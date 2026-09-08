package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard
import io.github.lingqiqi.meowceiler.ui.component.SettingsInfoRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsValueRow
import io.github.lingqiqi.meowceiler.ui.component.MeowPreferenceSectionByTitle
import io.github.lingqiqi.meowceiler.ui.component.SettingsSection
import io.github.lingqiqi.meowceiler.ui.settings.HookLogState
import io.github.lingqiqi.meowceiler.ui.settings.featureTitle
import io.github.lingqiqi.meowceiler.ui.settings.groupByHost
import io.github.lingqiqi5211.meowui.component.MeowPreferencePage
import io.github.lingqiqi5211.meowui.component.MeowTopBarAction
import kotlinx.coroutines.launch

/** 功能清单来自日志记录，未装载过的功能不显示。 */
@Composable
fun HookLogPage(state: HookLogState, onBack: () -> Unit, onOpenFeature: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var showPrevious by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { state.refresh() }

    val records = if (showPrevious) state.previous else state.current
    val hosts = records.groupByHost()

    MeowPreferencePage(
        title = stringResource(R.string.hook_log),
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
                icon = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.log_refresh),
                modifier = Modifier.testTag("action.log.refresh"),
                onClick = { scope.launch { state.refresh() } },
            ),
            MeowTopBarAction.Icon(
                icon = Icons.Filled.DeleteSweep,
                contentDescription = stringResource(R.string.log_clear),
                modifier = Modifier.testTag("action.log.clear"),
                onClick = { scope.launch { state.clear() } },
            ),
        ),
    ) {
        if (hosts.isEmpty()) {
            SettingsCard(testTag = "section.log.empty") {
                SettingsInfoRow(
                    titleRes = R.string.log_empty,
                    value = "",
                    testTag = "row.log.empty",
                )
            }
            return@MeowPreferencePage
        }

        hosts.forEach { (host, features) ->
            MeowPreferenceSectionByTitle(title = host, testTag = "section.log.$host") {
                features.forEach { health ->
                    SettingsValueRow(
                        title = featureTitle(health.tag),
                        value = when {
                            health.hasProblem ->
                                stringResource(R.string.feature_health_failed, health.failureCount)
                            health.installed -> stringResource(R.string.feature_health_ok)
                            else -> stringResource(R.string.feature_health_unknown)
                        },
                        testTag = "row.log.feature.${health.tag}",
                        navigation = true,
                    ) { onOpenFeature(health.tag) }
                }
            }
        }
    }
}

internal fun generationRes(showPrevious: Boolean): Int =
    if (showPrevious) R.string.log_generation_previous else R.string.log_generation_current
