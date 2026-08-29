package io.github.lingqiqi.meowceiler

import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.lingqiqi.meowceiler.ui.settings.FrameworkBridge
import io.github.lingqiqi5211.meowui.libxposed.MeowXposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * [FrameworkBridge] 的真身，跑在模块自己的进程里。
 *
 * 服务从 [MeowXposedService] 取，**不要**自己去 XposedServiceHelper.registerListener ——
 * 那个监听位只有一个且是覆盖式的，自己注册会把 MeowUI 的顶掉，远程偏好从此连不上，
 * 表现是设置页里所有绑定偏好的行整片变灰。这个坑踩过一次。
 *
 * 服务是异步绑上来的，没绑好时所有操作直接失败而不是排队等 —— 点了没反应比报错更难查。
 */
object XposedFrameworkBridge : FrameworkBridge {

    /** 重载失败会拖垮整个系统的进程，一律不碰。 */
    private val ProtectedProcesses = setOf("system_server", "android")

    private fun require(): XposedService =
        MeowXposedService.current ?: error("Xposed 框架服务未连接")

    override suspend fun hookedProcesses(): Result<List<String>> = call {
        require().runningTargets.map { it.processName }
    }

    /**
     * 只重载**确实过期**的进程。
     *
     * 曾经写成无差别重载所有已注入进程，点一下把设备搞成了软重启。两条教训：
     * 已经是最新的进程重载它毫无意义，只是白白冒一次风险；系统进程被重载失败会直接拖垮
     * system_server。所以先按状态筛，再排除系统进程。
     *
     * 正常情况下这里通常无事可做 —— module.prop 里 autoHotReload=true，模块更新后框架
     * 自己就重载了。这个入口是给自动重载没赶上的场合兜底的。
     */
    override suspend fun hotReload(): Result<Int> = call {
        val stale = require().runningTargets.filter {
            it.state == HookedTarget.State.STALE && it.processName !in ProtectedProcesses
        }
        if (stale.isEmpty()) return@call 0
        stale.count { reloadOne(it) }
    }

    /** 逐个等结果而不是一起发：一起发拿不到是哪个进程失败了。 */
    private suspend fun reloadOne(target: HookedTarget): Boolean =
        suspendCancellableCoroutine { continuation ->
            runCatching {
                require().hotReloadModule(target, null) { _, result ->
                    continuation.resume(result.status() == HotReloadResult.Status.SUCCEEDED)
                }
            }.onFailure { continuation.resume(false) }
        }

    override suspend fun scope(): Result<List<String>> = call {
        require().scope.orEmpty()
    }

    override suspend fun setInScope(packageName: String, inScope: Boolean): Result<Unit> = call {
        val service = require()
        if (inScope) {
            requestScope(service, listOf(packageName))
        } else {
            service.removeScope(listOf(packageName))
        }
    }

    /** 申请要用户在管理器里点同意；没批准的当失败报出去，别假装成功。 */
    private suspend fun requestScope(service: XposedService, packages: List<String>) =
        suspendCancellableCoroutine { continuation ->
            service.requestScope(
                packages,
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: List<String>) {
                        val missing = packages - approved.toSet()
                        if (missing.isEmpty()) {
                            continuation.resume(Unit)
                        } else {
                            continuation.resumeWith(
                                Result.failure(
                                    IllegalStateException("未获授权：${missing.joinToString()}"),
                                ),
                            )
                        }
                    }

                    override fun onScopeRequestFailed(message: String) {
                        continuation.resumeWith(Result.failure(IllegalStateException(message)))
                    }
                },
            )
        }

    /** 所有调用都可能抛 RemoteException / ServiceException，统一收进 Result。 */
    private suspend fun <T> call(block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching { block() } }
}
