package io.github.lingqiqi.meowceiler.ui

import io.github.lingqiqi.meowceiler.shared.HookLogRecord

/** 导航目的地。层级：Shell（三 Tab）→ 宿主 hub / 外观页 → 分类页。 */
enum class ClockPart { StatusBar, Big, Mini, Pad }

sealed interface Route {
    data object Shell : Route
    data object Appearance : Route

    data object Scope : Route

    data object HomeHosts : Route

    data object Licenses : Route

    data object HookLog : Route

    data object SafeMode : Route

    /** 宿主 hub：只放分类入口，不放开关。 */
    data object SystemUi : Route

    data object SystemUiLockScreen : Route
    data object SystemUiStatusBar : Route
    data object SystemUiClock : Route
    data object SystemUiIcons : Route
    data object SystemUiNotificationCenter : Route
    data object SystemUiNotificationWeather : Route
    data class SystemUiClockLayout(val part: ClockPart) : Route

    /** 某个作用域的全部日志。 */
    data class ScopeLog(val host: String) : Route

    /** 某个功能的 hook 日志。tag 就是功能 id。 */
    data class FeatureLog(val tag: String) : Route

    data class LogRecord(val record: HookLogRecord) : Route
}
