/*
  * This file is part of HyperCeiler.

  * HyperCeiler is free software: you can redistribute it and/or modify
  * it under the terms of the GNU Affero General Public License as
  * published by the Free Software Foundation, either version 3 of the
  * License.

  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU Affero General Public License for more details.

  * You should have received a copy of the GNU Affero General Public License
  * along with this program.  If not, see <https://www.gnu.org/licenses/>.

  * Copyright (C) 2023-2026 HyperCeiler Contributions
*/
package com.sevtinge.hyperceiler.libhook.rules.systemui.statusbar.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.SubscriptionManager
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreAndroidVersion
import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreHyperOSVersion
import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreSmallVersion
import com.sevtinge.hyperceiler.libhook.utils.hookapi.StateFlowHelper.newReadonlyStateFlow
import com.sevtinge.hyperceiler.libhook.utils.hookapi.StateFlowHelper.setStateFlowValue
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobileClass.miuiCellularIconVM
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobilePrefs.card1
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobilePrefs.card2
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobilePrefs.hideIndicator
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobilePrefs.hideRoaming
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobilePrefs.isEnableDouble
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobileViewHelper
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.getObjectFieldAs
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.hookAllConstructors
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.setObjectField
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import io.github.kyuubiran.ezxhelper.xposed.common.AfterHookParam
import java.util.concurrent.ConcurrentHashMap

class MobilePublicHookV : BaseHook() {

    /** 双排模式下每个 subId 的 isVisible Flow，用于广播刷新可见性 */
    private val visibilityFlows = ConcurrentHashMap<Int, Any>()

    @Volatile
    private var broadcastRegistered = false

    override fun init() {
        miuiCellularIconVM.hookAllConstructors {
            after { param ->
                val cellularIcon = param.thisObject
                val mobileIconInteractor = param.args[2] ?: return@after
                val subId = mobileIconInteractor.getObjectFieldAs<Int>("subId")
                val isVisible = if (isMoreAndroidVersion(36) || isMoreHyperOSVersion(3f)) {
                    val pair = loadClass("kotlin.Pair")
                        .getConstructor(Object::class.java, Object::class.java)
                        .newInstance(false, false)
                    newReadonlyStateFlow(pair)
                } else {
                    newReadonlyStateFlow(false)
                }

                if (isEnableDouble && !(card1 || card2)) {
                    // 双排信号：始终显示 slot 0，隐藏 slot 1+（飞行模式时全部隐藏）
                    cellularIcon.setObjectField("isVisible", isVisible)
                    visibilityFlows[subId] = isVisible

                    val slotIndex = SubscriptionManager.getSlotIndex(subId)
                    val shouldShow = !MobileViewHelper.isAirplaneModeOn() &&
                        (MobileViewHelper.isSingleSimMode() || (slotIndex == 0))
                    updateVisibility(isVisible, shouldShow)

                    registerDefaultDataSubChangedReceiver(EzXposed.appContext)
                } else {
                    val getSlotIndex = SubscriptionManager.getSlotIndex(subId)
                    if ((card1 && getSlotIndex == 0) || (card2 && getSlotIndex == 1)) {
                        cellularIcon.setObjectField("isVisible", isVisible)
                    }
                }

                if (hideIndicator) {
                    cellularIcon.setObjectField("inOutVisible", newReadonlyStateFlow(false))
                }
                if (hideRoaming) {
                    cellularIcon.setObjectField("smallRoamVisible", newReadonlyStateFlow(false))
                    cellularIcon.setObjectField("mobileRoamVisible", newReadonlyStateFlow(false))
                }
                // 隐藏 hd
                if (!isMoreSmallVersion(200, 2f)) {
                    updateIconState(param, "smallHdVisible", "system_ui_status_bar_icon_small_hd")
                    updateIconState(param, "volteVisibleCn", "system_ui_status_bar_icon_big_hd")
                    updateIconState(param, "volteVisibleGlobal", "system_ui_status_bar_icon_big_hd")
                }
            }
        }
    }

    /** 更新 isVisible Flow（适配 a15/a16 类型差异） */
    private fun updateVisibility(isVisible: Any, shouldShow: Boolean) {
        if (isMoreAndroidVersion(36) || isMoreHyperOSVersion(3f)) {
            val pair = loadClass("kotlin.Pair")
                .getConstructor(Object::class.java, Object::class.java)
                .newInstance(shouldShow, shouldShow)
            setStateFlowValue(isVisible, pair)
        } else {
            setStateFlowValue(isVisible, shouldShow)
        }
    }

    /** 监听上网卡切换 + SIM 状态变化，作为可见性更新的安全网 */
    @Synchronized
    private fun registerDefaultDataSubChangedReceiver(context: Context) {
        if (broadcastRegistered) return
        broadcastRegistered = true

        val filter = IntentFilter().apply {
            addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED")
            addAction("android.intent.action.SIM_STATE_CHANGED")
            addAction("android.intent.action.AIRPLANE_MODE")
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val isAirplane = MobileViewHelper.isAirplaneModeOn()
                val isSingle = MobileViewHelper.isSingleSimMode()
                visibilityFlows.forEach { (subId, isVisible) ->
                    val slotIndex = SubscriptionManager.getSlotIndex(subId)
                    val shouldShow = !isAirplane && (isSingle || (slotIndex == 0))
                    updateVisibility(isVisible, shouldShow)
                }
            }
        }, filter)
    }

    private fun updateIconState(param: AfterHookParam, fieldName: String, key: String) {
        val opt = mPrefsMap.getStringAsInt(key, 0)
        if (opt != 0) {
            val value = when (opt) {
                1 -> true
                else -> false
            }
            param.thisObject.setObjectField(fieldName, newReadonlyStateFlow(value))
        }
    }
}
