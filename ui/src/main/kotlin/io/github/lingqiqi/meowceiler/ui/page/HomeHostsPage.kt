package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard
import io.github.lingqiqi.meowceiler.ui.host.rememberInstalledHosts
import io.github.lingqiqi5211.meowui.component.MeowPreferencePage
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceWriter

@Composable
fun HomeHostsPage(onBack: () -> Unit) {
    val hosts = rememberInstalledHosts()
    val hidden by rememberMeowPreferenceValue(Preferences.Home.HiddenHosts)
    val write = rememberMeowPreferenceWriter(Preferences.Home.HiddenHosts)

    MeowPreferencePage(
        title = stringResource(R.string.settings_home_hosts),
        onBackClick = onBack,
    ) {
        SettingsCard(testTag = "section.home.hosts") {
            hosts.forEach { host ->
                val packageName = host.entry.packageName
                // 直接用分区作用域的成员：包一层 item {} 的话里面这句会解析成同名成员，
                // 二次 item() 在渲染期什么都不画，整页空白。
                MeowSwitchPreference(
                    title = host.label,
                    checked = packageName !in hidden,
                    onCheckedChange = { visible ->
                        write(if (visible) hidden - packageName else hidden + packageName)
                    },
                    modifier = Modifier.testTag("row.home.host.$packageName"),
                    summary = packageName,
                    leading = { HostIcon(packageName) },
                )
            }
        }
    }
}
