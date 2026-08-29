package io.github.lingqiqi.meowceiler.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.ui.component.FeatureSwitchRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsSection
import io.github.lingqiqi.meowceiler.ui.settings.FrameworkBridge
import io.github.lingqiqi.meowceiler.ui.settings.NoFrameworkBridge
import io.github.lingqiqi.meowceiler.ui.settings.rememberHookLogState
import io.github.lingqiqi.meowceiler.ui.settings.rememberScopeState
import io.github.lingqiqi.meowceiler.ui.page.AboutPage
import io.github.lingqiqi.meowceiler.ui.page.FeatureLogPage
import io.github.lingqiqi.meowceiler.ui.page.HomePage
import io.github.lingqiqi.meowceiler.ui.page.HookLogPage
import io.github.lingqiqi.meowceiler.ui.page.ModuleSettingsPage
import io.github.lingqiqi.meowceiler.ui.page.SafeModePage
import io.github.lingqiqi.meowceiler.ui.page.ScopePage
import io.github.lingqiqi.meowceiler.ui.page.SystemUiLockScreenPage
import io.github.lingqiqi.meowceiler.ui.page.SystemUiPage
import io.github.lingqiqi5211.meowui.component.MeowAppearancePage
import io.github.lingqiqi5211.meowui.component.MeowAppearanceLabels
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi5211.meowui.component.MeowNavHost
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import androidx.compose.runtime.getValue
import io.github.lingqiqi5211.meowui.theme.MeowTheme

@Composable
fun MeowCeilerApp(bridge: FrameworkBridge = NoFrameworkBridge) {
    val appearance = rememberAppearanceController()
    val floatingNav by rememberMeowPreferenceValue(Preferences.Appearance.FloatingNavigation)
    val scopeSync by rememberMeowPreferenceValue(Preferences.Framework.ScopeSync)
    val scopeState = rememberScopeState(bridge)
    val hookLog = rememberHookLogState()

    MeowTheme(appearance = appearance.appearance) {
        val backStack = remember { mutableStateListOf<Route>(Route.Shell) }
        val push: (Route) -> Unit = { backStack.add(it) }
        val pop: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

        MeowNavHost(
            backStack = backStack,
            onBack = pop,
            predictiveBackEnabled = appearance.appearance.predictiveBackEnabled,
        ) { route ->
            when (route) {
                Route.Shell -> ShellPage(
                    floatingNavigation = floatingNav,
                    home = {
                        HomePage(
                            // 同步关着就不过滤；开着但还没读到作用域(null)也不过滤 —— 宁可多显示，
                            // 也不能因为服务没答上来就把宿主列表清空。
                            scopeFilter = scopeState.packages?.takeIf { scopeSync },
                            onOpenHost = push,
                        )
                    },
                    settings = {
                        ModuleSettingsPage(
                            bridge = bridge,
                            onOpenAppearance = { push(Route.Appearance) },
                            onOpenScope = { push(Route.Scope) },
                            onOpenSafeMode = { push(Route.SafeMode) },
                            onOpenHookLog = { push(Route.HookLog) },
                        )
                    },
                    about = { AboutPage() },
                )
                Route.Appearance -> MeowAppearancePage(
                    appearance = appearance.appearance,
                    onAppearanceChange = appearance.onChange,
                    onBackClick = pop,
                    labels = appearanceLabels(),
                    // 悬浮底栏是外观的事，不是模块设置的事；MeowUI 不认识这个键，
                    // 所以挂在它的 extraContent 上，跟在它自己的分组后面。
                    extraContent = {
                        SettingsSection(
                            titleRes = R.string.appearance_navigation,
                            testTag = "section.appearance.navigation",
                        ) {
                            FeatureSwitchRow(
                                key = Preferences.Appearance.FloatingNavigation,
                                titleRes = R.string.settings_floating_nav,
                                summaryRes = R.string.settings_floating_nav_summary,
                            )
                        }
                    },
                )
                Route.Scope -> ScopePage(bridge = bridge, scopeState = scopeState, onBack = pop)
                Route.SafeMode -> SafeModePage(onBack = pop)
                Route.SystemUi -> SystemUiPage(onBack = pop, onOpenCategory = push)
                Route.SystemUiLockScreen -> SystemUiLockScreenPage(onBack = pop)
                Route.HookLog -> HookLogPage(
                    state = hookLog,
                    onBack = pop,
                    onOpenFeature = { push(Route.FeatureLog(it)) },
                )
                is Route.FeatureLog -> FeatureLogPage(tag = route.tag, state = hookLog, onBack = pop)
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
    predictiveBack = stringResource(R.string.appearance_predictive_back),
    predictiveBackSummary = stringResource(R.string.appearance_predictive_back_summary),
    interfaceScale = stringResource(R.string.appearance_scale),
    interfaceScaleSummary = stringResource(R.string.appearance_scale_summary),
    customColor = stringResource(R.string.appearance_custom_color),
    dialogConfirm = stringResource(R.string.dialog_confirm),
    dialogCancel = stringResource(R.string.dialog_cancel),
)
