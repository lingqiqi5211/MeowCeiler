package io.github.lingqiqi.meowceiler.hook.scopes

import io.github.lingqiqi.meowceiler.hook.base.StaticHooker
import io.github.lingqiqi.meowceiler.hook.rules.systemui.lockscreen.DoubleTapToSleep
import io.github.lingqiqi.meowceiler.hook.rules.systemui.statusbar.StatusBarClock

/** scope 自身不带开关，由 HookEntry 直接启用。 */
object SystemUi : StaticHooker() {
    override fun onInit() {
        attach(DoubleTapToSleep)
        attach(StatusBarClock)
    }
}
