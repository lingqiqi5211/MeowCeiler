package io.github.lingqiqi.meowceiler.hook.util

import io.github.libxposed.api.XposedModule
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi5211.meowui.core.preference.PreferenceConnectionState
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey
import io.github.lingqiqi5211.meowui.libxposed.XposedModulePreferenceStore
import io.github.lingqiqi5211.meowui.libxposed.createPreferenceStore
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

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    fun close() {
        store?.close()
        store = null
        scope.cancel()
    }

    private fun requireStore(): XposedModulePreferenceStore =
        checkNotNull(store) { "Settings.init() has not been called in this process" }
}
