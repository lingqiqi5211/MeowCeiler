package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard
import io.github.lingqiqi.meowceiler.ui.component.SettingsInfoRow
import io.github.lingqiqi.meowceiler.ui.settings.HookLogState
import io.github.lingqiqi5211.meowui.component.MeowPreferencePage
import io.github.lingqiqi5211.meowui.component.MeowTopBarAction
import io.github.lingqiqi5211.meowui.theme.MeowTheme

/** 一个作用域的全部日志，按时间连成一片，不拆成条目。 */
@Composable
fun ScopeLogPage(host: String, state: HookLogState, onBack: () -> Unit) {
    val context = LocalContext.current
    var showPrevious by remember { mutableStateOf(false) }

    val source = if (showPrevious) state.previous else state.current
    val text = remember(source, host) {
        source.filter { it.host == host }
            .sortedBy { it.lastMillis }
            .joinToString("\n\n") { it.fullText() }
    }

    MeowPreferencePage(
        title = stringResource(R.string.log_detail),
        subtitle = host,
        onBackClick = onBack,
        actionItems = listOf(
            MeowTopBarAction.Icon(
                icon = Icons.Filled.History,
                contentDescription = stringResource(R.string.log_generation_toggle),
                modifier = Modifier.testTag("action.log.generation"),
                onClick = { showPrevious = !showPrevious },
            ),
            MeowTopBarAction.Icon(
                icon = Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.log_copy),
                modifier = Modifier.testTag("action.log.copy"),
                onClick = { copyToClipboard(context, text) },
            ),
        ),
    ) {
        if (text.isEmpty()) {
            SettingsCard(testTag = "section.log.empty") {
                SettingsInfoRow(titleRes = R.string.log_empty, value = "", testTag = "row.log.empty")
            }
            return@MeowPreferencePage
        }

        SettingsCard(testTag = "section.log.detail") {
            item(key = "text") {
                // 与记录详情同一套：横向滚动、不折行，堆栈才看得出层级。
                SelectionContainer {
                    BasicText(
                        text = text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(16.dp)
                            .testTag("text.log.detail"),
                        style = MeowTheme.typography.summary.copy(
                            color = MeowTheme.colors.onSurface,
                            fontFamily = FontFamily.Monospace,
                        ),
                        softWrap = false,
                    )
                }
            }
        }
    }
}
