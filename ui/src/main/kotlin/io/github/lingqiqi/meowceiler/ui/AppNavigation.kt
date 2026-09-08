package io.github.lingqiqi.meowceiler.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshots.SnapshotStateList

/** 返回栈以 [Route.Shell] 为根；回调实例由持有者复用。 */
@Stable
class AppNavigation(private val backStack: SnapshotStateList<Route>) {
    /** MeowNavHost 以页面对象为身份；重复打开时返回已有页面，避免重复键。 */
    fun push(route: Route) {
        val existing = backStack.indexOf(route)
        if (existing < 0) {
            backStack.add(route)
            return
        }
        while (backStack.lastIndex > existing) backStack.removeAt(backStack.lastIndex)
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun resetDetail() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** 切换主页面入口时替换整个详情栈。 */
    fun openRoot(route: Route) {
        resetDetail()
        push(route)
    }
}
