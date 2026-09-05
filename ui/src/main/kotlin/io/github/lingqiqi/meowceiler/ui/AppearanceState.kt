package io.github.lingqiqi.meowceiler.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.lingqiqi.meowceiler.shared.Preferences.Appearance as Keys
import io.github.lingqiqi5211.meowui.core.MeowUiStyle
import io.github.lingqiqi5211.meowui.preference.currentMeowPreferenceStore
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import io.github.lingqiqi5211.meowui.theme.MeowAppearance
import io.github.lingqiqi5211.meowui.theme.MeowColorSpec
import io.github.lingqiqi5211.meowui.theme.MeowPaletteStyle
import io.github.lingqiqi5211.meowui.theme.MeowThemeMode
import kotlinx.coroutines.launch

/** 外观状态 + 写回。整个 App 共用一份，主题与外观设置页读的是同一个来源。 */
class AppearanceController(
    val appearance: MeowAppearance,
    val onChange: (MeowAppearance) -> Unit,
)

@Composable
fun rememberAppearanceController(): AppearanceController {
    val store = currentMeowPreferenceStore()
    val scope = rememberCoroutineScope()

    val style by rememberMeowPreferenceValue(Keys.Style)
    val themeMode by rememberMeowPreferenceValue(Keys.ThemeMode)
    val dynamicColor by rememberMeowPreferenceValue(Keys.DynamicColor)
    val seedColor by rememberMeowPreferenceValue(Keys.SeedColor)
    val paletteStyle by rememberMeowPreferenceValue(Keys.PaletteStyle)
    val miuixMonet by rememberMeowPreferenceValue(Keys.MiuixMonet)
    val amoledDark by rememberMeowPreferenceValue(Keys.AmoledDark)
    val blur by rememberMeowPreferenceValue(Keys.Blur)
    val predictiveBack by rememberMeowPreferenceValue(Keys.PredictiveBack)
    val interfaceScale by rememberMeowPreferenceValue(Keys.InterfaceScale)

    val current = MeowAppearance(
        style = style.toUiStyle(),
        themeMode = themeMode.toThemeMode(),
        dynamicColor = dynamicColor,
        seedColor = Color(seedColor.toInt()),
        paletteStyle = paletteStyle.toPaletteStyle(),
        colorSpec = MeowColorSpec.Spec2025,
        miuixMonetEnabled = miuixMonet,
        amoledDarkEnabled = amoledDark,
        blurEnabled = blur,
        predictiveBackEnabled = predictiveBack,
        interfaceScale = interfaceScale,
    )

    return AppearanceController(current) { next ->
        scope.launch {
            if (next.style != current.style) store.write(Keys.Style, next.style.key)
            if (next.themeMode != current.themeMode) store.write(Keys.ThemeMode, next.themeMode.key)
            if (next.dynamicColor != current.dynamicColor) store.write(Keys.DynamicColor, next.dynamicColor)
            if (next.seedColor != current.seedColor) store.write(Keys.SeedColor, next.seedColor.toArgb().toLong())
            if (next.paletteStyle != current.paletteStyle) store.write(Keys.PaletteStyle, next.paletteStyle.name)
            if (next.miuixMonetEnabled != current.miuixMonetEnabled) store.write(Keys.MiuixMonet, next.miuixMonetEnabled)
            if (next.amoledDarkEnabled != current.amoledDarkEnabled) store.write(Keys.AmoledDark, next.amoledDarkEnabled)
            if (next.blurEnabled != current.blurEnabled) store.write(Keys.Blur, next.blurEnabled)
            if (next.predictiveBackEnabled != current.predictiveBackEnabled) store.write(Keys.PredictiveBack, next.predictiveBackEnabled)
            if (next.interfaceScale != current.interfaceScale) store.write(Keys.InterfaceScale, next.interfaceScale)
        }
    }
}

private fun String.toUiStyle() =
    if (this == "miuix") MeowUiStyle.Miuix else MeowUiStyle.MaterialExpressive

private val MeowUiStyle.key get() = if (this == MeowUiStyle.Miuix) "miuix" else "material"

private fun String.toThemeMode() = when (this) {
    "light" -> MeowThemeMode.Light
    "dark" -> MeowThemeMode.Dark
    else -> MeowThemeMode.System
}

private val MeowThemeMode.key
    get() = when (this) {
        MeowThemeMode.Light -> "light"
        MeowThemeMode.Dark -> "dark"
        MeowThemeMode.System -> "system"
    }

private fun String.toPaletteStyle() =
    MeowPaletteStyle.entries.firstOrNull { it.name == this } ?: MeowPaletteStyle.TonalSpot
