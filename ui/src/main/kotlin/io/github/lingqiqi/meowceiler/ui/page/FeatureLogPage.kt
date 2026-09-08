package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.shared.HookEventKind
import io.github.lingqiqi.meowceiler.shared.HookLogRecord
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard
import io.github.lingqiqi.meowceiler.ui.component.SettingsInfoRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsSection
import io.github.lingqiqi.meowceiler.ui.component.SettingsValueRow
import io.github.lingqiqi.meowceiler.ui.settings.HookLogState
import io.github.lingqiqi.meowceiler.ui.settings.isProblem
import io.github.lingqiqi5211.meowui.component.MeowPreferencePage
import io.github.lingqiqi5211.meowui.component.MeowTopBarAction
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FeatureLogPage(tag: String, state: HookLogState, onBack: () -> Unit, onOpenRecord: (HookLogRecord) -> Unit) {
    val scope = rememberCoroutineScope()
    var showPrevious by remember { mutableStateOf(false) }

    val records = (if (showPrevious) state.previous else state.current).filter { it.tag == tag }
    val installed = records.filter { it.kind == HookEventKind.Installed }.maxByOrNull { it.lastMillis }
    val problems = records.filter { it.isProblem }.sortedByDescending { it.lastMillis }
    val trace = records.filter { it.kind == HookEventKind.Log && (it.level == 'D' || it.level == 'I') }
        .sortedByDescending { it.lastMillis }

    MeowPreferencePage(
        title = stringResource(R.string.feature_health),
        subtitle = stringResource(generationRes(showPrevious)),
        onBackClick = onBack,
        actionItems = listOf(
            MeowTopBarAction.Icon(
                icon = Icons.Filled.History,
                contentDescription = stringResource(R.string.log_generation_toggle),
                modifier = Modifier.testTag("action.log.generation"),
                onClick = { showPrevious = !showPrevious },
            ),
            MeowTopBarAction.Icon(
                icon = Icons.Filled.DeleteSweep,
                contentDescription = stringResource(R.string.log_clear),
                modifier = Modifier.testTag("action.log.clear"),
                onClick = { scope.launch { state.clear(tag) } },
            ),
        ),
    ) {
        if (installed != null) {
            SettingsCard(testTag = "section.log.installed") {
                SettingsValueRow(
                    title = stringResource(R.string.log_kind_installed),
                    value = installed.time(),
                    testTag = "row.log.installed",
                    navigation = true,
                ) { onOpenRecord(installed) }
            }
        }
        if (problems.isEmpty() && trace.isEmpty()) {
            if (installed != null) return@MeowPreferencePage
            SettingsCard(testTag = "section.log.empty") {
                SettingsInfoRow(
                    titleRes = R.string.log_empty,
                    value = "",
                    testTag = "row.log.empty",
                )
            }
            return@MeowPreferencePage
        }

        if (problems.isNotEmpty()) {
            SettingsSection(titleRes = R.string.log_problems, testTag = "section.log.problems") {
                problems.forEachIndexed { index, record ->
                    SettingsValueRow(
                        title = stringResource(record.kind.titleRes()),
                        value = record.summary(),
                        testTag = "row.log.problem.$index",
                        navigation = true,
                    ) { onOpenRecord(record) }
                }
            }
        }

        if (trace.isNotEmpty()) {
            SettingsSection(titleRes = R.string.log_trace, testTag = "section.log.trace") {
                trace.forEachIndexed { index, record ->
                    SettingsValueRow(
                        title = stringResource(R.string.log_trace_entry),
                        value = "${record.time()} ${record.message.lineSequence().first()}",
                        testTag = "row.log.trace.$index",
                        navigation = true,
                    ) { onOpenRecord(record) }
                }
            }
        }
    }
}

internal fun HookEventKind.titleRes(): Int = when (this) {
    HookEventKind.Installed -> R.string.log_kind_installed
    HookEventKind.InitFailed -> R.string.log_kind_init_failed
    HookEventKind.HookFailed -> R.string.log_kind_hook_failed
    HookEventKind.CallbackFailed -> R.string.log_kind_callback_failed
    HookEventKind.SafeModeBlocked -> R.string.log_kind_safe_mode
    HookEventKind.Log -> R.string.log_kind_log
}

/** 次数按对数阶梯上报，是实际次数的下界，因此显示「+」。 */
private fun HookLogRecord.summary(): String {
    val times = if (count > 1) " ×$count+" else ""
    return "${time()}$times"
}

private fun HookLogRecord.time(): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastMillis))
