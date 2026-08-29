package io.github.lingqiqi.meowceiler.hook.base

import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi.meowceiler.hook.util.MLog
import io.github.lingqiqi.meowceiler.shared.HookEventKind
import io.github.lingqiqi.meowceiler.shared.HookLog
import io.github.lingqiqi.meowceiler.hook.util.SafeMode
import io.github.lingqiqi.meowceiler.hook.util.Settings
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookFactory
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArraySet

/** [switch] 为 null 表示无开关，恒随父级。 */
sealed class BaseHooker(private val switch: PreferenceKey<Boolean>? = null) {

    val id: String get() = switch?.name ?: this::class.java.simpleName

    /**
     * 有开关的才是「功能」。scope 与基础 hooker 没有开关，它们不该出现在日志页的功能清单里。
     *
     * 日志上报因此分两档：功能用自己的 id 当 tag；无开关的归框架级，名字写进消息里，
     * 而且**只在出错时才报** —— scope 装上是常态，报了只是噪音。
     */
    private val isFeature: Boolean get() = switch != null

    private val logTag: String get() = if (isFeature) id else HookLog.FrameworkTag

    private fun describe(what: String) = if (isFeature) what else "$id: $what"

    private val handles = CopyOnWriteArraySet<XposedInterface.HookHandle>()
    private val children = CopyOnWriteArraySet<BaseHooker>()

    private var selfEnabled = true
    private var parentEnabled = false

    private val effectiveEnabled: Boolean get() = selfEnabled && parentEnabled

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
        val current = effectiveEnabled
        if (previous == current) return

        if (current) {
            if (handles.isEmpty()) {
                runCatching { onHook() }
                    .onSuccess { if (isFeature) MLog.event(HookEventKind.Installed, 'I', id, "installed") }
                    .onFailure {
                        MLog.event(HookEventKind.HookFailed, 'E', logTag, describe("onHook failed"), it)
                    }
            }
        } else {
            when (this) {
                is StaticHooker -> MLog.d("$id: static hooker, unhook skipped")
                is DynamicHooker -> {
                    handles.forEach { it.unhook() }
                    handles.clear()
                }
            }
        }

        children.forEach { it.updateParentState(current) }
    }

    fun attach(child: BaseHooker) {
        if (!children.add(child)) return
        child.performInit()
        child.updateParentState(effectiveEnabled)
    }

    /** 走这个装 hook，handle 才会被登记，unhook 与热重载清理才不用各功能自己管。 */
    protected fun Method.hookManaged(block: HookFactory.() -> Unit) {
        handles += createHook { block() }
    }

    /**
     * 带保护的 before / after。功能一律走这两个，别直接用 [hookManaged] 写裸回调。
     *
     * 两件事是结构性的、不靠各功能自觉：回调里抛出的异常不会漏进宿主的调用栈，
     * 且会记到 [SafeMode] 上 —— 同一宿主连续出错到阈值就把它整个停掉。
     * 停掉之后这里直接不执行回调，所以立刻生效，不用等重启宿主（静态 hook 也摘不掉）。
     */
    protected fun Method.hookBefore(action: (HookParam) -> Unit) = hookManaged {
        before { param -> guarded { action(param) } }
    }

    protected fun Method.hookAfter(action: (HookParam) -> Unit) = hookManaged {
        after { param -> guarded { action(param) } }
    }

    private inline fun guarded(action: () -> Unit) {
        if (SafeMode.isBlocked(EzXposed.packageName)) return
        try {
            action()
        } catch (t: Throwable) {
            // 这里以前漏了 id，日志里只有一句「: hook callback failed」，看不出是哪个功能。
            MLog.event(HookEventKind.CallbackFailed, 'E', logTag, describe("hook callback failed"), t)
            SafeMode.recordFailure(t)
        }
    }
}

/** 装了不摘，关掉需要重启宿主。 */
abstract class StaticHooker(switch: PreferenceKey<Boolean>? = null) : BaseHooker(switch)

/**
 * 可运行时摘除，配合 observe 实现免重启开关。
 *
 * 要求 [onHook] 里装的东西能干净摘除：纯方法 hook 可以；
 * 注册了监听器、替换了 View、改了静态字段的摘了也回不去。
 */
abstract class DynamicHooker(switch: PreferenceKey<Boolean>? = null) : BaseHooker(switch)
