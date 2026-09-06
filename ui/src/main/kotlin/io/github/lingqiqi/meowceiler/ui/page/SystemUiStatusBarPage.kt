package io.github.lingqiqi.meowceiler.ui.page

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi.meowceiler.ui.ClockPart
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.Route
import io.github.lingqiqi.meowceiler.ui.component.FeatureSliderRow
import io.github.lingqiqi.meowceiler.ui.component.FeatureSwitchRow
import io.github.lingqiqi.meowceiler.ui.component.FeatureTextRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard
import io.github.lingqiqi.meowceiler.ui.component.SettingsNavigationRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsSection
import io.github.lingqiqi5211.meowui.component.MeowPreferenceSectionScope
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey
import io.github.lingqiqi5211.meowui.preference.currentMeowPreferenceStore
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import kotlinx.coroutines.launch

private const val FormatHelpUrl = "https://zhuti.designer.xiaomi.com/docs/grammar/#时间日期"

@Composable
fun SystemUiStatusBarPage(onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val enabled by rememberMeowPreferenceValue(Preferences.SystemUi.Clock)
    val noSync by rememberMeowPreferenceValue(Preferences.SystemUi.ClockNoSync)
    val context = LocalContext.current
    HostPage(
        titleRes = R.string.systemui_section_statusbar,
        hostPackage = Scope.SystemUi,
        onBack = onBack,
    ) {
        SettingsCard(testTag = "section.systemui.clock") {
            FeatureSwitchRow(key = Preferences.SystemUi.Clock, titleRes = R.string.systemui_clock)
            FeatureSwitchRow(
                key = Preferences.SystemUi.ClockNoSync,
                titleRes = R.string.systemui_clock_no_sync,
                summaryRes = R.string.systemui_clock_no_sync_summary,
                visible = enabled,
            )
            FeatureSwitchRow(
                key = Preferences.SystemUi.ClockNoShadeAnimation,
                titleRes = R.string.systemui_clock_no_shade_animation,
                summaryRes = R.string.systemui_clock_no_shade_animation_summary,
                visible = enabled,
            )
        }
        if (!enabled) return@HostPage

        SettingsSection(titleRes = R.string.systemui_clock_section_display, testTag = "section.systemui.clock.display") {
            FeatureTextRow(Preferences.SystemUi.ClockFormatStatusBar, R.string.systemui_clock_format_statusbar)
            FeatureTextRow(Preferences.SystemUi.ClockFormatBig, R.string.systemui_clock_format_big, visible = noSync)
            FeatureTextRow(Preferences.SystemUi.ClockFormatMini, R.string.systemui_clock_format_mini, allowBlank = true)
            SettingsNavigationRow(titleRes = R.string.systemui_clock_format_help, testTag = "row.systemui.clock.help") {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FormatHelpUrl)))
            }
            IntChoiceRow(
                key = Preferences.SystemUi.ClockStyle,
                titleRes = R.string.systemui_clock_style,
                labels = mapOf(
                    0 to R.string.systemui_clock_style_single,
                    1 to R.string.systemui_clock_style_time_first,
                    2 to R.string.systemui_clock_style_date_first,
                ),
            )
        }

        SettingsSection(titleRes = R.string.systemui_clock_section_layout, testTag = "section.systemui.clock.layout") {
            SettingsNavigationRow(titleRes = R.string.systemui_clock_section_statusbar, testTag = "row.systemui.clock.layout.statusbar") {
                onOpen(Route.SystemUiClockLayout(ClockPart.StatusBar))
            }
            SettingsNavigationRow(titleRes = R.string.systemui_clock_section_big, testTag = "row.systemui.clock.layout.big") {
                onOpen(Route.SystemUiClockLayout(ClockPart.Big))
            }
            SettingsNavigationRow(titleRes = R.string.systemui_clock_section_mini, testTag = "row.systemui.clock.layout.mini") {
                onOpen(Route.SystemUiClockLayout(ClockPart.Mini))
            }
        }
    }
}

@Composable
fun SystemUiClockLayoutPage(part: ClockPart, onBack: () -> Unit) {
    val p = Preferences.SystemUi
    val titleRes = when (part) {
        ClockPart.StatusBar -> R.string.systemui_clock_section_statusbar
        ClockPart.Big -> R.string.systemui_clock_section_big
        ClockPart.Mini -> R.string.systemui_clock_section_mini
    }
    HostPage(titleRes = titleRes, hostPackage = Scope.SystemUi, onBack = onBack) {
        SettingsCard(testTag = "section.systemui.clock.layout.${part.name}") {
            when (part) {
                ClockPart.StatusBar -> {
                    FeatureSwitchRow(key = p.ClockBoldStatusBar, titleRes = R.string.systemui_clock_bold)
                    FeatureSliderRow(p.ClockSizeStatusBar, R.string.systemui_clock_size, 0f..40f, 1f, "sp")
                    IntChoiceRow(
                        key = p.ClockAlign,
                        titleRes = R.string.systemui_clock_align,
                        labels = mapOf(
                            0 to R.string.systemui_clock_align_start,
                            1 to R.string.systemui_clock_align_center,
                            2 to R.string.systemui_clock_align_end,
                        ),
                    )
                }
                ClockPart.Big -> { FeatureSwitchRow(key = p.ClockBoldBig, titleRes = R.string.systemui_clock_bold); FeatureSliderRow(p.ClockSizeBig, R.string.systemui_clock_size, 0f..120f, 1f, "sp") }
                ClockPart.Mini -> { FeatureSwitchRow(key = p.ClockBoldMini, titleRes = R.string.systemui_clock_bold); FeatureSliderRow(p.ClockSizeMini, R.string.systemui_clock_size, 0f..72f, 1f, "sp") }
            }
        }
        SettingsCard(testTag = "section.systemui.clock.layout.${part.name}.sliders") {
            when (part) {
                ClockPart.StatusBar -> {
                    FeatureSliderRow(p.ClockSpacing, R.string.systemui_clock_spacing, 0.7f..1.6f, step = 0.05f)
                    FeatureSliderRow(p.ClockLeftStatusBar, R.string.systemui_clock_left, 0f..40f, step = 0.5f, unit = "dp")
                    FeatureSliderRow(p.ClockRightStatusBar, R.string.systemui_clock_right, 0f..40f, step = 0.5f, unit = "dp")
                    FeatureSliderRow(p.ClockOffsetStatusBar, R.string.systemui_clock_offset, 0f..24f, step = 0.5f, unit = "dp")
                    FeatureSliderRow(p.ClockFixedWidth, R.string.systemui_clock_fixed_width, 30f..120f, step = 1f, unit = "dp")
                }
                ClockPart.Big -> {
                    FeatureSliderRow(p.ClockLeftBig, R.string.systemui_clock_left, 0f..60f, step = 0.5f, unit = "dp")
                    FeatureSliderRow(p.ClockRightBig, R.string.systemui_clock_right, 0f..60f, step = 0.5f, unit = "dp")
                    FeatureSliderRow(p.ClockOffsetBig, R.string.systemui_clock_offset, 0f..24f, step = 0.5f, unit = "dp")
                }
                ClockPart.Mini -> {
                    FeatureSliderRow(p.ClockLeftMini, R.string.systemui_clock_left, 0f..72f, step = 0.5f, unit = "dp")
                    FeatureSliderRow(p.ClockRightMini, R.string.systemui_clock_right, 0f..72f, step = 0.5f, unit = "dp")
                    FeatureSliderRow(p.ClockOffsetMini, R.string.systemui_clock_offset, 0f..24f, step = 0.5f, unit = "dp")
                }
            }
        }
    }
}

@Composable
private fun MeowPreferenceSectionScope.IntChoiceRow(key: PreferenceKey<Int>, titleRes: Int, labels: Map<Int, Int>) {
    val value by rememberMeowPreferenceValue(key)
    val store = currentMeowPreferenceStore()
    val scope = rememberCoroutineScope()
    val names = labels.mapValues { stringResource(it.value) }
    MeowPopupPreference(
        title = stringResource(titleRes),
        value = value,
        options = labels.keys.toList(),
        onValueChange = { picked -> scope.launch { store.write(key, picked) } },
        modifier = Modifier.testTag("row.${key.name}"),
        optionLabel = names::getValue,
    )
}
