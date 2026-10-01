package io.github.lingqiqi.meowceiler.shared

/**
 * 宿主里注入的入口用它直达模块的某一页。
 *
 * 值是稳定字符串，UI 侧把它翻成路由；改名等于让旧入口失效。
 */
object ModuleLink {
    const val Extra = "meowceiler.page"

    const val SettingsHost = "settings"
}
