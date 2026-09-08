package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi5211.meowui.component.MeowTip

@Composable
fun SystemUiIconsPage(onBack: () -> Unit) {
    HostPage(titleRes = R.string.systemui_icons, hostPackage = Scope.SystemUi, onBack = onBack) {
        MeowTip(message = stringResource(R.string.systemui_icons_empty))
    }
}
