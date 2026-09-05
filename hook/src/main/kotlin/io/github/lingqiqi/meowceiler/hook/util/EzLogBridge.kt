package io.github.lingqiqi.meowceiler.hook.util

import io.github.lingqiqi5211.ezhooktool.core.EzLogger
import io.github.lingqiqi5211.ezhooktool.core.EzReflect

/**
 * 把 EzHookTool 的日志接进 [MLog]。库默认打到 System.err，宿主进程里等于丢掉。每一代都要重新设一次。
 * hook 回调异常由库的 safeMode 挡住后从 error 报出来，这里顺手计入 [SafeMode]。
 */
object EzLogBridge : EzLogger {
    fun install() {
        EzReflect.logger = this
    }

    override fun debug(tag: String, msg: String) = MLog.d(tag, msg)

    override fun warn(tag: String, msg: String) = MLog.w(tag, msg)

    override fun error(tag: String, msg: String, t: Throwable?) {
        if (t == null) MLog.e(tag, msg) else MLog.e(tag, msg, t)
        if (t != null) SafeMode.recordFailure(t)
    }
}
