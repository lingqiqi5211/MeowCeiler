package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.runtime.Composable
import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.Route
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard
import io.github.lingqiqi.meowceiler.ui.component.SettingsNavigationRow

@Composable
fun SystemFrameworkPage(
    onBack: () -> Unit,
    onOpenCategory: (Route) -> Unit,
) {
    HostPage(titleRes = R.string.scope_system_framework, hostPackage = Scope.SystemServer, onBack = onBack) {
        SettingsCard(testTag = "section.system_framework.categories") {
            SettingsNavigationRow(
                titleRes = R.string.package_manager_title,
                testTag = "row.system_framework.package_manager",
            ) { onOpenCategory(Route.PackageManager) }
        }
    }
}
