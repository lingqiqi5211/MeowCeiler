package io.github.lingqiqi.meowceiler.hook.base

import io.github.lingqiqi.meowceiler.hook.util.MLog
import io.github.lingqiqi.meowceiler.shared.HookEventKind
import io.github.lingqiqi.meowceiler.shared.HookLog
import io.github.lingqiqi.meowceiler.hook.util.Settings
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/** [switch] 为 null 表示无开关，恒随父级。 */
sealed class BaseHooker(private val switch: PreferenceKey<Boolean>? = null) {
    val id: String get() = switch?.name ?: this::class.java.simpleName

    /** 有开关的才是「功能」。无开关的 scope 归框架级，只在出错时上报，不进日志页的功能清单。 */
    private val isFeature: Boolean get() = switch != null

    private val logTag: String get() = if (isFeature) id else HookLog.FrameworkTag

    private fun describe(what: String) = if (isFeature) what else "$id: $what"

    private val children = CopyOnWriteArraySet<BaseHooker>()

    private var installed = false

    @Volatile
    private var selfEnabled = true

    @Volatile
    private var parentEnabled = false

    private val effectiveEnabled: Boolean get() = selfEnabled && parentEnabled

    /** [DynamicHooker] 的回调自己读这个决定放行还是返回；hook 装了就不摘，开关只是这一位。 */
    protected val enabled: Boolean get() = effectiveEnabled

    /** 开关之外的启用条件，与开关自动 AND。 */
    open val extraCondition: Boolean get() = true

    /** 只在需要 attach 子 hooker 或提前解析类时覆写。 */
    open fun onInit() {}

    /** 仅在「自己开 && 父级开」时执行一次。 */
    open fun onHook() {}

    /** 开关由框架在这里统一读，所以子类永远不需要调 super。 */
    internal fun performInit() {
        runCatching { onInit() }
            .onFailure { MLog.event(HookEventKind.InitFailed, 'E', logTag, describe("onInit failed"), it) }
        applySwitch()
    }

    private fun applySwitch() {
        when {
            switch == null -> updateSelfState(extraCondition)

            this is DynamicHooker -> Settings.scope.launch {
                Settings.observe(switch).collectLatest { enabled ->
                    updateSelfState(enabled && extraCondition)
                }
            }

            else -> updateSelfState(Settings.read(switch) && extraCondition)
        }
    }

    @Synchronized
    fun updateSelfState(enabled: Boolean) {
        val previous = effectiveEnabled
        selfEnabled = enabled
        applyStateChange(previous)
    }

    @Synchronized
    internal fun updateParentState(enabled: Boolean) {
        val previous = effectiveEnabled
        parentEnabled = enabled
        applyStateChange(previous)
    }

    private fun applyStateChange(previous: Boolean) {
        val shouldInstall = if (this is DynamicHooker) parentEnabled && extraCondition else effectiveEnabled
        if (!installed && shouldInstall) {
            runCatching { onHook() }
                .onSuccess {
                    installed = true
                    if (isFeature) MLog.event(HookEventKind.Installed, 'I', id, "installed")
                }
                .onFailure {
                    MLog.event(HookEventKind.HookFailed, 'E', logTag, describe("onHook failed"), it)
                }
        }

        val current = effectiveEnabled
        if (previous == current) return
        children.forEach { it.updateParentState(current) }
    }

    fun attach(child: BaseHooker) {
        if (!children.add(child)) return
        child.performInit()
        child.updateParentState(effectiveEnabled)
    }
}

/** 开关只读一次，关着就不装。改开关要重启宿主。 */
abstract class StaticHooker(switch: PreferenceKey<Boolean>? = null) : BaseHooker(switch)

/** 免重启开关：订阅开关，hook 照装，回调开头自己读 [enabled]。改 View、静态字段、监听器的不适合。 */
abstract class DynamicHooker(switch: PreferenceKey<Boolean>? = null) : BaseHooker(switch)
