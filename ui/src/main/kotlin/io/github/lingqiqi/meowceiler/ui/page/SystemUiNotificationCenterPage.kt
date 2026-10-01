package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi.meowceiler.shared.hyperOsVersion
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.Route
import io.github.lingqiqi.meowceiler.ui.component.FeatureSwitchRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsNavigationRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsSection
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue

@Composable
fun SystemUiNotificationCenterPage(
    onBack: () -> Unit,
    onOpen: (Route) -> Unit,
) {
    val unlocked by rememberMeowPreferenceValue(Preferences.SystemUi.FocusUnlock)
    // HyperOS 3 管理媒体岛，HyperOS 4 由系统页面管理通知应用。
    val managed = hyperOsVersion >= 4
    HostPage(
        titleRes = R.string.systemui_notification_control_center,
        hostPackage = Scope.SystemUi,
        onBack = onBack,
    ) {
        SettingsSection(
            titleRes = R.string.systemui_notification_section,
            testTag = "section.systemui.notification",
        ) {
            SettingsNavigationRow(
                titleRes = R.string.systemui_notification_weather,
                testTag = "row.systemui.notification.weather",
            ) {
                onOpen(Route.SystemUiNotificationWeather)
            }
            FeatureSwitchRow(
                key = Preferences.SystemUi.FocusUnlock,
                titleRes = R.string.systemui_focus_unlock,
                summaryRes =
                    if (managed) {
                        R.string.systemui_focus_unlock_summary_managed
                    } else {
                        R.string.systemui_focus_unlock_summary
                    },
            )
            SettingsNavigationRow(
                titleRes = R.string.systemui_focus_apps,
                testTag = "row.systemui.focus.apps",
                visible = unlocked && hyperOsVersion == 3,
            ) {
                onOpen(Route.SystemUiFocusApps)
            }
        }
    }
}
