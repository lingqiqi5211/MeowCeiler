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
 * [FrameworkBridge] 的实现。服务从 [MeowXposedService] 取：自己 registerListener 会顶掉 MeowUI 的监听，远程偏好会断。
 * 服务没绑好时直接失败，不排队。
 */
object XposedFrameworkBridge : FrameworkBridge {
    /** 重载失败会拖垮整个系统的进程，一律不碰。 */
    private val ProtectedProcesses = setOf("system_server", "android")

    private fun require(): XposedService =
        MeowXposedService.current ?: error("Xposed 框架服务未连接")

    override suspend fun hookedProcesses(): Result<List<String>> = call {
        require().runningTargets.map { it.processName }
    }

    /** 只重载 STALE 的进程并排除系统进程：无差别重载曾把设备搞成软重启。autoHotReload 开着时这里通常无事可做。 */
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
