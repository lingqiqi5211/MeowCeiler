package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.runtime.Composable
import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.Route
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard
import io.github.lingqiqi.meowceiler.ui.component.SettingsNavigationRow

@Composable
fun SystemUiPage(onBack: () -> Unit, onOpenCategory: (Route) -> Unit) {
    HostPage(titleRes = R.string.scope_systemui, hostPackage = Scope.SystemUi, onBack = onBack) {
        SettingsCard(testTag = "section.systemui.categories") {
            SettingsNavigationRow(
                titleRes = R.string.systemui_section_lockscreen,
                testTag = "row.systemui.lockscreen",
            ) { onOpenCategory(Route.SystemUiLockScreen) }
            SettingsNavigationRow(
                titleRes = R.string.systemui_section_statusbar,
                testTag = "row.systemui.statusbar",
            ) { onOpenCategory(Route.SystemUiStatusBar) }
            SettingsNavigationRow(
                titleRes = R.string.systemui_notification_control_center,
                testTag = "row.systemui.notification.controlcenter",
            ) { onOpenCategory(Route.SystemUiNotificationCenter) }
        }
    }
}
