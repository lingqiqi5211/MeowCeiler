package io.github.lingqiqi.meowceiler.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/** [packages] 为 null 表示连接或读取未完成，不等于空作用域；首页此时不过滤宿主。 */
@Stable
class ScopeState internal constructor() {
    var packages: Set<String>? by mutableStateOf(null)
        private set

    private var token by mutableIntStateOf(0)

    fun reload() {
        token++
    }

    internal suspend fun awaitRequests(bridge: FrameworkBridge) {
        snapshotFlow { token }.collect {
            packages = bridge.scope().getOrNull()?.toSet()
        }
    }
}

@Composable
fun rememberScopeState(bridge: FrameworkBridge): ScopeState {
    val state = remember { ScopeState() }
    LaunchedEffect(state, bridge) { state.awaitRequests(bridge) }
    return state
}
