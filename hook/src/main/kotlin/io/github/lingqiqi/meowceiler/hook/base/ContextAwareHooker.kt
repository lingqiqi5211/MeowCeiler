package io.github.lingqiqi.meowceiler.hook.base

import android.content.Context
import android.content.res.Resources
import io.github.lingqiqi.meowceiler.hook.util.idOf
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey

/** 需要宿主 Context / Resources 的 hooker，context 来自 [EzXposed.runOnApplicationAttach]。 */
abstract class ContextAwareHooker(switch: PreferenceKey<Boolean>? = null) : StaticHooker(switch) {
    abstract val targetPackage: String

    private var ready = false

    final override fun onHook() {
        EzXposed.runOnApplicationAttach { context ->
            if (ready) return@runOnApplicationAttach
            ready = true
            ContextScope(context, targetPackage).onReady()
        }
    }

    abstract fun ContextScope.onReady()
}

/** `getIdentifier` 是字符串查表，按约定在 onReady 里一次性解析完，回调里只用 Int。 */
class ContextScope(val context: Context, private val packageName: String) {
    val res: Resources get() = context.resources

    fun String.toId(): Int = res.idOf(this, "id", packageName)

    fun String.toDrawableId(): Int = res.idOf(this, "drawable", packageName)

    fun String.toStringId(): Int = res.idOf(this, "string", packageName)

    fun String.toDimenId(): Int = res.idOf(this, "dimen", packageName)

    fun String.toLayoutId(): Int = res.idOf(this, "layout", packageName)

    fun Int.dp2px(): Int = (this * res.displayMetrics.density + 0.5f).toInt()
}
