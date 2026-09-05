package io.github.lingqiqi.meowceiler.ui.page

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.component.SettingsSection
import io.github.lingqiqi.meowceiler.ui.host.HostEntry
import io.github.lingqiqi.meowceiler.ui.settings.FrameworkBridge
import io.github.lingqiqi.meowceiler.ui.settings.ScopeState
import io.github.lingqiqi5211.meowui.component.MeowAlertDialog
import io.github.lingqiqi5211.meowui.component.MeowPreferencePage
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import kotlinx.coroutines.launch

/**
 * 作用域。列的是模块支持的宿主而不是全部已安装应用；另列一段「在作用域里但模块用不到的」方便清理。
 * 开了「作用域同步」时，这里的勾选决定首页显示哪些宿主。
 */
@Composable
fun ScopePage(
    bridge: FrameworkBridge,
    scopeState: ScopeState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val syncEnabled by rememberMeowPreferenceValue(Preferences.Framework.ScopeSync)

    var error by remember { mutableStateOf<String?>(null) }
    val frameworkFailed = stringResource(R.string.settings_framework_failed)

    val inScope = scopeState.packages.orEmpty()
    val supported = remember { HostEntry.entries.map { it.packageName } }
    val extras = inScope.filterNot { it in supported }.sorted()

    fun toggle(packageName: String, enabled: Boolean) = scope.launch {
        bridge.setInScope(packageName, enabled)
            .onFailure { error = frameworkFailed.format(it.message.orEmpty()) }
        scopeState.reload()
    }

    MeowPreferencePage(
        title = stringResource(R.string.settings_scope),
        onBackClick = onBack,
    ) {
        SettingsSection(
            titleRes = R.string.settings_scope_supported,
            testTag = "section.scope.supported",
        ) {
            supported.forEach { packageName ->
                MeowSwitchPreference(
                    title = context.appLabel(packageName),
                    checked = packageName in inScope,
                    onCheckedChange = { toggle(packageName, it) },
                    modifier = Modifier.testTag("row.scope.$packageName"),
                    summary = if (syncEnabled) {
                        stringResource(R.string.settings_scope_drives_home)
                    } else {
                        packageName
                    },
                )
            }
        }

        if (extras.isNotEmpty()) {
            SettingsSection(
                titleRes = R.string.settings_scope_extra,
                testTag = "section.scope.extra",
            ) {
                extras.forEach { packageName ->
                    MeowSwitchPreference(
                        title = context.appLabel(packageName),
                        checked = true,
                        onCheckedChange = { toggle(packageName, it) },
                        modifier = Modifier.testTag("row.scope.$packageName"),
                        summary = packageName,
                    )
                }
            }
        }
    }

    MeowAlertDialog(
        show = error != null,
        title = stringResource(R.string.settings_scope),
        message = error.orEmpty(),
        onConfirm = { error = null },
        onDismissRequest = { error = null },
        cancelText = null,
    )
}

/** 拿不到应用名就退回包名 —— 宿主可能是当前用户装不到的系统组件。 */
private fun Context.appLabel(packageName: String): String = runCatching {
    packageManager.getApplicationLabel(
        packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)),
    ).toString()
}.getOrDefault(packageName)
