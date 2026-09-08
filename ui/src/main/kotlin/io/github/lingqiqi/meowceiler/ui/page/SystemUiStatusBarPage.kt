package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.runtime.Composable
import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.Route
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard
import io.github.lingqiqi.meowceiler.ui.component.SettingsNavigationRow

@Composable
fun SystemUiStatusBarPage(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    HostPage(titleRes = R.string.systemui_section_statusbar, hostPackage = Scope.SystemUi, onBack = onBack) {
        SettingsCard(testTag = "section.systemui.statusbar.categories") {
            SettingsNavigationRow(titleRes = R.string.systemui_clock, testTag = "row.systemui.statusbar.clock") {
                onOpen(Route.SystemUiClock)
            }
            SettingsNavigationRow(titleRes = R.string.systemui_icons, testTag = "row.systemui.statusbar.icons") {
                onOpen(Route.SystemUiIcons)
            }
        }
    }
}
