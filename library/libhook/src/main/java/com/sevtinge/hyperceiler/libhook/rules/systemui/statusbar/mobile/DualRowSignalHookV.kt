/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.systemui.statusbar.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.telephony.SubscriptionManager
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.util.size
import com.sevtinge.hyperceiler.libhook.appbase.systemui.DarkInfo
import com.sevtinge.hyperceiler.libhook.appbase.systemui.StatusBarViewConfig
import com.sevtinge.hyperceiler.libhook.appbase.systemui.StatusBarViewContext
import com.sevtinge.hyperceiler.libhook.appbase.systemui.StatusBarViewUtils
import com.sevtinge.hyperceiler.libhook.appbase.systemui.statusBarViewConfig
import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreHyperOSVersion
import com.sevtinge.hyperceiler.libhook.utils.api.DisplayUtils
import com.sevtinge.hyperceiler.libhook.utils.api.ProjectApi
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobileClass.mobileSignalController
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobileClass.networkController
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.AppsTool.getModuleRes
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.callMethodAs
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.getAdditionalInstanceField
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.getBooleanField
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.getObjectField
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.getObjectFieldAs
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.setAdditionalInstanceField
import com.sevtinge.hyperceiler.libhook.utils.log.XposedLog
import io.github.kyuubiran.ezxhelper.android.util.ViewUtil.findViewByIdName
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder.`-Static`.methodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import java.util.concurrent.ConcurrentHashMap

class DualRowSignalHookV : StatusBarViewUtils() {

    companion object {
        private const val TAG_DUAL_CONTAINER = "dual_signal_container"
        private const val TAG_SIGNAL_SLOT1 = "dual_signal_slot1"
        private const val TAG_SIGNAL_SLOT2 = "dual_signal_slot2"
    }

    private val rightMargin by lazy {
        mPrefsMap.getInt("system_ui_statusbar_mobile_network_icon_right_margin", 8) - 8
    }
    private val leftMargin by lazy {
        mPrefsMap.getInt("system_ui_statusbar_mobile_network_icon_left_margin", 8) - 8
    }
    private val iconScale by lazy {
        mPrefsMap.getInt("system_ui_statusbar_mobile_network_icon_size", 10)
    }
    private val verticalOffset by lazy {
        mPrefsMap.getInt("system_ui_statusbar_mobile_network_icon_vertical_offset", 40)
    }

    private val selectedIconStyle by lazy {
        mPrefsMap.getString("system_ui_status_mobile_network_icon_style", "")
    }

    private val dualSignalResMap = HashMap<String, Int>()

    /** 每张 SIM 的信号等级，以 subscriptionId 为 key */
    private val simSignalLevels = ConcurrentHashMap<Int, Int>()

    /** 每个 SIM 的 dataSim 状态，用于检测上网卡切换 */
    private val simDataSimState = ConcurrentHashMap<Int, Boolean>()

    @Volatile
    private var networkControllerInstance: Any? = null

    // ==================== 框架回调 ====================

    override fun onCreateViewConfig() = statusBarViewConfig {
        hookPoint = StatusBarViewConfig.HookPoint.MOBILE_CONSTRUCT_AND_BIND
        darkModeStrategy = StatusBarViewConfig.DarkModeStrategy.TINT_LIGHT_COLOR_FLOW
        targetSlot = "mobile"
    }

    /**
     * 对所有双卡 View 创建双排容器。
     * 上网卡切换时无需重建，只需刷新图标和可见性。
     */
    override fun onViewCreated(ctx: StatusBarViewContext) {
        val subId = ctx.subId
        val mobileGroup = ctx.getMobileGroup() ?: return
        val signalContainer = ctx.getSignalContainer() ?: return
        val mobileSignal = ctx.getMobileSignal() ?: return

        // 判断当前活跃 SIM 卡数量
        val simCount = getActiveMobileControllerCount()
        val isDualSim = simCount > 1

        // 单卡模式：保留系统原始图标，不做任何修改
        if (!isDualSim) {
            return
        }

        // 设置 mobile_group 边距
        mobileGroup.setPadding(
            DisplayUtils.dp2px(leftMargin * 0.5f), 0,
            DisplayUtils.dp2px(rightMargin * 0.5f), 0
        )

        // 检查是否已有双排容器（constructAndBind 对同一个 subId 会被调用多次，对应不同显示位置）
        val existingContainer = ctx.findViewWithTag<FrameLayout>(TAG_DUAL_CONTAINER)
        if (existingContainer != null) {
            /** 已有容器，只缓存视图并刷新 */
            registerViewCache(subId, ctx.rootView)
            if (simSignalLevels.isNotEmpty()) {
                val isUseTint = ctx.rootView.getAdditionalInstanceField("dualDarkIsUseTint") as? Boolean ?: false
                val isLight = ctx.rootView.getAdditionalInstanceField("dualDarkIsLight") as? Boolean ?: true
                val color = ctx.rootView.getAdditionalInstanceField("dualDarkColor") as? Int
                refreshDualIconsForView(ctx.rootView, isUseTint, isLight, color)
            }
            return
        }

        // 双卡模式：为每个 View 都创建双排信号容器
        val dualContainer = FrameLayout(ctx.context).apply {
            tag = TAG_DUAL_CONTAINER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val slot1 = ImageView(ctx.context).apply {
            tag = TAG_SIGNAL_SLOT1
            adjustViewBounds = true
        }
        val slot2 = ImageView(ctx.context).apply {
            tag = TAG_SIGNAL_SLOT2
            adjustViewBounds = true
        }

        val signalLp = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (iconScale != 10) DisplayUtils.dp2px(iconScale * 2.0f)
            else ViewGroup.LayoutParams.MATCH_PARENT
        )
        dualContainer.addView(slot1, ViewGroup.LayoutParams(signalLp))
        dualContainer.addView(slot2, ViewGroup.LayoutParams(signalLp))

        // 给双排容器分配 ID 以供约束引用（替代 mobile_signal 的锚点角色）
        val dualContainerId = View.generateViewId()
        dualContainer.id = dualContainerId

        signalContainer.addView(dualContainer)

        // 约束双排容器到 parent end（与 mobile_signal 原位置一致）
        try {
            val dlp = dualContainer.layoutParams
            dlp.javaClass.getField("endToEnd").setInt(dlp, 0)     // PARENT_ID
            dlp.javaClass.getField("topToTop").setInt(dlp, 0)
            dlp.javaClass.getField("bottomToBottom").setInt(dlp, 0)
            dualContainer.layoutParams = dlp
        } catch (_: Throwable) {}

        mobileSignal.visibility = View.GONE

        if (isMoreHyperOSVersion(3f)) {
            // 修正小 5G 定位：将约束锚点从 mobile_signal 切换到 dualContainer（左上角）
            (signalContainer.findViewByIdName("mobile_type") as? ImageView)?.let { mobileType ->
                try {
                    val lp = mobileType.layoutParams
                    lp.javaClass.getField("endToStart").setInt(lp, dualContainerId)
                    lp.javaClass.getField("topToTop").setInt(lp, dualContainerId)
                    mobileType.layoutParams = lp
                } catch (_: Throwable) {}
            }

            // 修正移动指示器定位：将约束锚点从 mobile_signal 切换到 dualContainer（左下角）
            (signalContainer.findViewByIdName("mobile_left_mobile_inout") as? ImageView)?.let { inout ->
                try {
                    val lp = inout.layoutParams
                    lp.javaClass.getField("endToStart").setInt(lp, dualContainerId)
                    lp.javaClass.getField("bottomToBottom").setInt(lp, dualContainerId)
                    lp.javaClass.getField("topToTop").setInt(lp, -1)
                    inout.layoutParams = lp
                } catch (_: Throwable) {}
            }
        }

        // 调整小标签的上边距（非 OS3）
        if (!isMoreHyperOSVersion(3f)) {
            fun adjustTopMargin(view: View?) {
                (view?.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.topMargin = -18
                    view.layoutParams = it
                }
            }
            adjustTopMargin(mobileGroup.findViewByIdName("mobile_small_hd") as? ImageView)
            adjustTopMargin(mobileGroup.findViewByIdName("mobile_small_roam") as? ImageView)
            adjustTopMargin(mobileGroup.findViewByIdName("mobile_satellite") as? ImageView)
        }

        // 垂直偏移
        if (verticalOffset != 40) {
            dualContainer.translationY =
                DisplayUtils.dp2px((verticalOffset - 40) * 0.1f).toFloat()
        }

        // 缓存视图（每张卡的 View 都缓存）
        registerViewCache(subId, ctx.rootView)

        // 立即用当前信号数据刷新（如果有数据的话）
        if (simSignalLevels.isNotEmpty()) {
            refreshDualIconsForView(ctx.rootView, isUseTint = false, isLight = true)
        }

        // 小 5G 定位尺寸
        setDensityReplacement(
            "com.android.systemui",
            "dimen",
            "status_bar_mobile_type_half_to_top_distance",
            3f
        )
        setDensityReplacement(
            "com.android.systemui",
            "dimen",
            "status_bar_mobile_left_inout_over_strength",
            0f
        )
        setDensityReplacement(
            "com.android.systemui",
            "dimen",
            "status_bar_mobile_type_middle_to_strength_start",
            -0.4f
        )
    }

    /**
     * tintLightColorFlow 在 signalIconId 变化后也会被 collect，
     * 所以这里同时承担反色和信号图标刷新。
     */
    override fun onDarkModeChanged(ctx: StatusBarViewContext, darkInfo: DarkInfo) {
        val subId = ctx.subId
        if (subId == -1 || simSignalLevels.isEmpty()) return

        // 保存当前反色状态到 rootView
        ctx.rootView.setAdditionalInstanceField("dualDarkIsUseTint", darkInfo.isUseTint)
        ctx.rootView.setAdditionalInstanceField("dualDarkIsLight", darkInfo.isLight)
        ctx.rootView.setAdditionalInstanceField("dualDarkColor", darkInfo.color)

        refreshDualIconsForView(ctx.rootView, darkInfo.isUseTint, darkInfo.isLight, darkInfo.color)
    }

    override fun onInitExtra() {
        loadDualSignalRes()
        listenMobileSignal()
    }

    // ==================== 活跃 SIM 数量查询 ====================

    /** 获取当前活跃 SIM 卡数量 */
    private fun getActiveMobileControllerCount(): Int {
        val controller = networkControllerInstance ?: return 0
        return try {
            val controllers = controller
                .getObjectFieldAs<SparseArray<*>>("mMobileSignalControllers")
            controllers.size
        } catch (e: Throwable) {
            XposedLog.w(TAG, lpparam.packageName, "getActiveMobileControllerCount error: ${e.message}")
            0
        }
    }

    // ==================== 资源加载 ====================

    private fun loadDualSignalRes() {
        loadClass("com.android.systemui.SystemUIApplication")
            .methodFinder()
            .filterByName("onCreate")
            .single()
            .createHook {
                var isHooked = false
                after { param ->
                    if (!isHooked) {
                        isHooked = true
                        val modRes = getModuleRes(
                            param.thisObject.callMethodAs<Context>("getApplicationContext")
                        )
                        (1..2).forEach { slot ->
                            (0..5).forEach { lvl ->
                                arrayOf("", "dark", "tint").forEach { colorMode ->
                                    val isUseTint = colorMode == "tint"
                                    val isLight = colorMode.isNotEmpty()
                                    val resName =
                                        getSignalIconResName(slot, lvl, isUseTint, isLight)
                                    dualSignalResMap[resName] = modRes.getIdentifier(
                                        resName, "drawable", ProjectApi.mAppModulePkg
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }

    // ==================== 信号监听 ====================

    private fun listenMobileSignal() {
        // Hook notifyListeners：记录每张 SIM 的信号等级和 dataSim 状态
        mobileSignalController.methodFinder()
            .filterByName("notifyListeners")
            .single()
            .createHook {
                after { param ->
                    val signalController = param.thisObject

                    // 同时捕获 NetworkControllerImpl 实例
                    if (networkControllerInstance == null) {
                        runCatching {
                            networkControllerInstance =
                                signalController.getObjectField("mNetworkController")
                        }
                    }

                    val subscriptionInfo =
                        signalController.getObjectFieldAs<Any>("mSubscriptionInfo")
                    val subscriptionId =
                        subscriptionInfo.callMethodAs<Int>("getSubscriptionId")
                    val currentState =
                        signalController.getObjectFieldAs<Any>("mCurrentState")
                    val dataSim = currentState.getBooleanField("dataSim")
                    val connected = currentState.getBooleanField("connected")
                    val signalStrength = currentState.getObjectField("signalStrength")
                    val rawLevel = if (!connected) {
                        0
                    } else {
                        signalStrength?.callMethodAs<Int>("getMiuiLevel") ?: 0
                    }

                    // OS3 rawLevel >= 2 时需要 +1
                    val level = if (isMoreHyperOSVersion(3f) && rawLevel >= 2) {
                        rawLevel + 1
                    } else {
                        rawLevel
                    }

                    // 记录旧状态用于变化检测
                    val oldLevel = simSignalLevels[subscriptionId]
                    val oldDataSim = simDataSimState[subscriptionId]

                    // 更新当前 SIM 的信号等级和 dataSim 状态
                    simSignalLevels[subscriptionId] = level
                    simDataSimState[subscriptionId] = dataSim

                    // 检测是否有变化（包括 level 变化和 dataSim 切换）
                    val levelChanged = oldLevel != level
                    val dataSimChanged = oldDataSim != null && oldDataSim != dataSim

                    if (!levelChanged && !dataSimChanged) {
                        return@after
                    }

                    // 刷新所有缓存视图
                    refreshAllCachedViews()
                }
            }

        // Hook setCurrentSubscriptionsLocked：捕获 NetworkController 实例 + SIM 变化时重置信号数据
        networkController.methodFinder()
            .filterByName("setCurrentSubscriptionsLocked")
            .single()
            .createHook {
                before { param ->
                    val networkCtrl = param.thisObject
                    networkControllerInstance = networkCtrl

                    val subList = param.args[0] as List<*>
                    val currentSubscriptions =
                        networkCtrl.getObjectFieldAs<List<*>>("mCurrentSubscriptions")

                    // 提取新 subId 集合
                    val newSubIds = subList.filterNotNull().mapNotNull { subInfo ->
                        runCatching { subInfo.callMethodAs<Int>("getSubscriptionId") }.getOrNull()
                    }.toSet()

                    if (currentSubscriptions.size != subList.size) {
                        simSignalLevels.clear()
                        simDataSimState.clear()
                        clearViewCache()
                    } else if (newSubIds.isNotEmpty()) {
                        // 清理过时 subId（如 eSIM 切换）
                        val staleKeys = simSignalLevels.keys.filter { it !in newSubIds }
                        staleKeys.forEach { key ->
                            simSignalLevels.remove(key)
                            simDataSimState.remove(key)
                        }
                    }
                }
            }
    }

    // ==================== 视图刷新 ====================

    /** @return Pair(dataSimLevel, noDataSimLevel) */
    private fun getSignalLevelsForRender(): Pair<Int, Int> {
        val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()

        var dataSimLevel = 0
        var noDataSimLevel = 0

        simSignalLevels.forEach { (subId, level) ->
            if (subId == defaultDataSubId) {
                dataSimLevel = level
            } else {
                noDataSimLevel = level
            }
        }

        return Pair(dataSimLevel, noDataSimLevel)
    }

    private fun refreshDualIconsForView(
        rootView: ViewGroup,
        isUseTint: Boolean,
        isLight: Boolean,
        color: Int? = null
    ) {
        val dualContainer =
            rootView.findViewWithTag<FrameLayout>(TAG_DUAL_CONTAINER) ?: return
        if (dualContainer.visibility != View.VISIBLE) return

        val slot1 =
            dualContainer.findViewWithTag<ImageView>(TAG_SIGNAL_SLOT1) ?: return
        val slot2 =
            dualContainer.findViewWithTag<ImageView>(TAG_SIGNAL_SLOT2) ?: return

        val (dataLevel, noDataLevel) = getSignalLevelsForRender()

        val forceUseTint = selectedIconStyle != "theme"

        val slot1ResId = dualSignalResMap[getSignalIconResName(
            1, dataLevel,
            isUseTint = forceUseTint || isUseTint,
            isLight = isLight
        )]
        val slot2ResId = dualSignalResMap[getSignalIconResName(
            2, noDataLevel,
            isUseTint = forceUseTint || isUseTint,
            isLight = isLight
        )]

        if (slot1ResId != null && slot2ResId != null) {
            slot1.setImageResource(slot1ResId)
            slot2.setImageResource(slot2ResId)

            val tintList = if (forceUseTint) {
                when {
                    isUseTint && color != null -> ColorStateList.valueOf(color)
                    isUseTint -> {
                        (rootView.findViewByIdName("mobile_signal") as? ImageView)?.imageTintList
                    }
                    isLight -> ColorStateList.valueOf(Color.WHITE)
                    else -> null
                }
            } else {
                color?.let { c ->
                    if (isUseTint) ColorStateList.valueOf(c) else null
                } ?: slot1.imageTintList
            }

            slot1.imageTintList = tintList
            slot2.imageTintList = tintList
        }
    }

    /** 刷新所有缓存视图的双排信号图标（使用存储的反色状态） */
    private fun refreshAllCachedViews() {
        viewCache.values.forEach { viewSet ->
            viewSet.forEach { rootView ->
                rootView.post {
                    val isUseTint = rootView.getAdditionalInstanceField("dualDarkIsUseTint") as? Boolean ?: false
                    val isLight = rootView.getAdditionalInstanceField("dualDarkIsLight") as? Boolean ?: true
                    val color = rootView.getAdditionalInstanceField("dualDarkColor") as? Int
                    refreshDualIconsForView(rootView, isUseTint, isLight, color)
                }
            }
        }
    }

    // ==================== 图标资源名称生成 ====================

    private fun getSignalIconResName(
        slot: Int,
        level: Int,
        isUseTint: Boolean,
        isLight: Boolean
    ): String {
        val iconStyle = if (selectedIconStyle.isNotEmpty()) {
            "_$selectedIconStyle"
        } else {
            ""
        }
        val colorMode = if (!isUseTint || selectedIconStyle == "theme") {
            if (!isLight) "_dark" else ""
        } else {
            "_tint"
        }
        return "statusbar_signal_oa_${slot}_$level$colorMode$iconStyle"
    }
}
