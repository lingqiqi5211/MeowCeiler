package io.github.lingqiqi.meowceiler.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 框架作用域的当前内容，首页与作用域页共用一份。
 *
 * [packages] 为 null 表示**还不知道**（框架服务没连上或调用失败），不等于「作用域是空的」。
 * 这个区别很重要：首页据此过滤时，不知道就得放行全部，否则服务一抽风用户的宿主列表会整个空掉。
 */
class ScopeState(
    val packages: Set<String>?,
    val reload: () -> Unit,
)

@Composable
fun rememberScopeState(bridge: FrameworkBridge): ScopeState {
    var token by remember { mutableIntStateOf(0) }
    var packages by remember { mutableStateOf<Set<String>?>(null) }

    LaunchedEffect(token) {
        packages = bridge.scope().getOrNull()?.toSet()
    }

    return ScopeState(packages) { token++ }
}
