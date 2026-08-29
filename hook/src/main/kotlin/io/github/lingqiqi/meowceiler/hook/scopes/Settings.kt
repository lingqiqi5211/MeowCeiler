package io.github.lingqiqi.meowceiler.hook.scopes

import io.github.lingqiqi.meowceiler.hook.base.StaticHooker
import io.github.lingqiqi.meowceiler.hook.rules.settings.ModuleEntry

/** scope 自身不带开关，由 HookEntry 直接启用。 */
object Settings : StaticHooker() {
    override fun onInit() {
        attach(ModuleEntry)
    }
}
