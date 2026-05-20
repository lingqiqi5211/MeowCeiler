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
package com.sevtinge.hyperceiler.libhook.rules.securitycenter.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.view.Menu
import com.sevtinge.hyperceiler.libhook.R
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed.appContext
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed.initAppContext
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook

@SuppressLint("DiscouragedApi")
object AddAppManagerEntry : BaseHook() {
    //override val key = "add_aosp_app_manager_entry"
    private const val ITEM_ID_AOSP_APP_MANAGER = 0x484302

    private val idIdMiuixActionEndMenuGroup by lazy {
        appContext.resources.getIdentifier("miuix_action_end_menu_group", "id", EzXposed.packageName)
    }

    override fun init() {
        findClassIfExists("com.miui.appmanager.AppManagerMainActivity")
            ?.findMethod {
                name("onCreateOptionsMenu")
                parameterTypes(Menu::class.java)
            }
            ?.createHook {
                after {
                    val activity = it.thisObject as Activity
                    val menu = it.args[0] as Menu
                    if (menu.findItem(ITEM_ID_AOSP_APP_MANAGER) != null) return@after

                    initAppContext(activity, true)
                    val menuItem = menu.add(
                        idIdMiuixActionEndMenuGroup,
                        ITEM_ID_AOSP_APP_MANAGER,
                        Menu.NONE,
                        R.string.security_center_aosp_app_manager
                    )
                    menuItem.setOnMenuItemClickListener {
                        activity.startActivity(createAppManagerIntent())
                        true
                    }
                }
            }
    }

    private fun createAppManagerIntent(): Intent =
        Intent(Intent.ACTION_MAIN).setClassName(
            "com.android.settings",
            "com.android.settings.applications.ManageApplications"
        )
}
