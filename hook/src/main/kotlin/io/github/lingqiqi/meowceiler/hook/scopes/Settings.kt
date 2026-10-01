package io.github.lingqiqi.meowceiler.hook.scopes

import io.github.lingqiqi.meowceiler.hook.base.StaticHooker
import io.github.lingqiqi.meowceiler.hook.rules.settings.IconSettingsEntry
import io.github.lingqiqi.meowceiler.hook.rules.settings.ModuleEntry
import io.github.lingqiqi.meowceiler.hook.rules.settings.NotificationIconCount

object Settings : StaticHooker() {
    override fun onInit() {
        attach(ModuleEntry)
        attach(NotificationIconCount)
        attach(IconSettingsEntry)
    }
}
