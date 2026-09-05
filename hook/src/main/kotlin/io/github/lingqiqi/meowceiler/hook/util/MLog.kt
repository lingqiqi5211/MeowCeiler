package io.github.lingqiqi.meowceiler.hook.util

import android.util.Log
import io.github.lingqiqi.meowceiler.shared.HookEventKind
import io.github.lingqiqi.meowceiler.shared.HookLog

/** hook 侧日志：同时进 logcat 和模块的日志页（[HookLogReporter]）。带 tag 的重载里 tag 是功能 id。 */
object MLog {
    private const val Tag = "MeowCeiler"

    var debugEnabled: Boolean = false

    fun d(message: String) = write('D', HookLog.FrameworkTag, message, null)

    fun d(tag: String, message: String) = write('D', tag, message, null)

    fun i(message: String) = write('I', HookLog.FrameworkTag, message, null)

    fun i(tag: String, message: String) = write('I', tag, message, null)

    fun w(message: String) = write('W', HookLog.FrameworkTag, message, null)

    fun w(tag: String, message: String) = write('W', tag, message, null)

    fun w(message: String, throwable: Throwable) =
        write('W', HookLog.FrameworkTag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable) =
        write('W', tag, message, throwable)

    fun e(message: String) = write('E', HookLog.FrameworkTag, message, null)

    fun e(tag: String, message: String) = write('E', tag, message, null)

    fun e(message: String, throwable: Throwable) =
        write('E', HookLog.FrameworkTag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable) =
        write('E', tag, message, throwable)

    /** 结构化事件，日志页据此推功能健康状态。功能代码不用调，[io.github.lingqiqi.meowceiler.hook.base.BaseHooker] 已经发了。 */
    internal fun event(
        kind: HookEventKind,
        level: Char,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) = write(level, tag, message, throwable, kind)

    private fun write(
        level: Char,
        tag: String,
        message: String,
        throwable: Throwable?,
        kind: HookEventKind = HookEventKind.Log,
    ) {
        if (level == 'D' && !debugEnabled) return

        val text = if (tag == HookLog.FrameworkTag) message else "$tag: $message"
        when (level) {
            'D' -> Log.d(Tag, text, throwable)
            'I' -> Log.i(Tag, text, throwable)
            'W' -> Log.w(Tag, text, throwable)
            else -> Log.e(Tag, text, throwable)
        }

        val reported = if (throwable == null) message else "$message — ${throwable.summary()}"
        runCatching { HookLogReporter.report(kind, level, tag, reported) }
    }

    /** 类名 + 消息 + 最上面一帧。够定位，又不至于把一整份堆栈塞进记录。 */
    private fun Throwable.summary(): String {
        val frame = stackTrace.firstOrNull()
            ?.let { " @ ${it.className}.${it.methodName}:${it.lineNumber}" }
        return "${this::class.java.simpleName}: ${message.orEmpty()}${frame.orEmpty()}"
    }
}
