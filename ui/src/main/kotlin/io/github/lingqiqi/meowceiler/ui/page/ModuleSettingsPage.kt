package io.github.lingqiqi.meowceiler.ui.page

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import io.github.lingqiqi.meowceiler.shared.SettingsEntryPosition
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.component.FeatureSwitchRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard
import io.github.lingqiqi.meowceiler.ui.component.SettingsNavigationRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsSection
import io.github.lingqiqi.meowceiler.ui.settings.FrameworkBridge
import io.github.lingqiqi.meowceiler.ui.settings.LauncherIcon
import io.github.lingqiqi.meowceiler.ui.settings.PreferencesBackup
import io.github.lingqiqi5211.meowui.component.MeowAlertDialog
import io.github.lingqiqi5211.meowui.component.MeowPreferenceScreen
import io.github.lingqiqi5211.meowui.preference.currentMeowPreferenceStore
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** optionLabel 是普通 lambda 不是 Composable，字符串得在这里先取好。 */
@Composable
fun ModuleSettingsPage(
    bridge: FrameworkBridge,
    onOpenAppearance: () -> Unit,
    onOpenScope: () -> Unit,
    onOpenSafeMode: () -> Unit,
    onOpenHookLog: () -> Unit,
) {
    val context = LocalContext.current
    val store = currentMeowPreferenceStore()
    val scope = rememberCoroutineScope()

    var iconHidden by remember { mutableStateOf(LauncherIcon.isHidden(context)) }


    val entryPositionKey by rememberMeowPreferenceValue(Preferences.SettingsEntry.Position)
    val entryPosition = SettingsEntryPosition.from(entryPositionKey)

    var confirmReset by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    val exported = stringResource(R.string.settings_backup_done)
    val importFailed = stringResource(R.string.settings_restore_failed)
    val ioFailed = stringResource(R.string.settings_file_failed)
    val importedFormat = stringResource(R.string.settings_restore_done)
    val reloadedFormat = stringResource(R.string.settings_hot_reload_done)
    val noTargets = stringResource(R.string.settings_hot_reload_none)
    val frameworkFailed = stringResource(R.string.settings_framework_failed)

    val backup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PreferencesBackup.MimeType),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = PreferencesBackup.export(store)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }.isSuccess
            }
            result = if (ok) exported else ioFailed
        }
    }

    val restore = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            val applied = text?.let { PreferencesBackup.import(store, it) }
            result = when {
                text == null -> ioFailed
                applied == null -> importFailed
                else -> importedFormat.format(applied)
            }
        }
    }

    MeowPreferenceScreen {
        SettingsCard(testTag = "section.appearance") {
            SettingsNavigationRow(
                titleRes = R.string.appearance_title,
                testTag = "row.appearance",
                onClick = onOpenAppearance,
            )
        }

        SettingsSection(titleRes = R.string.settings_display, testTag = "section.display") {
            MeowSwitchPreference(
                title = stringResource(R.string.settings_hide_launcher_icon),
                checked = iconHidden,
                onCheckedChange = {
                    iconHidden = it
                    LauncherIcon.setHidden(context, it)
                },
                modifier = Modifier.testTag("row.hide_launcher_icon"),
                summary = stringResource(R.string.settings_hide_launcher_icon_summary),
            )
            MeowPopupPreference(
                title = stringResource(R.string.settings_entry_position),
                value = entryPosition,
                options = SettingsEntryPosition.entries,
                onValueChange = { picked ->
                    scope.launch { store.write(Preferences.SettingsEntry.Position, picked.key) }
                },
                modifier = Modifier.testTag("row.entry_position"),
                summary = stringResource(R.string.settings_entry_position_summary),
                optionLabel = entryPositionLabels()::getValue,
            )
        }

        SettingsSection(titleRes = R.string.settings_module, testTag = "section.module") {
            FeatureSwitchRow(key = Preferences.Module.Enabled, titleRes = R.string.module_enabled)
            FeatureSwitchRow(key = Preferences.Module.Debug, titleRes = R.string.module_debug)
            MeowSwitchPreference(
                key = Preferences.Framework.ScopeSync,
                title = stringResource(R.string.settings_scope_sync),
                modifier = Modifier.testTag("row.scope_sync"),
                summary = stringResource(R.string.settings_scope_sync_summary),
            )
            MeowActionPreference(
                title = stringResource(R.string.settings_scope),
                modifier = Modifier.testTag("row.scope"),
                summary = stringResource(R.string.settings_scope_summary),
                navigation = true,
                onClick = onOpenScope,
            )
            MeowActionPreference(
                title = stringResource(R.string.safe_mode),
                modifier = Modifier.testTag("row.safe_mode"),
                summary = stringResource(R.string.safe_mode_summary),
                navigation = true,
                onClick = onOpenSafeMode,
            )
            MeowActionPreference(
                title = stringResource(R.string.hook_log),
                modifier = Modifier.testTag("row.hook_log"),
                summary = stringResource(R.string.hook_log_summary),
                navigation = true,
                onClick = onOpenHookLog,
            )
            MeowActionPreference(
                title = stringResource(R.string.settings_hot_reload),
                modifier = Modifier.testTag("row.hot_reload"),
                summary = stringResource(R.string.settings_hot_reload_summary),
                onClick = {
                    scope.launch {
                        result = bridge.hotReload().fold(
                            onSuccess = { count ->
                                if (count == 0) noTargets else reloadedFormat.format(count)
                            },
                            onFailure = { frameworkFailed.format(it.message.orEmpty()) },
                        )
                    }
                },
            )
        }

        SettingsSection(titleRes = R.string.settings_data, testTag = "section.data") {
            MeowActionPreference(
                title = stringResource(R.string.settings_backup),
                modifier = Modifier.testTag("row.backup"),
                summary = stringResource(R.string.settings_backup_summary),
                onClick = { backup.launch(PreferencesBackup.FileName) },
            )
            MeowActionPreference(
                title = stringResource(R.string.settings_restore),
                modifier = Modifier.testTag("row.restore"),
                summary = stringResource(R.string.settings_restore_summary),
                onClick = { restore.launch(arrayOf("*/*")) },
            )
            MeowActionPreference(
                title = stringResource(R.string.settings_reset),
                modifier = Modifier.testTag("row.reset"),
                summary = stringResource(R.string.settings_reset_summary),
                onClick = { confirmReset = true },
            )
        }
    }

    MeowAlertDialog(
        show = confirmReset,
        title = stringResource(R.string.settings_reset),
        message = stringResource(R.string.settings_reset_confirm),
        onConfirm = {
            confirmReset = false
            scope.launch { store.clear() }
        },
        onDismissRequest = { confirmReset = false },
        cancelText = stringResource(R.string.dialog_cancel),
    )

    MeowAlertDialog(
        show = result != null,
        title = stringResource(R.string.tab_settings),
        message = result.orEmpty(),
        onConfirm = { result = null },
        onDismissRequest = { result = null },
        cancelText = null,
    )
}


@Composable
private fun entryPositionLabels(): Map<SettingsEntryPosition, String> = mapOf(
    SettingsEntryPosition.Off to stringResource(R.string.settings_entry_off),
    SettingsEntryPosition.Device to stringResource(R.string.settings_entry_device),
    SettingsEntryPosition.Launcher to stringResource(R.string.settings_entry_launcher),
    SettingsEntryPosition.More to stringResource(R.string.settings_entry_more),
)
