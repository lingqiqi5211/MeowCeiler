package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.runtime.Composable
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.component.FeatureSwitchRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard

@Composable
fun SystemUiLockScreenPage(onBack: () -> Unit) {
    HostPage(
        titleRes = R.string.systemui_section_lockscreen,
        hostPackage = Scope.SystemUi,
        onBack = onBack,
    ) {
        SettingsCard(testTag = "section.systemui.lockscreen") {
            FeatureSwitchRow(
                key = Preferences.SystemUi.DoubleTapToSleep,
                titleRes = R.string.systemui_double_tap_to_sleep,
                summaryRes = R.string.systemui_double_tap_to_sleep_summary,
            )
        }
    }
}
