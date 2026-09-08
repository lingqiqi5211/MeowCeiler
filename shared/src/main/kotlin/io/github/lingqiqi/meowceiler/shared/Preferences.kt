package io.github.lingqiqi.meowceiler.shared

import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey

/**
 * 设置进程与 Hook 进程共用的唯一契约。key 名、类型、默认值只有这一处定义。
 *
 * 默认值必须是「远程设置不可用时对宿主安全」的那个值。
 */
object Preferences {
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
        val FloatingNavigation = PreferenceKey("appearance_floating_nav", false)
        val PredictiveBack = PreferenceKey("appearance_predictive_back", true)
        val InterfaceScale = PreferenceKey("appearance_interface_scale", 1f)
    }

    object SystemUi {
        val DoubleTapToSleep = PreferenceKey("systemui_lockscreen_double_tap", false)
        val Clock = PreferenceKey("systemui_clock", false)
        val NotificationWeather = PreferenceKey("systemui_notification_weather", false)
        val NotificationWeatherCity = PreferenceKey("systemui_notification_weather_city", false)
        val NotificationWeatherNewLine = PreferenceKey("systemui_notification_weather_new_line", false)
        val NotificationWeatherBold = PreferenceKey("systemui_notification_weather_bold", false)
        /** sp，0 表示跟随宿主日期字号。 */
        val NotificationWeatherSize = PreferenceKey("systemui_notification_weather_size", 0f)
        val NotificationWeatherIcon = PreferenceKey("systemui_notification_weather_icon", false)
        /** dp，0 表示跟随宿主给日期留的间距。 */
        val NotificationWeatherMargin = PreferenceKey("systemui_notification_weather_margin", 0f)
        val ClockNoSync = PreferenceKey("systemui_clock_no_sync", false)
        val ClockNoShadeAnimation = PreferenceKey("systemui_clock_no_shade_animation", false)
        val ClockFormatStatusBar = PreferenceKey("systemui_clock_format_statusbar", "HH:mm")
        val ClockFormatBig = PreferenceKey("systemui_clock_format_big", "HH:mm")
        val ClockFormatMini = PreferenceKey("systemui_clock_format_mini", "")
        val ClockSizeStatusBar = PreferenceKey("systemui_clock_size_statusbar", 0f)
        val ClockSizeBig = PreferenceKey("systemui_clock_size_big", 0f)
        val ClockSizeMini = PreferenceKey("systemui_clock_size_mini", 0f)
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
        val ClockHiddenPad = PreferenceKey("systemui_clock_hidden_pad", false)
        val ClockFormatPad = PreferenceKey("systemui_clock_format_pad", "")
        val ClockBoldPad = PreferenceKey("systemui_clock_bold_pad", false)
        val ClockSizePad = PreferenceKey("systemui_clock_size_pad", 0f)
        val ClockLeftPad = PreferenceKey("systemui_clock_left_pad", 0f)
        val ClockRightPad = PreferenceKey("systemui_clock_right_pad", 0f)
        val ClockOffsetPad = PreferenceKey("systemui_clock_offset_pad", 12f)
    }

    /** 注入到系统设置里的模块入口。取值见 [SettingsEntryPosition]。 */
    object SettingsEntry {
        val Position = PreferenceKey("settings_entry_position", SettingsEntryPosition.Off.key)
    }

    /** 安全模式记录以模块 APK 标识为有效期，模块更新后自动失效。 */
    object SafeMode {
        val Enabled = PreferenceKey("safe_mode_enabled", true)
        val Records = PreferenceKey("safe_mode_records", emptySet<String>())
    }

    /** 开着时首页只显示在框架作用域里的宿主，见 ScopePage。 */
    object Framework {
        val ScopeSync = PreferenceKey("framework_scope_sync", false)
    }

    object Home {
        /** 首页藏起来的宿主。存「藏了谁」而不是「显示谁」：以后新增宿主默认就在首页上。 */
        val HiddenHosts = PreferenceKey("home_hidden_hosts", emptySet<String>())
    }

    /** 备份、恢复和重置使用此清单；新增设置键须同步注册。 */
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
        Appearance.FloatingNavigation,
        Appearance.PredictiveBack,
        Appearance.InterfaceScale,
        SystemUi.DoubleTapToSleep,
        SystemUi.Clock,
        SystemUi.NotificationWeather,
        SystemUi.NotificationWeatherCity,
        SystemUi.NotificationWeatherNewLine,
        SystemUi.NotificationWeatherBold,
        SystemUi.NotificationWeatherSize,
        SystemUi.NotificationWeatherIcon,
        SystemUi.NotificationWeatherMargin,
        SystemUi.ClockNoSync,
        SystemUi.ClockNoShadeAnimation,
        SystemUi.ClockFormatStatusBar,
        SystemUi.ClockFormatBig,
        SystemUi.ClockFormatMini,
        SystemUi.ClockSizeStatusBar,
        SystemUi.ClockSizeBig,
        SystemUi.ClockSizeMini,
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
        SystemUi.ClockHiddenPad,
        SystemUi.ClockFormatPad,
        SystemUi.ClockBoldPad,
        SystemUi.ClockSizePad,
        SystemUi.ClockLeftPad,
        SystemUi.ClockRightPad,
        SystemUi.ClockOffsetPad,
        SettingsEntry.Position,
        Framework.ScopeSync,
        Home.HiddenHosts,
        SafeMode.Enabled,
        SafeMode.Records,
    )
}

/** 隐藏桌面图标仅禁用别名，保留实际设置 Activity。 */
const val LauncherAliasName = ".LauncherAlias"

/** Hook 无法读取模块 BuildConfig；此包名须与 :app 的 applicationId 及 [LauncherAliasName] 一致。 */
const val ModulePackage = "io.github.lingqiqi.meowceiler"
const val ModuleSettingsActivity = "$ModulePackage.SettingsActivity"

/** 模块显示名。`app_name` 本来就是 translatable="false"，不必绕资源。 */
const val ModuleName = "MeowCeiler-Lite"
