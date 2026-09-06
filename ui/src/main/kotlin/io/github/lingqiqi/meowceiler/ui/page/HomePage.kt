package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.shared.SafeModeRecord
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.Route
import io.github.lingqiqi.meowceiler.ui.host.InstalledHost
import io.github.lingqiqi.meowceiler.ui.host.appIconSizePx
import io.github.lingqiqi.meowceiler.ui.host.rememberAppIcon
import io.github.lingqiqi.meowceiler.ui.host.rememberInstalledHosts
import io.github.lingqiqi5211.meowui.component.MeowActionPreference
import io.github.lingqiqi5211.meowui.component.MeowPreferenceScreen
import io.github.lingqiqi5211.meowui.component.MeowPreferenceSection
import io.github.lingqiqi5211.meowui.component.MeowTip
import io.github.lingqiqi5211.meowui.component.MeowTipStyle
import io.github.lingqiqi5211.meowui.core.preference.PreferenceConnectionState
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceConnectionState
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import io.github.lingqiqi5211.meowui.theme.MeowTheme

/**
 * 首页：宿主应用平铺列表，图标与名称取自实际安装的应用。scaffold 归外壳所有。
 *
 * [scopeFilter] 非空时只显示在作用域里的宿主（「作用域同步」开着时如此）；为 null 表示不过滤。
 * 注意作用域读取失败时上游传的是 null 而不是空集合 —— 服务抽一下不该把用户的宿主列表整个清空。
 * [hiddenHosts] 是用户在「首页显示」里关掉的宿主，只在作用域同步关着时才有意义。
 */
@Composable
fun HomePage(
    scopeFilter: Set<String>?,
    hiddenHosts: Set<String>,
    onOpenSafeMode: () -> Unit,
    onOpenHost: (Route) -> Unit,
) {
    val hosts = rememberInstalledHosts()
    val visible: (InstalledHost) -> Boolean = { host ->
        val packageName = host.entry.packageName
        (scopeFilter == null || packageName in scopeFilter) && packageName !in hiddenHosts
    }

    MeowPreferenceScreen {
        StatusTip(onOpenSafeMode)
        // 每个宿主都声明，藏起来的走 `item(visible = false)`：
        // 直接不声明的话分区收不到「这一趟少了谁」，改完开关首页要等下次重建才更新。
        MeowPreferenceSection(modifier = Modifier.testTag("section.hosts")) {
            hosts.forEach { host ->
                item(key = host.entry.packageName, visible = visible(host), container = false) {
                    HostRow(host, onOpenHost)
                }
            }
        }
        if (hosts.none(visible)) EmptyHosts()
    }
}

/** 模块整体状态。没问题时整条不画。 */
@Composable
private fun StatusTip(onOpenSafeMode: () -> Unit) {
    val connection by rememberMeowPreferenceConnectionState()
    val moduleEnabled by rememberMeowPreferenceValue(Preferences.Module.Enabled)
    val safeModeRaw by rememberMeowPreferenceValue(Preferences.SafeMode.Records)
    val blocked = remember(safeModeRaw) {
        safeModeRaw.mapNotNull(SafeModeRecord::decode).count { it.blocked }
    }

    when {
        connection is PreferenceConnectionState.Disconnected -> MeowTip(
            message = stringResource(R.string.home_status_framework_summary),
            modifier = Modifier.fillMaxWidth().testTag("tip.home.framework"),
            title = stringResource(R.string.home_status_framework),
            style = MeowTipStyle.Error,
        )

        !moduleEnabled -> MeowTip(
            message = stringResource(R.string.home_status_module_off_summary),
            modifier = Modifier.fillMaxWidth().testTag("tip.home.module_off"),
            title = stringResource(R.string.home_status_module_off),
            style = MeowTipStyle.Warning,
        )

        blocked > 0 -> MeowTip(
            message = stringResource(R.string.home_status_safe_mode_summary, blocked),
            modifier = Modifier.fillMaxWidth().testTag("tip.home.safe_mode"),
            title = stringResource(R.string.home_status_safe_mode),
            style = MeowTipStyle.Warning,
            actionText = stringResource(R.string.home_status_open),
            onAction = onOpenSafeMode,
        )

        else -> Unit
    }
}

/** 一个宿主都不显示时的空页。 */
@Composable
private fun EmptyHosts() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp)
            .testTag("section.hosts.empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            text = stringResource(R.string.home_empty),
            style = MeowTheme.typography.title.copy(
                color = MeowTheme.colors.onSurface,
                textAlign = TextAlign.Center,
            ),
        )
        BasicText(
            text = stringResource(R.string.home_empty_summary),
            style = MeowTheme.typography.summary.copy(
                color = MeowTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

@Composable
internal fun HostIcon(packageName: String) {
    val icon = rememberAppIcon(packageName, appIconSizePx(HostIconSize))
    Box(modifier = Modifier.size(HostIconSize)) {
        icon?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(HostIconSize))
        }
    }
}

internal val HostIconSize = 32.dp

@Composable
private fun HostRow(host: InstalledHost, onOpenHost: (Route) -> Unit) {
    MeowActionPreference(
        title = host.label,
        modifier = Modifier.testTag("row.host.${host.entry.packageName}"),
        navigation = true,
        leading = { HostIcon(host.entry.packageName) },
        onClick = { onOpenHost(host.entry.route) },
    )
}
