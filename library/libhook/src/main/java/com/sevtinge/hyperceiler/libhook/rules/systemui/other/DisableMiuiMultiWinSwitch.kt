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
package com.sevtinge.hyperceiler.libhook.rules.systemui.other

import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreAndroidVersion
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClassOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook

// by ljlvink
object DisableMiuiMultiWinSwitch : BaseHook() {
    override fun init() {
        // WMShell 的类由目标进程的最终 ClassLoader 负责加载，与 RemoveMiuiMultiWinSwitch 一致。
        val className = if (isMoreAndroidVersion(36)) {
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationDotView"
        } else {
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MiuiDotView"
        }
        val clazz = loadClassOrNull(className, classLoader)
        if (clazz == null) {
            // 类名随 ROM 变化时只禁用本规则，并留下足以定位的目标信息，不静默降级。
            XposedLog.e(
                TAG, packageName,
                "Target class not found: $className, classLoader=$classLoader"
            )
            return
        }
        clazz.findMethod { name("onDraw") }.createHook {
            returnConstant(null)
        }
    }
}
