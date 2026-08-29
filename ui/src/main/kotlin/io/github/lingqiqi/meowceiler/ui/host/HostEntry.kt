package io.github.lingqiqi.meowceiler.ui.host

import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi.meowceiler.ui.Route

/**
 * 首页的路由表：一个宿主一条。
 *
 * 图标与名称不写死，运行时从 PackageManager 取实际安装的应用；取不到就整条隐藏
 * （宿主没装 / 被停用时不该出现在列表里）。
 */
enum class HostEntry(val packageName: String, val route: Route) {
    SystemUi(Scope.SystemUi, Route.SystemUi),
}
