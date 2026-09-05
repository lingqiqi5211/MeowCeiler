package io.github.lingqiqi.meowceiler.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.lingqiqi.meowceiler.shared.LauncherAliasName

/**
 * 桌面图标的显示状态，真实来源是 PackageManager 的组件启用状态，不另存偏好。
 * 禁用的是 activity-alias 而不是 SettingsActivity，禁掉真正的 Activity 会连设置界面都打不开。
 */
object LauncherIcon {
    fun isHidden(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(context.alias()) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    fun setHidden(context: Context, hidden: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            context.alias(),
            if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun Context.alias() = ComponentName(packageName, packageName + LauncherAliasName)
}
