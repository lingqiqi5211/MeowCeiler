package io.github.lingqiqi.meowceiler.ui.page

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.shared.SafeModeRecord
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard
import io.github.lingqiqi.meowceiler.ui.component.SettingsSection
import io.github.lingqiqi5211.meowui.component.MeowPreferencePage
import io.github.lingqiqi5211.meowui.preference.currentMeowPreferenceStore
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import kotlinx.coroutines.launch

/**
 * 安全模式。只读写模块自己的偏好（[Preferences.SafeMode]），它是真相来源。解锁前门闸是 persist. 属性，
 * 只有 hook 侧写得动，所以「重试」只改偏好，门闸在宿主下次装载时被反向对齐。
 */
@Composable
fun SafeModePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = currentMeowPreferenceStore()
    val scope = rememberCoroutineScope()

    val raw by rememberMeowPreferenceValue(Preferences.SafeMode.Records)
    val records = raw.mapNotNull(SafeModeRecord::decode).sortedBy { it.hostPackage }
    val blocked = records.filter { it.blocked }

    fun clear(hostPackage: String) = scope.launch {
        store.write(
            Preferences.SafeMode.Records,
            raw.filterNot { SafeModeRecord.decode(it)?.hostPackage == hostPackage }.toSet(),
        )
    }

    MeowPreferencePage(
        title = stringResource(R.string.safe_mode),
        onBackClick = onBack,
    ) {
        SettingsCard(testTag = "section.safe_mode.switch") {
            MeowSwitchPreference(
                key = Preferences.SafeMode.Enabled,
                title = stringResource(R.string.safe_mode_enabled),
                modifier = Modifier.testTag("row.safe_mode_enabled"),
                summary = stringResource(R.string.safe_mode_enabled_summary),
            )
        }

        SettingsSection(
            titleRes = R.string.safe_mode_blocked,
            testTag = "section.safe_mode.blocked",
        ) {
            if (blocked.isEmpty()) {
                MeowActionPreference(
                    title = stringResource(R.string.safe_mode_blocked_empty),
                    enabled = false,
                    onClick = {},
                )
            }
            if (blocked.isNotEmpty()) {
                MeowActionPreference(
                    title = stringResource(R.string.safe_mode_retry_note),
                    enabled = false,
                    onClick = {},
                )
            }
            blocked.forEach { record ->
                MeowActionPreference(
                    title = context.appLabel(record.hostPackage),
                    modifier = Modifier.testTag("row.safe_mode.${record.hostPackage}"),
                    summary = record.message,
                    value = stringResource(R.string.safe_mode_retry),
                    onClick = { clear(record.hostPackage) },
                )
            }
        }

        val warned = records.filterNot { it.blocked }
        if (warned.isNotEmpty()) {
            SettingsSection(
                titleRes = R.string.safe_mode_warned,
                testTag = "section.safe_mode.warned",
            ) {
                warned.forEach { record ->
                    MeowActionPreference(
                        title = context.appLabel(record.hostPackage),
                        modifier = Modifier.testTag("row.safe_mode.warn.${record.hostPackage}"),
                        summary = record.message,
                        value = "${record.failures}/${SafeModeRecord.FailureThreshold}",
                        onClick = { clear(record.hostPackage) },
                    )
                }
            }
        }
    }
}

private fun Context.appLabel(packageName: String): String = runCatching {
    packageManager.getApplicationLabel(
        packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)),
    ).toString()
}.getOrDefault(packageName)
