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

/** 读取模块自身的日志 Provider，无需 root；跨 UID 校验由写入侧负责。 */
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

    /** [tag] 为空清全部；给了功能 id 就只清这个功能。 */
    suspend fun clear(tag: String? = null) {
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.call(HookLog.Authority, HookLog.MethodClear, tag, null)
            }
        }
        refresh()
    }

    private fun android.os.Bundle.decode(key: String): List<HookLogRecord> =
        getStringArrayList(key).orEmpty().mapNotNull(HookLog::decode)
}

/** 状态类记录与 `W`/`E` 级日志都算「问题」。 */
val HookLogRecord.isProblem: Boolean
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

/** 按宿主分组，只包含日志中出现过的功能。 */
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
                .sortedWith(compareByDescending<FeatureHealth> { it.hasProblem }.thenBy { it.tag })
        }
