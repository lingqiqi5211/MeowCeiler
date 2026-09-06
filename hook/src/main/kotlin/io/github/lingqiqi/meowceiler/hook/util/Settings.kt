package io.github.lingqiqi.meowceiler.hook.util

import io.github.libxposed.api.XposedModule
import io.github.lingqiqi.meowceiler.shared.HookLog
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi5211.meowui.core.preference.PreferenceConnectionState
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey
import io.github.lingqiqi5211.meowui.libxposed.XposedModulePreferenceStore
import io.github.lingqiqi5211.meowui.libxposed.createPreferenceStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive

object Settings {
    private var store: XposedModulePreferenceStore? = null

    /**
     * 不要写成 `by lazy` —— lazy 只初始化一次，[close] 取消后拿到的还是那个已取消的 scope，
     * launch 立即返回，DynamicHooker 在热重载之后会静默失效。
     */
    var scope: CoroutineScope = newScope()
        private set

    /** 协程里漏出来的异常不能带崩宿主，记日志了事。 */
    private fun newScope() = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            MLog.e(HookLog.FrameworkTag, "coroutine failed", t)
        },
    )

    fun init(module: XposedModule) {
        store = module.createPreferenceStore(Preferences.NAME)
        if (!scope.isActive) scope = newScope()
    }

    /** remote 不可用时回落 key 的默认值。 */
    fun <T : Any> read(key: PreferenceKey<T>): T = requireStore().read(key)

    fun <T : Any> observe(key: PreferenceKey<T>): Flow<T> = requireStore().observe(key)

    /** remote 是否真的通了。[read] 不通时回落默认值，安全模式必须能区分「读到空」和「没读到」。 */
    fun isConnected(): Boolean =
        store?.connectionState?.value == PreferenceConnectionState.Connected

    /** 安全模式记录要从宿主进程写回，所以 hook 侧也需要写入口。 */
    suspend fun <T : Any> write(key: PreferenceKey<T>, value: T) {
        requireStore().write(key, value)
    }

    /** 先停协程再关 store：关 store 会推一次变更，收集者若还活着就会拿着空 store 去读。 */
    fun close() {
        scope.cancel()
        store?.close()
        store = null
    }

    private fun requireStore(): XposedModulePreferenceStore =
        checkNotNull(store) { "Settings.init() has not been called in this process" }
}
