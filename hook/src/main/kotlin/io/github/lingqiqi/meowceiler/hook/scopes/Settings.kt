package io.github.lingqiqi.meowceiler.hook.scopes

import io.github.lingqiqi.meowceiler.hook.base.StaticHooker
import io.github.lingqiqi.meowceiler.hook.rules.settings.ModuleEntry

object Settings : StaticHooker() {
    override fun onInit() {
        attach(ModuleEntry)
    }
}
