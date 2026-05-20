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
import android.os.Bundle
import android.os.UserHandle
import android.view.Menu
import android.view.MenuInflater
import androidx.fragment.app.Fragment
import com.sevtinge.hyperceiler.libhook.R
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.java.Methods
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed.appContext
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed.initAppContext
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook

@SuppressLint("DiscouragedApi")
object AddAppInfoEntry : BaseHook() {
    //override val key = "add_aosp_app_info_entry"
    private const val ITEM_ID_AOSP_APP_INFO = 0x484301

    private val idIdMiuixActionEndMenuGroup by lazy {
        appContext.resources.getIdentifier("miuix_action_end_menu_group", "id", EzXposed.packageName)
    }
    private val idStringAppManagerAppInfoLabel by lazy {
        appContext.resources.getIdentifier("app_manager_app_info_label", "string", EzXposed.packageName)
    }

    override fun init() {
        val detailsFragmentClass =
            findClassIfExists("com.miui.appmanager.fragment.ApplicationsDetailsFragment")
        if (detailsFragmentClass != null) {
            hookDetailsFragment(detailsFragmentClass)
        } else {
            hookDetailsActivity()
        }
    }

    private fun hookDetailsFragment(clazz: Class<*>) {
        clazz.findMethod {
            name("onCreateOptionsMenu")
            parameterTypes(Menu::class.java, MenuInflater::class.java)
        }
            .createHook {
                after {
                    val fragment = it.thisObject as Fragment
                    val activity = fragment.activity ?: return@after
                    addAppInfoEntry(activity, it.args[0] as Menu)
                }
            }
    }

    private fun hookDetailsActivity() {
        findClassIfExists("com.miui.appmanager.ApplicationsDetailsActivity")
            ?.findMethod {
                name("onCreateOptionsMenu")
                parameterTypes(Menu::class.java)
            }
            ?.createHook {
                after {
                    addAppInfoEntry(it.thisObject as Activity, it.args[0] as Menu)
                }
            }
    }

    private fun addAppInfoEntry(activity: Activity, menu: Menu) {
        if (menu.findItem(ITEM_ID_AOSP_APP_INFO) != null) return

        initAppContext(activity, true)
        val pkgName = activity.intent.getStringExtra("package_name") ?: return
        val myUserId = Methods.callStaticMethod(UserHandle::class.java, "myUserId") as Int
        val uid = activity.intent.getIntExtra("miui.intent.extra.USER_ID", myUserId)
        val menuItem = menu.add(
            idIdMiuixActionEndMenuGroup,
            ITEM_ID_AOSP_APP_INFO,
            Menu.NONE,
            R.string.security_center_aosp_app_info_label
        )
        menuItem.setOnMenuItemClickListener {
            activity.startActivity(createAppInfoIntent(activity, pkgName, uid))
            true
        }
    }

    private fun createAppInfoIntent(activity: Activity, pkgName: String, uid: Int): Intent {
        val bundle = Bundle().apply {
            putString("package", pkgName)
            putInt("uid", uid)
        }
        return Intent(Intent.ACTION_MAIN).apply {
            setClassName("com.android.settings", "com.android.settings.SubSettings")
            putExtra(
                ":settings:show_fragment",
                "com.android.settings.applications.appinfo.AppInfoDashboardFragment"
            )
            putExtra(":settings:show_fragment_title", activity.getString(idStringAppManagerAppInfoLabel))
            putExtra(":settings:show_fragment_args", bundle)
        }
    }
}
