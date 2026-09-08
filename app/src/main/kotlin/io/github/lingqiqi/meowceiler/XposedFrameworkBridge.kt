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

/** 复用 [MeowXposedService] 的连接，避免重复注册监听覆盖远程偏好监听。 */
object XposedFrameworkBridge : FrameworkBridge {
    /** 不热重载系统关键进程，避免系统重启。 */
    private val ProtectedProcesses = setOf("system_server", "android")

    private fun require(): XposedService =
        MeowXposedService.current ?: error("Xposed 框架服务未连接")

    override suspend fun hookedProcesses(): Result<List<String>> = call {
        require().runningTargets.map { it.processName }
    }

    override suspend fun hotReload(): Result<Int> = call {
        val stale = require().runningTargets.filter {
            it.state == HookedTarget.State.STALE && it.processName !in ProtectedProcesses
        }
        if (stale.isEmpty()) return@call 0
        stale.count { reloadOne(it) }
    }

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

    /** 作用域申请须经管理器批准；部分批准也返回失败。 */
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

    private suspend fun <T> call(block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching { block() } }
}
