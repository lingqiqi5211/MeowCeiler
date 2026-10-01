package io.github.lingqiqi.meowceiler.ui.page

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.component.FeatureSwitchRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsNavigationRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsSection

@Composable
fun SettingsHostPage(onBack: () -> Unit) {
    val keys = Preferences.SettingsIcons
    val context = LocalContext.current
    HostPage(titleRes = R.string.scope_settings, hostPackage = Scope.Settings, onBack = onBack) {
        SettingsSection(titleRes = R.string.settings_icons, testTag = "section.settings.icons") {
            FeatureSwitchRow(
                key = keys.NotificationCount,
                titleRes = R.string.settings_icons_count,
                summaryRes = R.string.settings_icons_count_summary,
            )
            FeatureSwitchRow(key = keys.Entry, titleRes = R.string.settings_icons_entry)
            SettingsNavigationRow(
                titleRes = R.string.settings_icons_open_host,
                testTag = "row.settings.icons.host",
            ) {
                openIconDisplayCustomization(context)
            }
        }
    }
}

/**
 * 宿主没给这一页单独的 action，只能经 SubSettings 指定 fragment。
 *
 * 那一页多半已经开着（用户正是从那儿过来的），把设置那个任务挪到前台而不是再开一个。
 */
private fun openIconDisplayCustomization(context: Context) {
    val intent = Intent()
        .setClassName(Scope.Settings, "com.android.settings.SubSettings")
        .putExtra(":android:show_fragment", "com.android.settings.IconDisplayCustomizationSettings")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    runCatching { context.startActivity(intent) }
}
