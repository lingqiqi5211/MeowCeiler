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
package com.sevtinge.hyperceiler.libhook.rules.xmsf

import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.hookapi.dexkit.DexKit
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.callMethod
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createBeforeHook
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object UnlockFoucsAuth : BaseHook() {

    private val getAuthError by lazy {
        DexKit.findMember("getAuthError") { dexKitBridge ->
            dexKitBridge.findClass {
                matcher {
                    className = "com.xiaomi.xms.auth.AuthSession"
                }
            }.findMethod {
                matcher {
                    modifiers = Modifier.FINAL
                    paramCount = 1
                    returnType = "android.os.Bundle"
                }
            }.single()
        } as? Method
    }

    private val getAuthSuccess by lazy {
        DexKit.findMember("getAuthSuccess") { dexKitBridge ->
            dexKitBridge.findClass {
                matcher {
                    className = "com.xiaomi.xms.auth.AuthSession"
                }
            }.findMethod {
                matcher {
                    modifiers = Modifier.FINAL
                    paramCount = 0
                    returnType = "android.os.Bundle"
                }
            }.single()
        } as? Method
    }

    private val getErrorField by lazy {
        DexKit.findMember("getErrorField") { dexKitBridge ->
            dexKitBridge.findClass {
                matcher {
                    className = "com.xiaomi.xms.auth.AuthError"
                }
            }.findField {
                matcher {
                    type = "int"
                }
            }.single()
        } as? Field
    }

    override fun init() {
        val authErrorMethod = getAuthError ?: return
        val authSuccessMethod = getAuthSuccess ?: return
        val errorField = getErrorField ?: return

        authErrorMethod.createBeforeHook {
            val error = it.args[0] ?: return@createBeforeHook
            val errorCode = errorField.get(error)
            XposedLog.d(TAG, lpparam.packageName, "发现错误分发: $errorCode，正在拦截并强制返回成功")
            errorField.set(error, 0)
            it.result = it.thisObject.callMethod(authSuccessMethod.name)
        }

    }
}
