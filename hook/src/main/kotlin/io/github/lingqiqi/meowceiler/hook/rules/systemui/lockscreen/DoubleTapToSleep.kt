package io.github.lingqiqi.meowceiler.hook.rules.systemui.lockscreen

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import io.github.lingqiqi.meowceiler.hook.base.Feature
import io.github.lingqiqi.meowceiler.hook.base.StaticHooker
import io.github.lingqiqi.meowceiler.hook.util.MLog
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.toClass
import kotlin.math.abs

@Feature(
    name = "锁屏双击息屏",
    since = "2026-08-15",
    target = "SystemUI 17.03.260226.r",
    updated = "2026-08-15",
)
object DoubleTapToSleep : StaticHooker(Preferences.SystemUi.DoubleTapToSleep) {

    private const val DoubleTapWindowMs = 250L
    private const val SlopPx = 100f

    private var lastDownTime = 0L
    private var lastX = 0f
    private var lastY = 0f

    /** goToSleep 是公开类上的隐藏成员，:hidden-api 的桩覆盖不到，只能反射。这里解析一次。 */
    private val goToSleep by lazy {
        runCatching {
            PowerManager::class.java.findMethod {
                name("goToSleep")
                paramCount(1)
            }
        }.getOrNull()
    }

    override fun onHook() {
        // NotificationsQuickSettingsContainer 是锁屏与下拉的公共容器，
        // 在 dispatchTouchEvent 上拦得到整块区域，比逐个 View 挂监听稳。
        "com.android.systemui.shade.NotificationsQuickSettingsContainer".toClass()
            .findMethod { name("dispatchTouchEvent") }
            .hookBefore { param ->
                val event = param.args.getOrNull(0) as? MotionEvent ?: return@hookBefore
                if (event.action != MotionEvent.ACTION_DOWN) return@hookBefore
                val view = param.thisObjectOrNull as? View ?: return@hookBefore

                val now = SystemClock.uptimeMillis()
                val isDoubleTap = now - lastDownTime < DoubleTapWindowMs &&
                    abs(event.x - lastX) < SlopPx &&
                    abs(event.y - lastY) < SlopPx

                if (isDoubleTap) {
                    lastDownTime = 0L
                    if (sleepIfLocked(view.context)) param.result = true
                } else {
                    lastDownTime = now
                    lastX = event.x
                    lastY = event.y
                }
            }
    }

    /** 只在真的锁着时息屏，避免下拉通知栏时误触发。 */
    private fun sleepIfLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isKeyguardLocked != true) return false

        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        val method = goToSleep ?: run {
            MLog.w("$id: PowerManager.goToSleep not found")
            return false
        }
        return runCatching { method.invoke(power, SystemClock.uptimeMillis()) }.isSuccess
    }
}
