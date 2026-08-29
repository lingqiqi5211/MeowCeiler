package io.github.lingqiqi.meowceiler.logs

import android.content.Context
import android.provider.Settings
import io.github.lingqiqi.meowceiler.shared.HookLog
import io.github.lingqiqi.meowceiler.shared.HookLogRecord
import java.io.File

/**
 * hook 日志的落盘。跑在模块进程里，由 [HookLogProvider] 独占调用。
 *
 * ## append-only journal
 *
 * 每次写就是往文件尾追加几行。这样便宜，而且天然抗撕裂：进程在写一半时被杀，读的时候丢掉最后
 * 那行残行，之前的全都还在。**不整体重写** —— 重写到一半死掉会把历史一起赔进去。
 *
 * 行数超过 [HookLog.CompactThreshold] 才压缩一次：回放成内存态，写临时文件，rename 顶上去。
 * rename 在同一文件系统上是原子的，所以压缩本身也不会留下半个文件。
 *
 * ## 两代
 *
 * journal 里插 `#boot` 标记分代，读的时候按标记切分。**不做文件轮转** —— 轮转要在「写入时发现
 * 换代了」才触发，这一代要是一条都没写，上一代的内容会被当成本次运行显示。标记法没有这个洞。
 *
 * ## 放 DE 区
 *
 * `createDeviceProtectedStorageContext()`。SystemUI 在解锁前就起来了，CE 区那时候读写不了，
 * 而开机早期恰恰是最需要日志的时候。
 */
internal class HookLogStore(context: Context) {

    private val lock = Any()

    private val directory: File =
        File(context.createDeviceProtectedStorageContext().filesDir, "hooklog")

    private val journal: File = File(directory, "journal")

    private val bootCount: Int =
        runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
        }.getOrDefault(0)

    /** 本次运行的换代标记是否已经写过。只在进程内记，重启后由标记本身兜底。 */
    private var bootMarkerWritten = false

    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        synchronized(lock) {
            runCatching {
                directory.mkdirs()
                val pending = buildList {
                    if (!bootMarkerWritten && lastBootCountInJournal() != bootCount) {
                        add(HookLog.encodeBootMarker(bootCount))
                    }
                    addAll(lines)
                }
                bootMarkerWritten = true
                journal.appendText(pending.joinToString("\n", postfix = "\n"))
            }.onFailure { return }

            if (countLines() > HookLog.CompactThreshold) compact()
        }
    }

    /** 返回 (本次运行, 上次重启前) 两代的记录，各自已经合并过。 */
    fun read(): Generations = synchronized(lock) { load() }

    fun clear() {
        synchronized(lock) {
            runCatching { journal.delete() }
            bootMarkerWritten = false
        }
    }

    // region 读

    private fun load(): Generations {
        val lines = runCatching { journal.readLines() }.getOrDefault(emptyList())

        // 按 #boot 标记切代。标记之前的行属于「更早」，读的时候直接丢。
        val generations = mutableListOf<MutableList<HookLogRecord>>()
        var current: MutableList<HookLogRecord>? = null
        for (line in lines) {
            if (HookLog.decodeBootMarker(line) != null) {
                current = mutableListOf()
                generations += current
                continue
            }
            // decode 返回 null 的是半行残留或旧格式，丢掉它就行，不影响其它行。
            val record = HookLog.decode(line) ?: continue
            (current ?: mutableListOf<HookLogRecord>().also { generations += it; current = it }) += record
        }

        val kept = generations.takeLast(HookLog.KeptGenerations)
        return Generations(
            current = merge(kept.getOrNull(kept.lastIndex).orEmpty()),
            previous = merge(kept.getOrNull(kept.lastIndex - 1).orEmpty()),
        )
    }

    /**
     * 回放：同一合并键的多条压成一条，时间取并集。
     *
     * 流水不合并，只保留最近 [HookLog.MaxTrace] 条。
     */
    private fun merge(records: List<HookLogRecord>): List<HookLogRecord> {
        val problems = LinkedHashMap<String, HookLogRecord>()
        val trace = ArrayDeque<HookLogRecord>()

        for (record in records) {
            if (record.isTrace) {
                trace.addLast(record)
                while (trace.size > HookLog.MaxTrace) trace.removeFirst()
                continue
            }
            val existing = problems.remove(record.mergeKey)
            problems[record.mergeKey] = if (existing == null) {
                record
            } else {
                // 宿主侧送来的 count 已经是它那边的累计值，取大的那个而不是相加 ——
                // 同一代里的多次 flush 是同一串计数的快照，相加会翻倍。
                record.copy(
                    firstMillis = minOf(existing.firstMillis, record.firstMillis),
                    lastMillis = maxOf(existing.lastMillis, record.lastMillis),
                    count = maxOf(existing.count, record.count),
                )
            }
        }

        while (problems.size > HookLog.MaxProblems) {
            problems.remove(problems.keys.first())
        }
        return problems.values + trace
    }

    private fun lastBootCountInJournal(): Int? = runCatching {
        journal.readLines().asReversed().firstNotNullOfOrNull(HookLog::decodeBootMarker)
    }.getOrNull()

    private fun countLines(): Int =
        runCatching { journal.readLines().size }.getOrDefault(0)

    // endregion

    /** 回放成内存态再写回去，只保留该留的两代。临时文件 + rename，中途死掉不会毁掉原文件。 */
    private fun compact() {
        runCatching {
            val generations = load()
            val temp = File(directory, "journal.tmp")
            val text = buildString {
                if (generations.previous.isNotEmpty()) {
                    // 上一代的标记用一个不等于当前的值，保证它仍然被切成单独一代。
                    appendLine(HookLog.encodeBootMarker(bootCount - 1))
                    generations.previous.forEach { appendLine(HookLog.encode(it)) }
                }
                appendLine(HookLog.encodeBootMarker(bootCount))
                generations.current.forEach { appendLine(HookLog.encode(it)) }
            }
            temp.writeText(text)
            if (!temp.renameTo(journal)) {
                journal.writeText(text)
                temp.delete()
            }
        }
    }

    internal data class Generations(
        val current: List<HookLogRecord>,
        val previous: List<HookLogRecord>,
    )
}
