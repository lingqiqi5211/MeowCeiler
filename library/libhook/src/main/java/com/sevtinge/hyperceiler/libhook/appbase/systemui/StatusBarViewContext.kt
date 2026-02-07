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

import android.content.Context
import android.content.res.Resources
import android.telephony.SubscriptionManager
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreAndroidVersion
import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreHyperOSVersion
import com.sevtinge.hyperceiler.libhook.utils.api.DisplayUtils
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.AppsTool
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.getIntField
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.getObjectField
import io.github.kyuubiran.ezxhelper.android.util.ViewUtil.findViewByIdName

/**
 * 状态栏视图操作上下文
 *
 * 封装状态栏视图操作中的常用功能，屏蔽 Android 版本差异。
 *
 * @property rootView 当前操作的根视图（如 ModernStatusBarMobileView / NetworkSpeedView 等）
 * @property slot 插槽名称
 * @property subId SIM 卡订阅 ID（仅 mobile 场景有效，其他场景为 -1）
 * @property viewModel 关联的 ViewModel 引用（可选）
 */
class StatusBarViewContext(
    val rootView: ViewGroup,
    val slot: String = "",
    val subId: Int = -1,
    val viewModel: Any? = null
) {
    /**
     * 当前 SIM 卡的槽位索引（0 或 1）
     * 仅 mobile 场景有效，其他场景为 -1
     */
    val slotIndex: Int by lazy {
        if (subId != -1) SubscriptionManager.getSlotIndex(subId) else -1
    }

    /**
     * 当前 View 的 Context
     */
    val context: Context get() = rootView.context

    /**
     * 是否为 Android 16+
     */
    val isAndroid16: Boolean by lazy { isMoreAndroidVersion(36) }

    /**
     * 是否为 HyperOS 3+
     */
    val isHyperOS3: Boolean by lazy { isMoreHyperOSVersion(3f) }

    // ==================== 视图查找 ====================

    /**
     * 按资源 ID 名称查找子视图
     */
    fun <T : View> findView(idName: String): T? {
        @Suppress("UNCHECKED_CAST")
        return rootView.findViewByIdName(idName) as? T
    }

    /**
     * 按 tag 查找子视图
     */
    fun <T : View> findViewWithTag(tag: String): T? {
        @Suppress("UNCHECKED_CAST")
        return rootView.findViewWithTag(tag) as? T
    }

    /**
     * 获取 mobile_group（LinearLayout / MobileSignalAnimatorContainer）
     * 适用于 MOBILE_CONSTRUCT_AND_BIND 场景
     */
    fun getMobileGroup(): LinearLayout? {
        return findView("mobile_group")
    }

    /**
     * 获取信号容器视图（mobile_signal_container）
     *
     * 在 a15 OS3 和 a16 OS3 上都是 MobileSignalAnimatorView（ConstraintLayout 子类），
     * 统一以 ViewGroup 返回，不强转 FrameLayout，避免 ClassCastException。
     *
     * 旧版（非 OS3）使用 mobile_container_left。
     */
    fun getSignalContainer(): ViewGroup? {
        return if (isHyperOS3) {
            findView("mobile_signal_container")
        } else {
            findView("mobile_container_left")
        }
    }

    /**
     * 获取右侧信号容器（仅旧版非 OS3 有）
     */
    fun getSignalContainerRight(): ViewGroup? {
        return if (isHyperOS3) null else findView("mobile_container_right")
    }

    /**
     * 获取主信号 ImageView
     */
    fun getMobileSignal(): ImageView? {
        return findView("mobile_signal")
    }

    /**
     * 获取网络类型 ImageView（小 5G）
     */
    fun getMobileType(): ImageView? {
        return findView("mobile_type")
    }

    /**
     * 获取网络类型单独显示 TextView（大 5G）
     */
    fun getMobileTypeSingle(): TextView? {
        return findView("mobile_type_single")
    }

    // ==================== 工具方法 ====================

    /**
     * 获取模块资源
     */
    fun getModuleRes(): Resources {
        return AppsTool.getModuleRes(context)
    }

    /**
     * dp 转 px
     */
    fun dp2px(dp: Float): Int {
        return DisplayUtils.dp2px(dp)
    }

    /**
     * dp 转 px（int 参数版）
     */
    fun dp2px(dp: Int): Int {
        return DisplayUtils.dp2px(dp)
    }

    /**
     * 判断给定 subId 是否为当前默认数据 SIM 卡
     */
    fun isDefaultDataSim(subId: Int = this.subId): Boolean {
        return subId == SubscriptionManager.getDefaultDataSubscriptionId()
    }

    /**
     * 从根视图获取 subId 字段（用于从 ModernStatusBarMobileView 读取）
     */
    fun getSubIdFromView(): Int {
        return try {
            rootView.getIntField("subId")
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * 从根视图获取 slot 字段
     */
    fun getSlotFromView(): String {
        return try {
            rootView.getObjectField("slot") as? String ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    // ==================== 视图操作 ====================

    /**
     * 在指定父容器中添加子视图
     */
    fun addView(parent: ViewGroup, child: View, params: ViewGroup.LayoutParams? = null) {
        if (params != null) {
            parent.addView(child, params)
        } else {
            parent.addView(child)
        }
    }

    /**
     * 从父容器中移除子视图
     */
    fun removeView(parent: ViewGroup, child: View) {
        parent.removeView(child)
    }

    /**
     * 设置视图可见性
     */
    fun setVisibility(view: View?, visibility: Int) {
        view?.visibility = visibility
    }

    override fun toString(): String {
        return "StatusBarViewContext(slot='$slot', subId=$subId, slotIndex=$slotIndex, " +
            "rootView=${rootView.javaClass.simpleName}, isAndroid16=$isAndroid16, " +
            "isHyperOS3=$isHyperOS3)"
    }
}
