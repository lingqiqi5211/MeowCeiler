package io.github.lingqiqi.meowceiler.shared

import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey

/**
 * 设置进程与 Hook 进程共用的唯一契约。key 名、类型、默认值只有这一处定义。
 *
 * 默认值必须是「远程设置不可用时对宿主安全」的那个值。
 */
object Preferences {
    /** 两侧必须用同一个名字。 */
    const val NAME = "meowceiler"

    object Module {
        val Enabled = PreferenceKey("module_enabled", true)
        val Debug = PreferenceKey("module_debug", false)
    }

    /** 外观。只有设置进程用，hook 侧不读。枚举存字符串，避免序号漂移。 */
    object Appearance {
        val Style = PreferenceKey("appearance_style", "material")
        val ThemeMode = PreferenceKey("appearance_theme_mode", "system")
        val DynamicColor = PreferenceKey("appearance_dynamic_color", true)
        val SeedColor = PreferenceKey("appearance_seed_color", 0xFF6750A4L)
        val PaletteStyle = PreferenceKey("appearance_palette_style", "tonal_spot")
        val MiuixMonet = PreferenceKey("appearance_miuix_monet", true)
        val AmoledDark = PreferenceKey("appearance_amoled_dark", false)
        val Blur = PreferenceKey("appearance_blur", true)
        val PredictiveBack = PreferenceKey("appearance_predictive_back", true)
        val InterfaceScale = PreferenceKey("appearance_interface_scale", 1f)
        val FloatingNavigation = PreferenceKey("appearance_floating_nav", false)
    }

    object SystemUi {
        val DoubleTapToSleep = PreferenceKey("systemui_lockscreen_double_tap", false)
        val Clock = PreferenceKey("systemui_clock", false)
        val ClockNoSync = PreferenceKey("systemui_clock_no_sync", false)
        val ClockNoShadeAnimation = PreferenceKey("systemui_clock_no_shade_animation", false)
        val ClockFormatStatusBar = PreferenceKey("systemui_clock_format_statusbar", "HH:mm")
        val ClockFormatBig = PreferenceKey("systemui_clock_format_big", "HH:mm")
        val ClockFormatMini = PreferenceKey("systemui_clock_format_mini", "")
        val ClockStyle = PreferenceKey("systemui_clock_style", 0)
        val ClockAlign = PreferenceKey("systemui_clock_align", 0)
        val ClockSpacing = PreferenceKey("systemui_clock_spacing", 0.8f)
        val ClockFixedWidth = PreferenceKey("systemui_clock_fixed_width", 30f)
        val ClockBoldStatusBar = PreferenceKey("systemui_clock_bold_statusbar", false)
        val ClockLeftStatusBar = PreferenceKey("systemui_clock_left_statusbar", 0f)
        val ClockRightStatusBar = PreferenceKey("systemui_clock_right_statusbar", 0f)
        val ClockOffsetStatusBar = PreferenceKey("systemui_clock_offset_statusbar", 12f)
        val ClockBoldBig = PreferenceKey("systemui_clock_bold_big", false)
        val ClockLeftBig = PreferenceKey("systemui_clock_left_big", 0f)
        val ClockRightBig = PreferenceKey("systemui_clock_right_big", 0f)
        val ClockOffsetBig = PreferenceKey("systemui_clock_offset_big", 12f)
        val ClockBoldMini = PreferenceKey("systemui_clock_bold_mini", false)
        val ClockLeftMini = PreferenceKey("systemui_clock_left_mini", 0f)
        val ClockRightMini = PreferenceKey("systemui_clock_right_mini", 0f)
        val ClockOffsetMini = PreferenceKey("systemui_clock_offset_mini", 12f)
    }

    /** 注入到系统设置里的模块入口。取值见 [SettingsEntryPosition]。 */
    object SettingsEntry {
        val Position = PreferenceKey("settings_entry_position", SettingsEntryPosition.Off.key)
    }

    /**
     * 功能级安全模式。
     *
     * 和 HyperCeiler 的整宿主停用不同：这里隔离的是**单个功能**，靠 hook 回调边界归因，
     * 并以模块 apk 的标识做有效期 —— 模块一换记录自动作废、功能自动重试。
     */
    object SafeMode {
        val Enabled = PreferenceKey("safe_mode_enabled", true)
        val Records = PreferenceKey("safe_mode_records", emptySet<String>())
    }

    /** 开着时首页只显示在框架作用域里的宿主，见 ScopePage。 */
    object Framework {
        val ScopeSync = PreferenceKey("framework_scope_sync", false)
    }

    /**
     * 全部键。备份、恢复、重置都按这份走。
     *
     * 新增键必须同时加进来 —— 漏了不会报错，只会在备份里静默缺一项。
     */
    val all: List<PreferenceKey<*>> = listOf(
        Module.Enabled,
        Module.Debug,
        Appearance.Style,
        Appearance.ThemeMode,
        Appearance.DynamicColor,
        Appearance.SeedColor,
        Appearance.PaletteStyle,
        Appearance.MiuixMonet,
        Appearance.AmoledDark,
        Appearance.Blur,
        Appearance.PredictiveBack,
        Appearance.InterfaceScale,
        Appearance.FloatingNavigation,
        SystemUi.DoubleTapToSleep,
        SystemUi.Clock,
        SystemUi.ClockNoSync,
        SystemUi.ClockNoShadeAnimation,
        SystemUi.ClockFormatStatusBar,
        SystemUi.ClockFormatBig,
        SystemUi.ClockFormatMini,
        SystemUi.ClockStyle,
        SystemUi.ClockAlign,
        SystemUi.ClockSpacing,
        SystemUi.ClockFixedWidth,
        SystemUi.ClockBoldStatusBar,
        SystemUi.ClockLeftStatusBar,
        SystemUi.ClockRightStatusBar,
        SystemUi.ClockOffsetStatusBar,
        SystemUi.ClockBoldBig,
        SystemUi.ClockLeftBig,
        SystemUi.ClockRightBig,
        SystemUi.ClockOffsetBig,
        SystemUi.ClockBoldMini,
        SystemUi.ClockLeftMini,
        SystemUi.ClockRightMini,
        SystemUi.ClockOffsetMini,
        SettingsEntry.Position,
        Framework.ScopeSync,
        SafeMode.Enabled,
        SafeMode.Records,
    )
}

/**
 * 桌面图标入口的别名组件，隐藏图标就是禁用它。
 *
 * 真正的 Activity 不能直接禁用 —— 那样设置界面自己也打不开了。
 */
const val LauncherAliasName = ".LauncherAlias"

/**
 * 模块自身的包名与设置入口。
 *
 * hook 跑在宿主进程里，拿不到自己的 BuildConfig，只能写死；必须和 `:app` 的
 * applicationId 一致。[LauncherAliasName] 也是同一份耦合。
 */
const val ModulePackage = "io.github.lingqiqi.meowceiler"
const val ModuleSettingsActivity = "$ModulePackage.SettingsActivity"

/** 模块显示名。`app_name` 本来就是 translatable="false"，不必绕资源。 */
const val ModuleName = "MeowCeiler-Lite"
