package io.github.lingqiqi.meowceiler.hook.scopes

import io.github.lingqiqi.meowceiler.hook.base.StaticHooker
import io.github.lingqiqi.meowceiler.hook.rules.systemui.lockscreen.DoubleTapToSleep
import io.github.lingqiqi.meowceiler.hook.rules.systemui.statusbar.StatusBarClock
import io.github.lingqiqi.meowceiler.hook.rules.systemui.controlcenter.NotificationWeather

object SystemUi : StaticHooker() {
    override fun onInit() {
        attach(DoubleTapToSleep)
        attach(StatusBarClock)
        attach(NotificationWeather)
    }
}
