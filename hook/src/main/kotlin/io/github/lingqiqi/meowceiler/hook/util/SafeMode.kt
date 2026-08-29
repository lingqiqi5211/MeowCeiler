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
 * 安全模式：模块在某个宿主里连续出错，就把它在那个宿主里整个停掉。
 *
 * ## 存两份，各管一段
 *
 * - **偏好存储**是真相来源：带失败次数和异常简述，设置界面读写它，也进了 [Preferences.all]
 *   所以备份/恢复/重置都覆盖得到。缺点是它在 CE（凭据加密）区，**用户解锁之前读不出来**。
 * - **[SafeModeGate] 那个 `persist.` 系统属性**只存「哪些宿主被停了」，作用是补上上面那个缺点：
 *   SystemUI 在解锁前就已经起来，如果那个阶段模块把它崩掉、而记录只在读不到的偏好里，
 *   就会陷入崩溃循环 —— 连锁屏都进不去，也就没法解锁去关掉它。属性存在 /data/property，
 *   属 DE 级，解锁前可读且跨重启保留。
 *
 * 两者的关系是单向的：偏好可读时以偏好为准，并把属性**反向对齐**成偏好的结论；偏好读不到时
 * （解锁前）才拿属性当依据。所以设置界面里点「重试」只需要改偏好，属性会在下一次 hook 装载时
 * 被 hook 侧抹掉 —— 模块应用自己是普通 UID，写不了系统属性。
 *
 * ## 判定来自哪里
 *
 * 来自**我们自己的 hook 回调边界**（BaseHooker 的 hookBefore / hookAfter），不去 hook
 * system_server 数宿主崩溃 —— 那需要「系统框架」作用域，而且崩溃计数分不清是不是模块干的。
 * 代价是只覆盖「我们的代码抛异常」这一类：hook 没抛却把宿主状态改坏、宿主稍后自己崩，
 * 这里发现不了，那种要另加一层系统级崩溃监控。
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

        // 解锁前 remote 不可用，read 会安静地回落到默认值 —— 那种「空记录」不能当成
        // 「没有被停掉的宿主」，否则门闸就白设了。用一个写不进去也读不回来的探针区分：
        // Records 的默认值是空集合，remote 不通时读到的就是它。
        preferencesReadable = runCatching { Settings.isConnected() }.getOrDefault(false)

        if (preferencesReadable) {
            enabled = Settings.read(Preferences.SafeMode.Enabled)
            records = Settings.read(Preferences.SafeMode.Records)
                .mapNotNull(SafeModeRecord::decode)
                .filter { it.moduleStamp == moduleStamp }
                .associateBy { it.hostPackage }
            // 偏好说了算：把门闸对齐过去。用户点过「重试」就是在这一步被真正兑现的。
            writeGate(records.values.filter { it.blocked }.map { it.hostPackage }.toSet())
        } else {
            MLog.w("safe mode: preferences unavailable (pre-unlock?), falling back to prop gate=$gate")
        }
    }

    /** 这个宿主是否已被停掉。 */
    fun isBlocked(hostPackage: String): Boolean {
        if (!enabled) return false
        records[hostPackage]?.let { return it.blocked }
        // 偏好没读到就信门闸。这是解锁前唯一的依据。
        return !preferencesReadable && hostPackage in gate
    }

    /**
     * 记一次失败。达到阈值就地生效 —— 后续回调不再执行，不用等重启宿主。
     *
     * 偏好读不到时不累加也不写回：那时连旧次数都不知道，写回去只会把真实计数冲掉。
     */
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
            // 门闸要立刻写：这次崩溃之后很可能就是重启，下次开机得在解锁前就拦住。
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
