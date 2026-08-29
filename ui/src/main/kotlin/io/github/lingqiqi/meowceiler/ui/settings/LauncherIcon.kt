package io.github.lingqiqi.meowceiler.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.lingqiqi.meowceiler.shared.LauncherAliasName

/**
 * 桌面图标的显示状态。
 *
 * 状态的真实来源是 PackageManager 的组件启用状态，不另存一份偏好 —— 重装、清数据、
 * 或用户从别处改过之后，偏好里那份就和系统实际状态对不上了。
 *
 * 禁用的是清单里的 activity-alias 而不是 SettingsActivity 本身：禁掉真正的 Activity
 * 会连设置界面自己都启动不了。
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
            // 不加 DONT_KILL_APP 的话系统会顺手把本进程杀掉，用户正按着开关。
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun Context.alias() = ComponentName(packageName, packageName + LauncherAliasName)
}
