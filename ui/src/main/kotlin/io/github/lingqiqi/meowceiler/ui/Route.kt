package io.github.lingqiqi.meowceiler.ui

import io.github.lingqiqi.meowceiler.shared.HookLogRecord

/** 导航目的地。层级：Shell（三 Tab）→ 宿主 hub / 外观页 → 分类页。 */
enum class ClockPart { StatusBar, Big, Mini }

sealed interface Route {
    data object Shell : Route
    data object Appearance : Route

    /** 框架作用域。 */
    data object Scope : Route

    /** 首页显示哪些宿主。 */
    data object HomeHosts : Route

    /** 开源许可。 */
    data object Licenses : Route

    /** hook 日志总览。 */
    data object HookLog : Route

    /** 安全模式。 */
    data object SafeMode : Route

    /** 宿主 hub：只放分类入口，不放开关。 */
    data object SystemUi : Route

    /** 分类页：真正的开关在这一层。 */
    data object SystemUiLockScreen : Route
    data object SystemUiStatusBar : Route
    data class SystemUiClockLayout(val part: ClockPart) : Route

    /** 某个功能的 hook 日志。tag 就是功能 id。 */
    data class FeatureLog(val tag: String) : Route

    /** 一条记录的全文。 */
    data class LogRecord(val record: HookLogRecord) : Route
}
