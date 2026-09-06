package io.github.lingqiqi.meowceiler.logs

import android.content.Context
import android.provider.Settings
import io.github.lingqiqi.meowceiler.shared.HookLog
import io.github.lingqiqi.meowceiler.shared.HookLogRecord
import java.io.File

/**
 * hook 日志落盘，[HookLogProvider] 独占调用。append-only journal：追加便宜且抗撕裂，超过 [HookLog.CompactThreshold]
 * 才压缩，写临时文件后 rename。用 `#boot` 标记分代而不轮转文件。放 DE 区，SystemUI 解锁前就在写。
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

    /** [tag] 为空清全部；给了功能 id 就只删这个功能的记录，其余按原样写回。 */
    fun clear(tag: String?) {
        synchronized(lock) {
            if (tag == null) {
                runCatching { journal.delete() }
                bootMarkerWritten = false
                return
            }
            runCatching {
                val kept = journal.readLines().filter { line ->
                    HookLog.decodeBootMarker(line) != null || HookLog.decode(line)?.tag != tag
                }
                journal.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
            }
        }
    }

    // region 读

    private fun load(): Generations {
        val lines = runCatching { journal.readLines() }.getOrDefault(emptyList())

        val generations = mutableListOf<MutableList<HookLogRecord>>()
        var current: MutableList<HookLogRecord>? = null
        for (line in lines) {
            if (HookLog.decodeBootMarker(line) != null) {
                current = mutableListOf()
                generations += current
                continue
            }
            val record = HookLog.decode(line) ?: continue
            (current ?: mutableListOf<HookLogRecord>().also { generations += it; current = it }) += record
        }

        val kept = generations.takeLast(HookLog.KeptGenerations)
        return Generations(
            current = merge(kept.getOrNull(kept.lastIndex).orEmpty()),
            previous = merge(kept.getOrNull(kept.lastIndex - 1).orEmpty()),
        )
    }

    /** 回放：同一合并键压成一条；流水不合并，只保留最近 [HookLog.MaxTrace] 条。 */
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
