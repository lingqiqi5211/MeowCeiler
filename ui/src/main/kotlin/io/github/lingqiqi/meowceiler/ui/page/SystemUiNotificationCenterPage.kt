package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.runtime.Composable
import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.Route
import io.github.lingqiqi.meowceiler.ui.component.SettingsNavigationRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsSection

@Composable
fun SystemUiNotificationCenterPage(onBack: () -> Unit, onOpen: (Route) -> Unit) {
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
        }
    }
}
