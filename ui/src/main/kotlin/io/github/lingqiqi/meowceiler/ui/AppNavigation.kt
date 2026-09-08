package io.github.lingqiqi.meowceiler.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * 返回栈的增删。栈底永远是外壳，其余是详情页。
 *
 * 单独拿出来是为了给页面一份稳定的回调：直接在组合里写 lambda 的话，每趟重组都是新实例，
 * 拿着它的页面就跳不过去，一路重组到底。
 */
@Stable
class AppNavigation(private val backStack: SnapshotStateList<Route>) {
    /**
     * 入栈。
     *
     * 栈里已经有这一页就回到它，而不是再压一份：`MeowNavHost` 用页面对象本身作身份，
     * 同一个对象出现两次会在 reconcile 时报错。
     */
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

    /** 清空详情，只留外壳。宽屏下就是把右栏收回到「请选择」。 */
    fun resetDetail() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** 从外壳直接打开一页：先清掉旧详情，免得宽屏下右栏叠着上一次的页面。 */
    fun openRoot(route: Route) {
        resetDetail()
        push(route)
    }
}
