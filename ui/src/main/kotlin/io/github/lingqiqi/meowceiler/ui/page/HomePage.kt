package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.lingqiqi.meowceiler.ui.Route
import io.github.lingqiqi.meowceiler.ui.host.InstalledHost
import io.github.lingqiqi.meowceiler.ui.host.rememberInstalledHosts
import io.github.lingqiqi5211.meowui.component.MeowActionPreference
import io.github.lingqiqi5211.meowui.component.MeowPreferenceScreen
import io.github.lingqiqi5211.meowui.component.MeowPreferenceSection

/**
 * 首页：宿主应用平铺列表，图标与名称取自实际安装的应用。scaffold 归外壳所有。
 *
 * [scopeFilter] 非空时只显示在作用域里的宿主（「作用域同步」开着时如此）；为 null 表示不过滤。
 * 注意作用域读取失败时上游传的是 null 而不是空集合 —— 服务抽一下不该把用户的宿主列表整个清空。
 */
@Composable
fun HomePage(scopeFilter: Set<String>?, onOpenHost: (Route) -> Unit) {
    val hosts = rememberInstalledHosts().let { all ->
        if (scopeFilter == null) all else all.filter { it.entry.packageName in scopeFilter }
    }

    MeowPreferenceScreen {
        MeowPreferenceSection(modifier = Modifier.testTag("section.hosts")) {
            hosts.forEach { host ->
                item(key = host.entry.packageName, container = false) {
                    HostRow(host, onOpenHost)
                }
            }
        }
    }
}

@Composable
private fun HostRow(host: InstalledHost, onOpenHost: (Route) -> Unit) {
    MeowActionPreference(
        title = host.label,
        modifier = Modifier.testTag("row.host.${host.entry.packageName}"),
        navigation = true,
        leading = {
            Image(
                bitmap = host.icon.toBitmap(96, 96).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
        },
        onClick = { onOpenHost(host.entry.route) },
    )
}
