package io.github.lingqiqi.meowceiler.ui.page

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lingqiqi.meowceiler.shared.FocusAppRegistry
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.component.SettingsSection
import io.github.lingqiqi.meowceiler.ui.host.appIconSizePx
import io.github.lingqiqi.meowceiler.ui.host.rememberAppIcon
import io.github.lingqiqi5211.meowui.component.MeowSwitchPreference
import io.github.lingqiqi5211.meowui.component.MeowTip
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey
import io.github.lingqiqi5211.meowui.core.preference.PreferenceWriteResult
import io.github.lingqiqi5211.meowui.preference.currentMeowPreferenceStore
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Collator

/** HyperOS 3 的媒体岛应用列表；HyperOS 4 由系统页面管理通知应用。 */
@Composable
fun SystemUiFocusAppsPage(onBack: () -> Unit) {
    val mediaListed by rememberMeowPreferenceValue(Preferences.SystemUi.FocusMediaApps)
    val mediaHidden by rememberMeowPreferenceValue(Preferences.SystemUi.FocusMediaHidden)
    val writeMediaHidden = rememberFocusAppSwitchWriter(Preferences.SystemUi.FocusMediaHidden)
    val mediaRecorded = rememberRecordedApps(FocusAppRegistry.Media)
    val mediaApps = rememberFocusApps(mediaListed + mediaRecorded)

    HostPage(
        titleRes = R.string.systemui_focus_apps,
        hostPackage = Scope.SystemUi,
        onBack = onBack,
    ) {
        if (mediaApps.isEmpty()) {
            MeowTip(message = stringResource(R.string.systemui_focus_apps_empty))
            return@HostPage
        }
        SettingsSection(
            titleRes = R.string.systemui_focus_apps_media,
            testTag = "section.systemui.focus.media",
        ) {
            mediaApps.forEach { app ->
                item(key = app.packageName, container = false) {
                    FocusAppRow(
                        app = app,
                        allowed = app.packageName !in mediaHidden,
                        onChange = { allowed ->
                            writeMediaHidden(app.packageName, allowed)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberFocusAppSwitchWriter(key: PreferenceKey<Set<String>>): (String, Boolean) -> Unit {
    val store = currentMeowPreferenceStore()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mutex = remember(store, key) { Mutex() }
    return remember(store, key, scope, mutex, context) {
        { packageName, allowed ->
            scope.launch {
                mutex.withLock {
                    val current = store.read(key)
                    val next = if (allowed) current - packageName else current + packageName
                    // 框架进程无法反序列化 Kotlin EmptySet；默认值为空集，删除键与写空集等价。
                    val result = if (next.isEmpty()) store.delete(key) else store.write(key, next)
                    when (result) {
                        PreferenceWriteResult.Success -> {
                            Unit
                        }

                        is PreferenceWriteResult.NotConnected -> {
                            Toast.makeText(context, R.string.preference_not_connected, Toast.LENGTH_SHORT).show()
                        }

                        is PreferenceWriteResult.Failure -> {
                            Log.e("MeowCeiler", "cannot save focus switch", result.cause)
                            Toast.makeText(context, R.string.systemui_focus_write_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusAppRow(
    app: FocusApp,
    allowed: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val icon = rememberAppIcon(app.packageName, appIconSizePx(IconSize))
    MeowSwitchPreference(
        title = app.label,
        checked = allowed,
        onCheckedChange = onChange,
        summary = app.packageName,
        leading = icon?.let { { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(IconSize)) } },
    )
}

private data class FocusApp(
    val packageName: String,
    val label: String,
)

@Composable
private fun rememberRecordedApps(kind: String): Set<String> {
    val context = LocalContext.current.applicationContext
    val recorded by produceState(emptySet<String>(), context, kind) {
        withContext(Dispatchers.IO) {
            FocusAppRegistry.observe(context, kind).collect { value = it }
        }
    }
    return recorded
}

/** 包名来自 Hook 侧的记录，可能包含已经卸载的应用，查不到就不列。 */
@Composable
private fun rememberFocusApps(packages: Set<String>): List<FocusApp> {
    val context = LocalContext.current.applicationContext
    val state =
        produceState(initialValue = emptyList<FocusApp>(), context, packages) {
            value = withContext(Dispatchers.IO) { context.loadFocusApps(packages) }
        }
    return state.value
}

private fun Context.loadFocusApps(packages: Set<String>): List<FocusApp> {
    val manager = packageManager
    val collator = Collator.getInstance()
    return packages
        .mapNotNull { packageName ->
            runCatching {
                val info = manager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                FocusApp(packageName, info.loadLabel(manager).toString())
            }.getOrNull()
        }.sortedWith(compareBy(collator) { it.label })
}

private val IconSize = 32.dp
