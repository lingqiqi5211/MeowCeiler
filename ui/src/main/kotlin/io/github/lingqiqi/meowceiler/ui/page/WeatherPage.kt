package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard
import io.github.lingqiqi.meowceiler.ui.settings.NativeWeatherBridge
import io.github.lingqiqi5211.meowui.component.*
import io.github.lingqiqi5211.meowui.core.preference.PreferenceWriteResult
import io.github.lingqiqi5211.meowui.preference.currentMeowPreferenceStore
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun WeatherPage(
    bridge: NativeWeatherBridge,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val store = currentMeowPreferenceStore()
    val pending by rememberMeowPreferenceValue(Preferences.Weather.Pending)
    val savedMaster by rememberMeowPreferenceValue(Preferences.Weather.Enabled)
    val savedBlur by rememberMeowPreferenceValue(Preferences.Weather.Blur)
    val savedEnabled = Preferences.Weather.topBarEnabled(savedMaster, savedBlur)
    var enabled by remember(savedEnabled) { mutableStateOf(savedEnabled) }
    var busy by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val moduleOff = stringResource(R.string.weather_module_off)
    val saveFailed = stringResource(R.string.preference_not_connected)

    fun operation(
        apply: Boolean,
        restart: Boolean = true,
    ) {
        if (busy) return
        busy = true
        failure = null
        scope.launch {
            try {
                val result =
                    if (apply) {
                        val active = enabled
                        check(!active || store.read(Preferences.Module.Enabled)) { moduleOff }
                        check(store.write(Preferences.Weather.Pending, true) == PreferenceWriteResult.Success) { saveFailed }
                        check(store.write(Preferences.Weather.Blur, if (active) "on" else "system") == PreferenceWriteResult.Success) { saveFailed }
                        check(store.write(Preferences.Weather.Enabled, active) == PreferenceWriteResult.Success) { saveFailed }
                        bridge.apply(active, restart).onSuccess {
                            check(store.write(Preferences.Weather.Pending, false) == PreferenceWriteResult.Success) { saveFailed }
                        }
                    } else {
                        bridge.status()
                    }
                result
                    .onSuccess {
                        status = it
                        if (apply) dirty = false
                    }.onFailure { failure = it.localizedMessage ?: saveFailed }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.localizedMessage ?: saveFailed
            } finally {
                busy = false
            }
        }
    }
    val stateLabel =
        stringResource(
            when {
                failure != null -> {
                    R.string.weather_failed
                }

                dirty || pending -> {
                    R.string.weather_pending
                }

                else -> {
                    when (status) {
                        "initialized" -> R.string.weather_initialized
                        "disabled" -> R.string.weather_disabled
                        "ready", "pending" -> R.string.weather_waiting
                        "unsupported" -> R.string.weather_unsupported
                        "failed", "offline" -> R.string.weather_failed
                        else -> R.string.weather_unknown
                    }
                }
            },
        )
    MeowPreferencePage(title = stringResource(R.string.weather_title), onBackClick = onBack) {
        SettingsCard("section.weather.main") {
            item("enabled", container = false) {
                MeowSwitchPreference(
                    checked = enabled,
                    title = stringResource(R.string.weather_enabled),
                    summary = stringResource(R.string.weather_description),
                    enabled = !busy,
                    onCheckedChange = {
                        enabled = it
                        dirty = it != savedEnabled
                    },
                )
            }
        }
        SettingsCard("section.weather.apply") {
            item("apply", container = false) {
                MeowActionPreference(
                    title = stringResource(R.string.weather_apply),
                    summary = stringResource(R.string.weather_apply_summary),
                    enabled = !busy,
                    onClick = {
                        operation(true)
                    },
                )
            }
            item("save", container = false) {
                MeowActionPreference(
                    title = stringResource(R.string.weather_save),
                    summary = stringResource(R.string.weather_save_summary),
                    enabled = !busy,
                    onClick = {
                        operation(true, false)
                    },
                )
            }
            item("refresh", container = false) {
                MeowActionPreference(title = stringResource(R.string.weather_refresh), enabled = !busy, onClick = { operation(false) })
            }
            item("status", container = false) {
                MeowInfoPreference(
                    title = stringResource(R.string.weather_status),
                    value = if (busy) stringResource(R.string.weather_working) else stateLabel,
                    summary = failure ?: stringResource(R.string.weather_requirements),
                )
            }
        }
    }
}
