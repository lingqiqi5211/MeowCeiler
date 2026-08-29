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

/**
 * hook 日志总览：哪些功能装上了、哪些出了问题。
 *
 * **清单从记录本身来**，不另立功能注册表 —— 报告过的功能才出现。这也意味着「某个功能不在列表里」
 * 本身是有信息量的：它在这一代里从没被装载过（开关关着，或宿主还没起来）。
 *
 * 顶上切「本次运行 / 上次重启前」。宿主崩掉导致重启时，要看的恰恰是崩之前那一代。
 */
@Composable
fun HookLogPage(state: HookLogState, onBack: () -> Unit, onOpenFeature: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var showPrevious by remember { mutableStateOf(false) }

    // 每次进这一页都重读：宿主是在别的进程里写的，页面留在后台时它一直在变。
    LaunchedEffect(Unit) { state.refresh() }

    val records = if (showPrevious) state.previous else state.current
    val hosts = records.groupByHost()

    MeowPreferencePage(
        title = stringResource(R.string.hook_log),
        // 代次放副标题：它是「当前在看什么」，本来就该一直可见，不值得单占一行。
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

        // 分组标题直接用宿主包名。不查 PackageManager 取应用名 —— 记录里可能有已经卸载或当前
        // 不可见的宿主，取不到名字反而要多处理一种空值。
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
