package io.github.lingqiqi.meowceiler.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.ui.settings.FrameworkBridge
import io.github.lingqiqi.meowceiler.ui.settings.NoFrameworkBridge
import io.github.lingqiqi.meowceiler.ui.settings.rememberHookLogState
import io.github.lingqiqi.meowceiler.ui.settings.rememberScopeState
import io.github.lingqiqi.meowceiler.ui.component.AppLanguageRow
import io.github.lingqiqi.meowceiler.ui.page.AboutPage
import io.github.lingqiqi.meowceiler.ui.page.FeatureLogPage
import io.github.lingqiqi.meowceiler.ui.page.HomePage
import io.github.lingqiqi.meowceiler.ui.page.HomeHostsPage
import io.github.lingqiqi.meowceiler.ui.page.HookLogPage
import io.github.lingqiqi.meowceiler.ui.page.LicensesPage
import io.github.lingqiqi.meowceiler.ui.page.LogRecordPage
import io.github.lingqiqi.meowceiler.ui.page.ModuleSettingsPage
import io.github.lingqiqi.meowceiler.ui.page.SafeModePage
import io.github.lingqiqi.meowceiler.ui.page.ScopePage
import io.github.lingqiqi.meowceiler.ui.page.SystemUiLockScreenPage
import io.github.lingqiqi.meowceiler.ui.page.SystemUiPage
import io.github.lingqiqi.meowceiler.ui.page.SystemUiClockLayoutPage
import io.github.lingqiqi.meowceiler.ui.page.SystemUiClockPage
import io.github.lingqiqi.meowceiler.ui.page.SystemUiIconsPage
import io.github.lingqiqi.meowceiler.ui.page.SystemUiStatusBarPage
import io.github.lingqiqi5211.meowui.component.MeowAppearancePage
import io.github.lingqiqi5211.meowui.component.MeowAppearanceLabels
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import androidx.compose.runtime.getValue
import io.github.lingqiqi5211.meowui.theme.MeowTheme

@Composable
fun MeowCeilerApp(bridge: FrameworkBridge = NoFrameworkBridge) {
    val appearance = rememberAppearanceController()
    val scopeSync by rememberMeowPreferenceValue(io.github.lingqiqi.meowceiler.shared.Preferences.Framework.ScopeSync)
    val hiddenHosts by rememberMeowPreferenceValue(io.github.lingqiqi.meowceiler.shared.Preferences.Home.HiddenHosts)
    val scopeState = rememberScopeState(bridge)
    val hookLog = rememberHookLogState()

    MeowTheme(appearance = appearance.appearance) {
        val backStack = remember { mutableStateListOf<Route>(Route.Shell) }
        val nav = remember(backStack) { AppNavigation(backStack) }

        AdaptiveAppNavHost(
            backStack = backStack,
            onBack = nav::pop,
            predictiveBackEnabled = appearance.appearance.predictiveBackEnabled,
            floatingNavigation = appearance.appearance.floatingNavigationBarEnabled,
        ) { route ->
            when (route) {
                Route.Shell -> ShellPage(
                    floatingNavigation = appearance.appearance.floatingNavigationBarEnabled,
                    onTabChanged = nav::resetDetail,
                    home = {
                        HomePage(
                            scopeFilter = scopeState.packages?.takeIf { scopeSync },
                            hiddenHosts = if (scopeSync) emptySet() else hiddenHosts,
                            onOpenSafeMode = { nav.openRoot(Route.SafeMode) },
                            onOpenHost = nav::openRoot,
                        )
                    },
                    settings = {
                        ModuleSettingsPage(
                            bridge = bridge,
                            onOpenAppearance = { nav.openRoot(Route.Appearance) },
                            onOpenScope = { nav.openRoot(Route.Scope) },
                            onOpenHomeHosts = { nav.openRoot(Route.HomeHosts) },
                            onOpenSafeMode = { nav.openRoot(Route.SafeMode) },
                            onOpenHookLog = { nav.openRoot(Route.HookLog) },
                        )
                    },
                    about = { AboutPage(onOpenLicenses = { nav.openRoot(Route.Licenses) }) },
                )
                Route.Appearance -> MeowAppearancePage(
                    appearance = appearance.appearance,
                    onAppearanceChange = appearance.onChange,
                    onBackClick = nav::pop,
                    labels = appearanceLabels(),
                    interfaceItems = { AppLanguageRow() },
                )
                Route.Scope -> ScopePage(bridge = bridge, scopeState = scopeState, onBack = nav::pop)
                Route.HomeHosts -> HomeHostsPage(onBack = nav::pop)
                Route.Licenses -> LicensesPage(onBack = nav::pop)
                Route.SafeMode -> SafeModePage(onBack = nav::pop)
                Route.SystemUi -> SystemUiPage(onBack = nav::pop, onOpenCategory = nav::push)
                Route.SystemUiLockScreen -> SystemUiLockScreenPage(onBack = nav::pop)
                Route.SystemUiStatusBar -> SystemUiStatusBarPage(onBack = nav::pop, onOpen = nav::push)
                Route.SystemUiClock -> SystemUiClockPage(onBack = nav::pop, onOpen = nav::push)
                Route.SystemUiIcons -> SystemUiIconsPage(onBack = nav::pop)
                is Route.SystemUiClockLayout -> SystemUiClockLayoutPage(part = route.part, onBack = nav::pop)
                Route.HookLog -> HookLogPage(
                    state = hookLog,
                    onBack = nav::pop,
                    onOpenFeature = { nav.push(Route.FeatureLog(it)) },
                )
                is Route.FeatureLog -> FeatureLogPage(
                    tag = route.tag,
                    state = hookLog,
                    onBack = nav::pop,
                    onOpenRecord = { nav.push(Route.LogRecord(it)) },
                )
                is Route.LogRecord -> LogRecordPage(record = route.record, onBack = nav::pop)
            }
        }
    }
}

@Composable
private fun appearanceLabels() = MeowAppearanceLabels(
    title = stringResource(R.string.appearance_title),
    themeColor = stringResource(R.string.appearance_theme_color),
    themeMode = stringResource(R.string.appearance_theme_mode),
    systemMode = stringResource(R.string.appearance_mode_system),
    lightMode = stringResource(R.string.appearance_mode_light),
    darkMode = stringResource(R.string.appearance_mode_dark),
    amoledDark = stringResource(R.string.appearance_amoled),
    amoledDarkSummary = stringResource(R.string.appearance_amoled_summary),
    colorSettings = stringResource(R.string.appearance_colors),
    paletteStyle = stringResource(R.string.appearance_palette),
    colorSpec = stringResource(R.string.appearance_color_spec),
    miuixMonet = stringResource(R.string.appearance_monet),
    miuixMonetSummary = stringResource(R.string.appearance_monet_summary),
    interfaceSettings = stringResource(R.string.appearance_interface),
    interfaceStyle = stringResource(R.string.appearance_style),
    floatingNavigationBar = stringResource(R.string.settings_floating_nav),
    floatingNavigationBarSummary = stringResource(R.string.settings_floating_nav_summary),
    blur = stringResource(R.string.appearance_blur),
    blurSummary = stringResource(R.string.appearance_blur_summary),
    predictiveBack = stringResource(R.string.appearance_predictive_back),
    predictiveBackSummary = stringResource(R.string.appearance_predictive_back_summary),
    interfaceScale = stringResource(R.string.appearance_scale),
    interfaceScaleSummary = stringResource(R.string.appearance_scale_summary),
    customColor = stringResource(R.string.appearance_custom_color),
    defaultValue = stringResource(R.string.slider_default),
    dialogConfirm = stringResource(R.string.dialog_confirm),
    dialogCancel = stringResource(R.string.dialog_cancel),
)
