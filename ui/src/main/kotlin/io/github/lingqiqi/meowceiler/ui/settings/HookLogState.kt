package io.github.lingqiqi.meowceiler.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.lingqiqi.meowceiler.shared.HookEventKind
import io.github.lingqiqi.meowceiler.shared.HookLog
import io.github.lingqiqi.meowceiler.shared.HookLogRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从模块自己的 provider 把 hook 日志读回来。
 *
 * 读的是模块自己进程的 provider、落在模块自己的目录里，所以既不需要 root，也不用跨 uid ——
 * 跨 uid 那一段发生在写入侧（宿主 → provider），见 `:shared` 的 `HookLog`。
 */
class HookLogState internal constructor(private val context: Context) {

    var current by mutableStateOf<List<HookLogRecord>>(emptyList())
        private set

    var previous by mutableStateOf<List<HookLogRecord>>(emptyList())
        private set

    suspend fun refresh() {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.call(HookLog.Authority, HookLog.MethodRead, null, null)
            }.getOrNull()
        }
        current = result?.decode(HookLog.KeyRecords).orEmpty()
        previous = result?.decode(HookLog.KeyRecordsPrevious).orEmpty()
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.call(HookLog.Authority, HookLog.MethodClear, null, null)
            }
        }
        refresh()
    }

    private fun android.os.Bundle.decode(key: String): List<HookLogRecord> =
        getStringArrayList(key).orEmpty().mapNotNull(HookLog::decode)
}

/** 状态类记录与 `W`/`E` 级日志都算「问题」。 */
private val HookLogRecord.isProblem: Boolean
    get() = when (kind) {
        HookEventKind.InitFailed,
        HookEventKind.HookFailed,
        HookEventKind.CallbackFailed,
        HookEventKind.SafeModeBlocked,
        -> true

        HookEventKind.Installed -> false
        HookEventKind.Log -> level == 'W' || level == 'E'
    }

data class FeatureHealth(
    val tag: String,
    val installed: Boolean,
    val problems: List<HookLogRecord>,
) {
    val hasProblem: Boolean get() = problems.isNotEmpty()

    /** 出问题的总次数。合并过，所以这是「发生次数」而不是「记录条数」。 */
    val failureCount: Int get() = problems.sumOf { it.count }
}

@Composable
fun rememberHookLogState(): HookLogState {
    val context = LocalContext.current
    val state = remember(context) { HookLogState(context.applicationContext) }
    LaunchedEffect(state) { state.refresh() }
    return state
}

/**
 * 记录里出现过的功能，按宿主分组。
 *
 * 清单从记录本身来 —— 不需要在 `:shared` 里另立一份功能注册表。代价是没被装载过的功能不会出现，
 * 而那恰恰也是一种有用的信息：它在这一代里从没被碰过。
 */
fun List<HookLogRecord>.groupByHost(): Map<String, List<FeatureHealth>> =
    groupBy { it.host }
        .toSortedMap()
        .mapValues { (_, hostRecords) ->
            hostRecords.groupBy { it.tag }
                .map { (tag, tagRecords) ->
                    FeatureHealth(
                        tag = tag,
                        installed = tagRecords.any { it.kind == HookEventKind.Installed },
                        problems = tagRecords.filter { it.isProblem }
                            .sortedByDescending { it.lastMillis },
                    )
                }
                // 出过问题的排前面，其余按 id 排，位置稳定。
                .sortedWith(compareByDescending<FeatureHealth> { it.hasProblem }.thenBy { it.tag })
        }
