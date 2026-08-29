package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.runtime.Composable
import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.Route
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard
import io.github.lingqiqi.meowceiler.ui.component.SettingsNavigationRow

/** 宿主 hub：只放分类入口，开关在分类页里。 */
@Composable
fun SystemUiPage(onBack: () -> Unit, onOpenCategory: (Route) -> Unit) {
    HostPage(titleRes = R.string.scope_systemui, hostPackage = Scope.SystemUi, onBack = onBack) {
        SettingsCard(testTag = "section.systemui.categories") {
            SettingsNavigationRow(
                titleRes = R.string.systemui_section_lockscreen,
                testTag = "row.systemui.lockscreen",
            ) { onOpenCategory(Route.SystemUiLockScreen) }
        }
    }
}
