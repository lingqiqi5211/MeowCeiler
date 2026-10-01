package io.github.lingqiqi.meowceiler.hook.utils.settings

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.lingqiqi.meowceiler.hook.util.dimenPxByName
import io.github.lingqiqi.meowceiler.hook.util.idOf
import io.github.lingqiqi.meowceiler.hook.util.setPaddingVertical
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.callMethodOrNull
import io.github.lingqiqi5211.ezhooktool.core.findField
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.toClass
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/**
 * 往系统设置的偏好页里插条目。
 *
 * 宿主的 androidx / miuix Preference 只存在于设置进程，模块编译期没有这些类型，全部走反射。
 */
object HostPreferences {
    const val Plain = "androidx.preference.Preference"
    const val SeekBar = "androidx.preference.SeekBarPreference"
    const val DropDown = "miuix.preference.DropDownPreference"
    const val Category = "miuix.preference.PreferenceCategory"

    private const val ChangeListener = "androidx.preference.Preference\$OnPreferenceChangeListener"
    private const val ClickListener = "androidx.preference.Preference\$OnPreferenceClickListener"
    private const val SliderLayout = "miuix_preference_widget_seekbar"

    /** 数值位与宿主的滑条紧挨着时太挤，留一点间距。 */
    private const val ValueSpacingDp = 10

    /** 数值位给固定宽度，文字长短变化时滑条不会跟着伸缩。宿主的这条 dimen 就是干这个用的。 */
    private const val ValueWidthDimen = "preference_seekbar_value_minWidth"
    private const val ValueWidthDp = 36

    /** 只认自己造的那几个滑条：绑定回调是宿主所有滑条共用的。值是当前值的显示文本，null 表示照原样显示数字。 */
    private val sliders = WeakHashMap<Any, (Int) -> String?>()
    private var valueHooked = false

    /** 有的 Preference 只留了带 AttributeSet 的构造，属性传 null 即可，样式仍走主题默认值。 */
    fun create(context: Context, className: String): Any {
        val type = className.toClass()
        return runCatching { type.getConstructor(Context::class.java).newInstance(context) }
            .getOrElse {
                type.getConstructor(Context::class.java, AttributeSet::class.java)
                    .newInstance(context, null)
            }
    }

    /**
     * 滑条行：miuix 滑条与当前值同一行。
     *
     * 宿主的 miuix 里没有滑条 Preference，只留了这份行布局，控件 id 与 androidx 的滑条对得上。
     * [label] 给某些档位换个说法，返回 null 就照原样显示数字。
     */
    fun createSlider(
        context: Context,
        range: IntRange,
        label: (Int) -> String? = { null },
    ): Any {
        val slider = create(context, SeekBar)
        slider.callMethod("setMin", range.first)
        slider.callMethod("setMax", range.last)
        context.idOf(SliderLayout, "layout").takeIf { it != 0 }
            ?.let { slider.callMethod("setLayoutResource", it) }
        // 显示数值、连续回调、可键盘调节都只有 xml 属性，没有 setter。
        for (field in listOf("mShowSeekBarValue", "mUpdatesContinuously", "mAdjustable")) {
            findField(slider.javaClass) { name(field) }.setBoolean(slider, true)
        }
        sliders[slider] = label
        installValueHooks(context)
        return slider
    }

    /** miuix 那份布局把数值位的宽度写成 0，绑定之后给它固定宽度才看得见当前值，滑条宽度也才不会抖。 */
    private fun installValueHooks(context: Context) {
        if (valueHooked) return
        valueHooked = true
        val slider = SeekBar.toClass()
        val valueId = context.idOf("seekbar_value").takeIf { it != 0 } ?: return
        val density = context.resources.displayMetrics.density
        val spacing = (ValueSpacingDp * density).toInt()
        val valueWidth = context.dimenPxByName(ValueWidthDimen) ?: (ValueWidthDp * density).toInt()
        val barId = context.idOf("seekbar")
        slider.findMethod { name("onBindViewHolder"); paramCount(1) }.createHook {
            after { param ->
                val preference = param.thisObjectOrNull ?: return@after
                if (preference !in sliders) return@after
                val holder = param.args.getOrNull(0) ?: return@after
                val label = holder.callMethodOrNull("findViewById", valueId) as? TextView ?: return@after
                label.layoutParams = label.layoutParams.apply {
                    width = valueWidth
                    (this as? ViewGroup.MarginLayoutParams)?.marginStart = spacing
                }
                // 没有标题时布局仍旧按「标题在上」留了上边距，收掉才不至于空一截。
                val title = preference.callMethodOrNull("getTitle") as? CharSequence
                if (!title.isNullOrBlank()) return@after
                val bar = holder.callMethodOrNull("findViewById", barId) as? View ?: return@after
                (bar.parent as? View)?.let { it.setPaddingVertical(0, it.paddingBottom) }
            }
        }
        slider.findMethod { name("updateLabelValue"); paramCount(1) }.createHook {
            after { param ->
                val preference = param.thisObjectOrNull ?: return@after
                val value = param.args.getOrNull(0) as? Int ?: return@after
                val text = sliders[preference]?.invoke(value) ?: return@after
                val label = findField(preference.javaClass) { name("mSeekBarValueTextView") }
                    .get(preference) as? TextView
                label?.text = text
            }
        }
    }

    fun sliderValue(slider: Any): Int =
        findField(slider.javaClass) { name("mSeekBarValue") }.getInt(slider)

    /**
     * 把几条并成一张卡。
     *
     * 页面根层的条目各自成卡，只有同一个分组里的相邻条目才连起来，所以要连就得先有个分组；
     * 分组不给标题，看上去就只是一张卡。
     */
    fun group(context: Context, screen: Any, order: Int, key: String, members: List<Any>): Any {
        val category = create(context, Category)
        category.callMethod("setKey", key)
        category.callMethod("setOrder", order)
        screen.callMethod("addPreference", category)
        members.forEachIndexed { index, member ->
            screen.callMethod("removePreference", member)
            member.callMethod("setOrder", index)
            category.callMethod("addPreference", member)
        }
        return category
    }

    fun screen(fragment: Any): Any? = fragment.callMethodOrNull("getPreferenceScreen")

    fun find(fragment: Any, key: String): Any? = fragment.callMethodOrNull("findPreference", key)

    /**
     * 换掉变更监听器，返回宿主原来那个。
     *
     * 宿主的监听器负责真正写入，接管的一方要么自己写、要么把值转发给 [dispatchChange]。
     */
    fun onChange(preference: Any, handler: (Any?) -> Boolean): Any? {
        val previous = preference.callMethodOrNull("getOnPreferenceChangeListener")
        val listener = proxy(ChangeListener.toClass()) { args -> handler(args.getOrNull(1)) }
        preference.callMethod("setOnPreferenceChangeListener", listener)
        return previous
    }

    fun dispatchChange(listener: Any?, preference: Any, value: Any?): Boolean =
        listener?.callMethodOrNull("onPreferenceChange", preference, value) as? Boolean ?: true

    fun onClick(preference: Any, handler: () -> Unit) {
        val listener = proxy(ClickListener.toClass()) { handler(); true }
        preference.callMethod("setOnPreferenceClickListener", listener)
    }

    private fun proxy(type: Class<*>, handler: (Array<out Any?>) -> Boolean): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            val arguments = args ?: emptyArray()
            if (method.declaringClass != Any::class.java) return@newProxyInstance handler(arguments)
            when (method.name) {
                "equals" -> proxy === arguments.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                else -> "${type.name}@${Integer.toHexString(System.identityHashCode(proxy))}"
            }
        }
}
