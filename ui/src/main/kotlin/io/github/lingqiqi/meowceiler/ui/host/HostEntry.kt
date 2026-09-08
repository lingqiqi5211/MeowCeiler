package io.github.lingqiqi.meowceiler.ui.host

import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi.meowceiler.ui.Route

/** 宿主名称和图标由 PackageManager 提供；未安装或停用的宿主不显示。 */
enum class HostEntry(val packageName: String, val route: Route) {
    SystemUi(Scope.SystemUi, Route.SystemUi),
}
