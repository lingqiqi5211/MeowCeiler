package io.github.lingqiqi.meowceiler.hook.util

import android.os.SystemProperties
import io.github.lingqiqi.meowceiler.shared.HookEventKind
import io.github.lingqiqi.meowceiler.shared.HookLog
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.shared.SafeModeGate
import io.github.lingqiqi.meowceiler.shared.SafeModeRecord
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import kotlinx.coroutines.launch
import java.io.File

/**
 * 安全模式：模块在某个宿主里连续出错，就在那个宿主里整个停掉。
 *
 * 偏好存储是真相来源，但它在 CE 区，解锁前读不出来，而 SystemUI 解锁前就起来了；所以把「哪些宿主被停了」
 * 镜像到 [SafeModeGate] 的 `persist.` 属性，偏好可读时反向对齐。失败来源是 EzHookTool safeMode 挡住的回调异常。
 */
object SafeMode {
    /** 模块 apk 的标识。换过模块（重装/热重载新版本）旧记录与旧门闸都作废。 */
    private val moduleStamp: String by lazy {
        runCatching { File(EzXposed.modulePath).lastModified().toString() }.getOrDefault("0")
    }

    private var enabled = true

    /** 偏好这一趟是否真的读到了（而不是回落到 key 的默认值）。 */
    private var preferencesReadable = false

    private var records = emptyMap<String, SafeModeRecord>()

    /** 解锁前的门闸内容，来自系统属性。 */
    private var gate = emptySet<String>()

    /** 必须在 [Settings.init] 之后调。 */
    fun init() {
        gate = readGate()

        preferencesReadable = runCatching { Settings.isConnected() }.getOrDefault(false)

        if (preferencesReadable) {
            enabled = Settings.read(Preferences.SafeMode.Enabled)
            records = Settings.read(Preferences.SafeMode.Records)
                .mapNotNull(SafeModeRecord::decode)
                .filter { it.moduleStamp == moduleStamp }
                .associateBy { it.hostPackage }
            writeGate(records.values.filter { it.blocked }.map { it.hostPackage }.toSet())
        } else {
            MLog.w("safe mode: preferences unavailable (pre-unlock?), falling back to prop gate=$gate")
        }
    }

    /** 这个宿主是否已被停掉。 */
    fun isBlocked(hostPackage: String): Boolean {
        if (!enabled) return false
        records[hostPackage]?.let { return it.blocked }
        return !preferencesReadable && hostPackage in gate
    }

    /** 记一次失败。达到阈值就地生效。偏好读不到时不写回，免得冲掉真实计数。 */
    fun recordFailure(throwable: Throwable) {
        if (!enabled || !preferencesReadable) return
        val host = EzXposed.packageName.takeIf { it.isNotBlank() } ?: return

        val next = SafeModeRecord(
            hostPackage = host,
            failures = (records[host]?.failures ?: 0) + 1,
            moduleStamp = moduleStamp,
            message = throwable.summary(),
        )
        records = records + (host to next)

        if (next.blocked) {
            MLog.event(
                HookEventKind.SafeModeBlocked,
                'W',
                HookLog.FrameworkTag,
                "$host blocked after ${next.failures} failures",
            )
            writeGate(records.values.filter { it.blocked }.map { it.hostPackage }.toSet())
        }
        Settings.scope.launch {
            Settings.write(Preferences.SafeMode.Records, records.values.map { it.encode() }.toSet())
        }
    }

    private fun readGate(): Set<String> = runCatching {
        SafeModeGate.decode(SystemProperties.get(SafeModeGate.PropertyKey, ""), moduleStamp)
    }.getOrDefault(emptySet())

    /**
     * 写系统属性。要过 SELinux 的 set_prop 检查，宿主是 system UID 才有机会成功；
     * 失败只是退化成「解锁前没有门闸」，不影响偏好那份，所以不当错误处理。
     */
    private fun writeGate(blocked: Set<String>) {
        if (blocked == gate) return
        val value = SafeModeGate.encode(moduleStamp, blocked)
        runCatching { SystemProperties.set(SafeModeGate.PropertyKey, value) }
            .onSuccess { gate = blocked }
            .onFailure { MLog.w("safe mode: cannot write prop gate: ${it.message}") }
    }

    /** 异常摘要：类名 + 消息 + 最上面一帧，够定位又不至于把偏好塞爆。 */
    private fun Throwable.summary(): String {
        val frame = stackTrace.firstOrNull()
            ?.let { " @ ${it.className}.${it.methodName}:${it.lineNumber}" }
        return "${this::class.java.simpleName}: ${message.orEmpty()}${frame.orEmpty()}"
    }
}
