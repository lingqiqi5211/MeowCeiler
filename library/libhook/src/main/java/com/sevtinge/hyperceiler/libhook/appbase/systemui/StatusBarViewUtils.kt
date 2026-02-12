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
package com.sevtinge.hyperceiler.libhook.appbase.systemui

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobileClass.miuiMobileIconBinder
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobileClass.modernStatusBarMobileView
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobileClass.statusBarIconControllerImpl
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobileViewHelper
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.callMethodAs
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.getAdditionalInstanceField
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.getIntField
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.getObjectField
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.hookAllConstructors
import com.sevtinge.hyperceiler.libhook.utils.log.XposedLog
import io.github.kyuubiran.ezxhelper.android.util.ViewUtil.findViewByIdName
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder.`-Static`.methodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createAfterHook
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * 状态栏视图 Hook 基类
 *
 * 提供模板方法模式，封装视图创建、暗色适配、数据监听等通用 Hook 逻辑。
 * 屏蔽 a15/a16、HyperOS 2/3 的 API 差异。
 */
abstract class StatusBarViewUtils : BaseHook() {

    /** 视图缓存：每个 subId 可能对应多个视图（状态栏、锁屏、下拉等位置） */
    protected val viewCache = ConcurrentHashMap<Int, MutableSet<ViewGroup>>()

    private lateinit var config: StatusBarViewConfig

    // ==================== 抽象/可覆写方法 ====================

    protected abstract fun onCreateViewConfig(): StatusBarViewConfig

    /** 视图创建完成后的回调 */
    protected open fun onViewCreated(ctx: StatusBarViewContext) {}

    /** 暗色模式变化回调 */
    protected open fun onDarkModeChanged(ctx: StatusBarViewContext, darkInfo: DarkInfo) {}

    /** 额外的初始化逻辑，在框架完成所有标准 Hook 注册后调用 */
    protected open fun onInitExtra() {}

    // ==================== 初始化 ====================

    final override fun init() {
        config = onCreateViewConfig()
        XposedLog.d(TAG, lpparam.packageName, "StatusBarViewUtils init: $config")

        when (config.hookPoint) {
            StatusBarViewConfig.HookPoint.MOBILE_CONSTRUCT_AND_BIND -> {
                hookMobileConstructAndBind()
            }
            StatusBarViewConfig.HookPoint.VIEW_ALL_CONSTRUCTORS -> {
                hookViewAllConstructors()
            }
            StatusBarViewConfig.HookPoint.ICON_CONTROLLER -> {
                hookIconController()
            }
        }

        when (config.darkModeStrategy) {
            StatusBarViewConfig.DarkModeStrategy.TINT_LIGHT_COLOR_FLOW -> {
                hookTintLightColorFlow()
            }
            StatusBarViewConfig.DarkModeStrategy.ON_DARK_CHANGED -> {
                hookOnDarkChanged()
            }
            StatusBarViewConfig.DarkModeStrategy.NONE -> {}
        }

        onInitExtra()
    }

    // ==================== Hook 实现 ====================

    private fun hookMobileConstructAndBind() {
        modernStatusBarMobileView.methodFinder()
            .filterByName("constructAndBind")
            .single()
            .createAfterHook { param ->
                val rootView = param.result as? ViewGroup ?: return@createAfterHook
                val subId = rootView.getIntField("subId")

                // 应用 subId 过滤
                if (config.subIdFilter != null && !config.subIdFilter!!.invoke(subId)) {
                    return@createAfterHook
                }

                val ctx = StatusBarViewContext(
                    rootView = rootView,
                    slot = config.targetSlot,
                    subId = subId
                )

                try {
                    onViewCreated(ctx)
                } catch (e: Throwable) {
                    XposedLog.e(TAG, lpparam.packageName, "onViewCreated error", e)
                }
            }
    }

    private fun hookViewAllConstructors() {
        val viewClass = config.viewClass ?: return

        viewClass.hookAllConstructors {
            after { param ->
                val view = param.thisObject as? ViewGroup ?: return@after

                if (config.useLayoutListener) {
                    view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                        private var handled = false
                        override fun onLayoutChange(
                            v: View, l: Int, t: Int, r: Int, b: Int,
                            ol: Int, ot: Int, or2: Int, ob: Int
                        ) {
                            if (handled) return
                            handled = true
                            val ctx = StatusBarViewContext(
                                rootView = view,
                                slot = config.targetSlot
                            )
                            try {
                                onViewCreated(ctx)
                            } catch (e: Throwable) {
                                XposedLog.e(TAG, lpparam.packageName, "onViewCreated error", e)
                            }
                        }
                    })
                } else {
                    val ctx = StatusBarViewContext(
                        rootView = view,
                        slot = config.targetSlot
                    )
                    try {
                        onViewCreated(ctx)
                    } catch (e: Throwable) {
                        XposedLog.e(TAG, lpparam.packageName, "onViewCreated error", e)
                    }
                }
            }
        }
    }

    private fun hookIconController() {
        statusBarIconControllerImpl.methodFinder()
            .filterByName("addIconGroup")
            .first()
            .createAfterHook { param ->
                val controller = param.thisObject
                try {
                    MobileViewHelper.forEachMobileViewFromController(controller) { view, subId ->
                        val ctx = StatusBarViewContext(
                            rootView = view as ViewGroup,
                            slot = config.targetSlot,
                            subId = subId
                        )
                        onViewCreated(ctx)
                    }
                } catch (e: Throwable) {
                    XposedLog.e(TAG, lpparam.packageName, "hookIconController error", e)
                }
            }
    }

    private fun hookTintLightColorFlow() {
        val javaAdapterKt = loadClass("com.android.systemui.util.kotlin.JavaAdapterKt")

        // Hook setImageResWithTintLight（新版直接方法 / 旧版 access$ 方法）
        val setImageResMethod = miuiMobileIconBinder.methodFinder()
            .filter { name.contains("setImageResWithTintLight") }
            .firstOrNull()

        setImageResMethod?.createHook {
            before { param ->
                val icon = param.args[0] as? ImageView ?: return@before
                val pair = param.args[2] ?: return@before

                val ctx = MobileViewHelper.buildContextFromSignalView(icon, config.targetSlot) ?: return@before
                val darkInfo = DarkInfo.fromTintLightColor(
                    isUseTint = pair.callMethodAs("getFirst"),
                    isLight = pair.callMethodAs("getSecond"),
                    color = pair.callMethodAs<Int>("getThird")
                )

                try {
                    onDarkModeChanged(ctx, darkInfo)
                } catch (e: Throwable) {
                    XposedLog.e(TAG, lpparam.packageName, "onDarkModeChanged error", e)
                }
            }
        }

        // Hook bind 方法收集 tintLightColorFlow（适用于内联 lambda 的新版本）
        val resetMethod = miuiMobileIconBinder.methodFinder()
            .filterByName($$"access$resetImageWithTintLight")
            .singleOrNull()

        if (resetMethod != null) {
            resetMethod.createHook {
                before { param ->
                    val icon = param.args[0] as? ImageView ?: return@before
                    val isUseTint = param.args[1] as Boolean
                    val isLight = param.args[2] as Boolean

                    val ctx = MobileViewHelper.buildContextFromSignalView(icon, config.targetSlot) ?: return@before
                    val color = if (isUseTint) {
                        icon.imageTintList?.defaultColor
                    } else {
                        // 非 tint 模式下，从缓存的 darkColor 回退
                        ctx.rootView.getAdditionalInstanceField("dualDarkColor") as? Int
                    }
                    val darkInfo = DarkInfo.fromTintLightColor(
                        isUseTint = isUseTint,
                        isLight = isLight,
                        color = color
                    )

                    try {
                        onDarkModeChanged(ctx, darkInfo)
                    } catch (e: Throwable) {
                        XposedLog.e(TAG, lpparam.packageName, "onDarkModeChanged error", e)
                    }
                }
            }
        } else {
            // 新版：hook bind 方法收集 tintLightColorFlow
            miuiMobileIconBinder.methodFinder()
                .filterByName("bind")
                .single()
                .createAfterHook { param ->
                    val viewBinding = param.result ?: return@createAfterHook

                    val tintLightColorFlow = try {
                        viewBinding.getObjectField($$"$tintLightColorFlow")
                    } catch (_: Exception) {
                        null
                    } ?: return@createAfterHook

                    val container = param.args[0] as View
                    val mobileSignal =
                        container.findViewByIdName("mobile_signal") as? ImageView
                            ?: return@createAfterHook

                    javaAdapterKt.callMethodAs(
                        "collectFlow",
                        container,
                        tintLightColorFlow,
                        Consumer<Any> { triple ->
                            val ctx = MobileViewHelper.buildContextFromSignalView(mobileSignal, config.targetSlot) ?: return@Consumer
                            val darkInfo = DarkInfo.fromTintLightColor(
                                isUseTint = triple.callMethodAs("getFirst"),
                                isLight = triple.callMethodAs("getSecond"),
                                color = triple.callMethodAs("getThird")
                            )

                            try {
                                onDarkModeChanged(ctx, darkInfo)
                            } catch (e: Throwable) {
                                XposedLog.e(
                                    TAG, lpparam.packageName,
                                    "onDarkModeChanged error", e
                                )
                            }
                        }
                    )
                }
        }
    }

    private fun hookOnDarkChanged() {
        config.viewClass ?: return

        findClass(
            "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView"
        )?.let { modernViewClass ->
            try {
                findAndHookMethod(
                    modernViewClass,
                    "onDarkChanged",
                    ArrayList::class.java,
                    Float::class.java,
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE,
                    Boolean::class.java,
                    object : com.sevtinge.hyperceiler.libhook.callback.IMethodHook {
                        override fun after(param: io.github.kyuubiran.ezxhelper.xposed.common.AfterHookParam) {
                            val view = param.thisObject as? ViewGroup ?: return
                            val ctx = StatusBarViewContext(
                                rootView = view,
                                slot = config.targetSlot
                            )
                            val darkInfo = DarkInfo.fromOnDarkChanged(
                                areas = param.args[0],
                                darkIntensity = param.args[1] as Float,
                                tint = param.args[2] as Int,
                                lightColor = param.args[3] as Int,
                                darkColor = param.args[4] as Int,
                                useTint = param.args[5] as Boolean
                            )
                            try {
                                onDarkModeChanged(ctx, darkInfo)
                            } catch (e: Throwable) {
                                XposedLog.e(
                                    TAG, lpparam.packageName,
                                    "onDarkModeChanged error", e
                                )
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                XposedLog.w(TAG, lpparam.packageName, "hookOnDarkChanged failed: ${e.message}")
            }
        }
    }

    // ==================== 视图缓存与遍历 ====================

    protected fun registerViewCache(key: Int, view: ViewGroup) {
        val set = viewCache.getOrPut(key) { java.util.Collections.synchronizedSet(mutableSetOf()) }
        // 清理已 detach 的无效 view
        set.removeAll { !it.isAttachedToWindow }
        set.add(view)
    }

    protected fun getCachedViews(key: Int): Set<ViewGroup> {
        return viewCache[key] ?: emptySet()
    }

    protected fun removeCachedViews(key: Int): MutableSet<ViewGroup>? {
        return viewCache.remove(key)
    }

    protected fun clearViewCache() {
        viewCache.clear()
    }

    protected fun forEachMobileView(subId: Int, callback: (View) -> Unit) {
        MobileViewHelper.forEachMobileView(subId, viewCache, callback)
    }
}
