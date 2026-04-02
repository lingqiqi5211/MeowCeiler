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
package com.sevtinge.hyperceiler.libhook.rules.downloads

import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder.`-Static`.methodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHooks
import java.io.File
import java.io.IOException

// https://github.com/YifePlayte/WOMMO/blob/6069e7d/app/src/main/java/com/yifeplayte/wommo/hook/hooks/singlepackage/downloadprovider/RemoveXlDownload.kt
object RemoveXlDownload : BaseHook() {
    private fun shouldBlock(path: String?): Boolean {
        return path?.contains("${File.separator}.xlDownload") == true
    }

    override fun init() {
        loadClass("java.io.File").methodFinder()
            .filter { name in setOf("mkdir", "mkdirs") }
            .toList()
            .createHooks {
                before {
                    if (shouldBlock((it.thisObject as File).path)) {
                        it.result = true
                    }
                }
            }


        loadClass("com.android.providers.downloads.config.XLConfig")
            .methodFinder()
            .filter { name in setOf("setDebug", "setSoDebug") }
            .toList()
            .createHooks {
                before {
                    if (shouldBlock(it.args[2] as? String)) {
                        it.result = null
                    }
                }
            }

        loadClass("com.android.providers.downloads.util.FileUtil").methodFinder()
            .filterByName("createFile").single().createHook {
                before {
                    if (shouldBlock(it.args[0] as String)) {
                        it.throwable = IOException(".xlDownload is blocked")
                    }
                }
            }
    }
}
