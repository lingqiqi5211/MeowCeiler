package io.github.lingqiqi.meowceiler.hook.utils.systemui

import android.content.ComponentName
import android.content.ContextWrapper
import io.github.lingqiqi.meowceiler.hook.util.MLog
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.findField
import io.github.lingqiqi5211.ezhooktool.core.toClass
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 系统界面的插件各自带类加载器，宿主的类加载器里找不到插件的类。
 *
 * 插件建 Context 的那一刻是唯一能拿到它的时机，早于此的任何查找都会失败。
 * 接点取自 Hyper5GSwitch 与 HyperCeiler。
 */
object SystemUiPlugins {
    private const val FactoryClass =
        "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory"

    private val listeners = mutableMapOf<String, CopyOnWriteArrayList<Listener>>()
    private var installed = false

    private class Listener(val tag: String, val action: (ClassLoader) -> Unit)

    /** [component] 是插件实现类名，如 `miui.systemui.notification.FocusNotificationPluginImpl`。 */
    @Synchronized
    fun onLoaded(component: String, tag: String, action: (ClassLoader) -> Unit) {
        listeners.getOrPut(component) { CopyOnWriteArrayList() }.add(Listener(tag, action))
        if (installed) return
        install()
        installed = true
    }

    private fun install() {
        val factory = FactoryClass.toClass()
        // 字段名各代不同，按类型找只会命中这一个。
        val componentField = findField(factory) { type(ComponentName::class.java) }
        factory.findMethod { name("createPluginContext") }.createHook {
            after { param ->
                // 这里抛出去会被算成宿主的一次失败，攒够了模块就进安全模式。
                runCatching {
                    val component = componentField.get(param.thisObjectOrNull) as? ComponentName
                        ?: return@runCatching
                    val classLoader = (param.result as? ContextWrapper)?.classLoader
                        ?: return@runCatching
                    listeners[component.className]?.forEach { listener ->
                        runCatching { listener.action(classLoader) }
                            .onSuccess { MLog.d(listener.tag, "plugin ${component.className} ready") }
                            .onFailure { MLog.w(listener.tag, "plugin ${component.className} failed", it) }
                    }
                }.onFailure { MLog.w("plugin context hook failed", it) }
            }
        }
    }
}
