package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.restartPackage
import io.github.lingqiqi5211.meowui.component.MeowPreferencePage
import io.github.lingqiqi5211.meowui.component.MeowTopBarAction
import io.github.lingqiqi5211.meowui.component.rememberMeowSnackbarState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HostPage(
    titleRes: Int,
    hostPackage: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val snackbar = rememberMeowSnackbarState()
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.restart_failed)

    MeowPreferencePage(
        title = stringResource(titleRes),
        onBackClick = onBack,
        snackbarState = snackbar,
        actionItems = listOf(
            MeowTopBarAction.Icon(
                icon = Icons.Filled.RestartAlt,
                contentDescription = stringResource(R.string.restart_host),
                modifier = Modifier.testTag("action.restart"),
                onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { restartPackage(hostPackage) }
                        if (!ok) snackbar.show(failed)
                    }
                },
            ),
        ),
        content = content,
    )
}
