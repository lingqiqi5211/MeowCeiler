package io.github.lingqiqi.meowceiler.hook.rules.systemui.statusbar

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.FontVariationAxis
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import io.github.lingqiqi.meowceiler.hook.base.Feature
import io.github.lingqiqi.meowceiler.hook.base.StaticHooker
import io.github.lingqiqi.meowceiler.hook.util.Settings
import io.github.lingqiqi.meowceiler.hook.util.findViewByName
import io.github.lingqiqi.meowceiler.hook.util.idOf
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.shared.isPadDevice
import io.github.lingqiqi5211.ezhooktool.core.findAllMethods
import io.github.lingqiqi5211.ezhooktool.core.findConstructor
import io.github.lingqiqi5211.ezhooktool.core.findField
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.toClass
import io.github.lingqiqi5211.ezhooktool.core.toClassOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.EzResources
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

@Feature(
    name = "时钟指示器",
    since = "2026-09-05",
    target = "SystemUI 16.03.251211.r / 17.03.260226.r",
    updated = "2026-09-06",
)
object StatusBarClock : StaticHooker(Preferences.SystemUi.Clock) {

    private class Variant(
        val format: String,
        val bold: Boolean,
        val leftDp: Float,
        val rightDp: Float,
        val offset: Float,
        val align: Int = 0,
        val spacing: Float = 1f,
        val fixedWidthDp: Float = 0f,
        val sizeSp: Float = 0f,
        val hidden: Boolean = false,
    ) {
        val hasSeconds = 's' in format.replace(Regex("'[^']*'"), "")
        val twoLines = '\n' in format
    }

    private class Original(view: TextView) {
        private val typeface: Typeface? = view.typeface
        private var textSize = view.textSize
        private var appliedTextSize: Float? = null
        private val padStart = view.paddingStart
        private val padTop = view.paddingTop
        private val padEnd = view.paddingEnd
        private val padBottom = view.paddingBottom
        private val minWidth = view.minWidth
        private val maxWidth = view.maxWidth
        private val singleLine = view.isSingleLine
        private val maxLines = view.maxLines
        private val alignment = view.textAlignment
        private val spacingExtra = view.lineSpacingExtra
        private val spacingMultiplier = view.lineSpacingMultiplier
        private var visibility = view.visibility
        private var appliedVisibility: Int? = null

        fun restore(view: TextView) {
            // 只还原模块修改过的可见性，避免热重载显示宿主已隐藏的时钟。
            if (appliedVisibility != null) {
                view.visibility = visibility
                appliedVisibility = null
            }
            view.typeface = typeface
            if (appliedTextSize != null) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                appliedTextSize = null
            }
            view.setPaddingRelative(padStart, padTop, padEnd, padBottom)
            if (minWidth >= 0) view.minWidth = minWidth
            if (maxWidth >= 0) view.maxWidth = maxWidth
            if (view.isSingleLine != singleLine) view.isSingleLine = singleLine
            view.maxLines = maxLines
            view.textAlignment = alignment
            view.setLineSpacing(spacingExtra, spacingMultiplier)
        }

        fun setHidden(view: TextView, hidden: Boolean) {
            if (!hidden) {
                if (appliedVisibility == null) return
                view.visibility = visibility
                appliedVisibility = null
                return
            }
            if (appliedVisibility == null || view.visibility != appliedVisibility) visibility = view.visibility
            view.visibility = View.GONE
            appliedVisibility = View.GONE
        }

        /** null 保留宿主左右内边距，下内边距始终使用宿主值。 */
        fun applyPadding(view: TextView, startPx: Int?, endPx: Int?, top: Int) {
            view.setPaddingRelative(startPx ?: padStart, top, endPx ?: padEnd, padBottom)
        }

        fun applySize(view: TextView, sizeSp: Float) {
            if (sizeSp <= 0f || !sizeSp.isFinite()) return
            if (appliedTextSize == null || view.textSize != appliedTextSize) textSize = view.textSize
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            appliedTextSize = view.textSize
        }
    }

    private fun <T : Any> pref(key: PreferenceKey<T>): T = Settings.read(key)

    private fun firstLine(key: PreferenceKey<String>): String = pref(key).lineSequence().first().trim()

    private fun loadVariants(): Map<String, Variant> {
        val statusBarFormat = firstLine(Preferences.SystemUi.ClockFormatStatusBar).ifEmpty { "HH:mm" }
        val miniFormat = firstLine(Preferences.SystemUi.ClockFormatMini)
        val secondLine = miniFormat.ifEmpty { "M/d E" }
        val statusBar = when (pref(Preferences.SystemUi.ClockStyle)) {
            1 -> "$statusBarFormat\n$secondLine"
            2 -> "$secondLine\n$statusBarFormat"
            else -> statusBarFormat
        }
        val bigFormat = if (pref(Preferences.SystemUi.ClockNoSync)) {
            firstLine(Preferences.SystemUi.ClockFormatBig).ifEmpty { "HH:mm" }
        } else {
            statusBarFormat
        }
        val mini = Variant(
            miniFormat,
            pref(Preferences.SystemUi.ClockBoldMini),
            pref(Preferences.SystemUi.ClockLeftMini),
            pref(Preferences.SystemUi.ClockRightMini),
            pref(Preferences.SystemUi.ClockOffsetMini),
            sizeSp = pref(Preferences.SystemUi.ClockSizeMini),
        )
        return mapOf(
            "clock" to Variant(
                statusBar,
                pref(Preferences.SystemUi.ClockBoldStatusBar),
                pref(Preferences.SystemUi.ClockLeftStatusBar),
                pref(Preferences.SystemUi.ClockRightStatusBar),
                pref(Preferences.SystemUi.ClockOffsetStatusBar),
                pref(Preferences.SystemUi.ClockAlign),
                pref(Preferences.SystemUi.ClockSpacing),
                pref(Preferences.SystemUi.ClockFixedWidth),
                sizeSp = pref(Preferences.SystemUi.ClockSizeStatusBar),
            ),
            "big_time" to Variant(
                bigFormat,
                pref(Preferences.SystemUi.ClockBoldBig),
                pref(Preferences.SystemUi.ClockLeftBig),
                pref(Preferences.SystemUi.ClockRightBig),
                pref(Preferences.SystemUi.ClockOffsetBig),
                sizeSp = pref(Preferences.SystemUi.ClockSizeBig),
            ),
            "date_time" to mini,
            // Pad 的 horizontal_time 并非手机横屏迷你时钟，不能套用同一配置。
            *(if (isPadDevice) emptyArray() else arrayOf("horizontal_time" to mini)),
            "normal_control_center_date_view" to mini,
            "pad_clock" to Variant(
                firstLine(Preferences.SystemUi.ClockFormatPad),
                pref(Preferences.SystemUi.ClockBoldPad),
                pref(Preferences.SystemUi.ClockLeftPad),
                pref(Preferences.SystemUi.ClockRightPad),
                pref(Preferences.SystemUi.ClockOffsetPad),
                sizeSp = pref(Preferences.SystemUi.ClockSizePad),
                hidden = pref(Preferences.SystemUi.ClockHiddenPad),
            ),
        )
    }

    /** 双排状态栏时钟没法从单行大时钟放大过去，双排时一律不做放大动画。 */
    private fun loadNoShadeAnimation(variants: Map<String, Variant>): Boolean =
        pref(Preferences.SystemUi.ClockNoShadeAnimation) || variants.getValue("clock").twoLines

    private val variantKey = EzResources.fakeResId("meowceiler.clock.variant")
    private val markKey = EzResources.fakeResId("meowceiler.clock.mark")
    private val tickKey = EzResources.fakeResId("meowceiler.clock.tick")
    private val originalKey = EzResources.fakeResId("meowceiler.clock.original")
    private val emptyKey = EzResources.fakeResId("meowceiler.clock.empty")

    @Volatile private var variants: Map<String, Variant> = emptyMap()
    @Volatile private var noShadeAnimation = false
    @Volatile private var marker = Any()
    private val nameById = HashMap<Int, String?>(8)
    private val adopted: MutableSet<TextView> = Collections.newSetFromMap(WeakHashMap())
    private val clockSizes = WeakHashMap<Any, Pair<SizeOverride, SizeOverride>>()

    private class SizeOverride {
        private var original = 0
        private var applied: Int? = null

        fun resolve(current: Int, replacement: Int?): Int {
            // 配置变化时宿主会重读系统尺寸，记住新的默认值，0sp 才能正确还原。
            if (applied == null || current != applied) original = current
            applied = replacement
            return replacement ?: original
        }
    }

    private lateinit var controllerField: Field
    private lateinit var calendarField: Field
    private lateinit var setTimeMethod: Method
    private lateinit var formatMethod: Method
    private lateinit var updateTimeMethod: Method
    private lateinit var callbackOuterField: Field
    private lateinit var headerControllerField: Field
    private lateinit var notificationBigTimeField: Field
    private lateinit var notificationDateTimeField: Field
    private lateinit var bigTimeSizeField: Field
    private lateinit var statusBarClockSizeField: Field
    private var savedStatusBarClockSize: Int? = null
    private var expandController: WeakReference<Any>? = null
    private lateinit var updateTranslationYMethod: Method
    private lateinit var progressField: Field
    private lateinit var notificationCallbackField: Field
    private lateinit var newAppearanceField: Field
    private lateinit var onExpansionChangedMethod: Method
    private lateinit var onAppearanceChangedMethod: Method
    private var statusBar: ShadeStatusBar? = null
    private var setTimeExtraArgs: Array<Any> = emptyArray()

    override fun onHook() {
        val clazz = "com.android.systemui.statusbar.views.MiuiClock".toClass()
        controllerField = findField(clazz) { name("mMiuiStatusBarClockController") }.apply { isAccessible = true }
        calendarField = findField(controllerField.type) { name("mCalendar") }.apply { isAccessible = true }
        val calendarClass = calendarField.type
        setTimeMethod = calendarClass.findAllMethods { name("setTimeInMillis") }
            .filter { it.parameterTypes.firstOrNull() == Long::class.javaPrimitiveType }
            .minByOrNull { it.parameterCount }
            ?: error("setTimeInMillis not found in ${calendarClass.name}")
        formatMethod = calendarClass.findAllMethods { name("format"); paramCount(2) }
            .firstOrNull { it.parameterTypes[0] == Context::class.java && it.returnType == String::class.java }
            ?: error("format(Context, CharSequence) not found in ${calendarClass.name}")
        setTimeExtraArgs = Array(setTimeMethod.parameterCount - 1) { false }
        variants = loadVariants()
        noShadeAnimation = loadNoShadeAnimation(variants)

        clazz.findConstructor { paramCount(3) }.createHook {
            after { param -> (param.thisObjectOrNull as? TextView)?.let(::adopt) }
        }
        updateTimeMethod = clazz.findMethod { name("updateTime") }
        updateTimeMethod.createHook {
            before { param ->
                val view = param.thisObjectOrNull as? TextView ?: return@before
                if (view.getTag(markKey) !== marker) adopt(view)
                val variant = view.getTag(variantKey) as? Variant ?: return@before
                if (variant.hidden) {
                    param.result = null
                    return@before
                }
                if (variant.format.isEmpty()) return@before
                render(view, variant)
                if (variant.hasSeconds && view.getTag(tickKey) == null && view.isAttachedToWindow) startTicker(view, variant)
                param.result = null
            }
        }

        val headerClass = "com.android.systemui.qs.MiuiNotificationHeaderView".toClass()
        headerClass.findAllMethods { paramCount(0) }
            .filter { it.declaringClass == headerClass && it.name.startsWith("updateResources") }
            .ifEmpty { error("updateResources not found in ${headerClass.name}") }
            .forEach { method ->
                method.createHook {
                    after { param ->
                        val header = param.thisObjectOrNull as? View ?: return@after
                        for (name in variants.keys) (header.findViewByName(name) as? TextView)?.let(::restyle)
                    }
                }
            }
        val expandClass = "com.android.systemui.controlcenter.shade.NotificationHeaderExpandController".toClass()
        bigTimeSizeField = findField(expandClass) { name("bigTimeSize") }.apply { isAccessible = true }
        statusBarClockSizeField = findField(expandClass) { name("statusBarClockSize") }.apply { isAccessible = true }
        headerControllerField = findField(expandClass) { name("headerController") }.apply { isAccessible = true }
        updateTranslationYMethod = expandClass.findMethod { name("updateTranslationY"); paramCount(0) }
        updateTranslationYMethod.createHook {
            before { param -> param.thisObjectOrNull?.let(::applyClockSizes) }
        }
        val combinedClass = "com.android.systemui.controlcenter.shade.CombinedHeaderController".toClass()
        notificationBigTimeField = findField(combinedClass) { name("notificationBigTime") }.apply { isAccessible = true }
        notificationDateTimeField = findField(combinedClass) { name("notificationDateTime") }.apply { isAccessible = true }
        expandClass.findMethod { name("updateWeight") }.createHook {
            before { param ->
                if (variants["big_time"]?.bold == true) {
                    val controller = param.thisObjectOrNull ?: return@before
                    val clock = notificationBigTime(controller) ?: return@before
                    if (clock.getTag(markKey) !== marker) adopt(clock)
                    // 已在 style 中设为 701，别让宿主每帧换回普通字体再加粗，反复触发布局。
                    param.result = null
                } else if (noShadeAnimation) {
                    param.args[0] = 1f
                }
            }
        }
        val callbackClass = "${expandClass.name}\$notificationCallback\$1".toClass()
        callbackOuterField = findField(callbackClass) { name("this\$0") }.apply { isAccessible = true }
        newAppearanceField = findField(callbackClass) { name("newAppearance") }.apply { isAccessible = true }
        progressField = findField(expandClass) { name("progress") }.apply { isAccessible = true }
        notificationCallbackField = findField(expandClass) { name("notificationCallback") }.apply { isAccessible = true }
        onExpansionChangedMethod = callbackClass.findMethod { name("onExpansionChanged") }
        onAppearanceChangedMethod = callbackClass.findMethod { name("onAppearanceChanged") }
        onExpansionChangedMethod.createHook {
            before { param ->
                val controller = param.thisObjectOrNull?.let(callbackOuterField::get) ?: return@before
                if (applyClockSizes(controller)) updateTranslationYMethod.invoke(controller)
            }
            after { param ->
                val controller = param.thisObjectOrNull?.let(callbackOuterField::get) ?: return@after
                expandController = WeakReference(controller)
                pinBigTime(controller)
            }
        }
        onAppearanceChangedMethod.createHook {
            after { param ->
                if (!noShadeAnimation) return@after
                val appeared = param.args[0] as Boolean
                statusBar?.setHidden(appeared)
                val controller = param.thisObjectOrNull?.let(callbackOuterField::get) ?: return@after
                followMiniClock(controller)
            }
        }
        "com.android.systemui.statusbar.policy.MiuiStatusBarClockController".toClass()
            .findAllMethods { paramCount(1) }
            .filter { it.name.startsWith("onMiuiThemeChanged") }
            .forEach { method ->
                method.createHook { after { for (view in adopted.toList()) restyle(view) } }
            }

        hideStatusBarWhileExpanded()

        val watched = Preferences.all.filter { it.name.startsWith(Preferences.SystemUi.Clock.name + "_") }
        Settings.scope.launch {
            merge(*watched.map { Settings.observe(it).drop(1) }.toTypedArray()).collect { if (enabled) reload() }
        }
    }

    /**
     * Pad 下拉通知中心时整条状态栏随面板 appearance 一起收起、再一起回来；手机只即时收时钟，给放大动画腾位。
     * 取消动画后照 Pad 办：appearance 变化时把状态栏内容整体渐隐、渐显，与大时钟同一时刻起步。
     * 宿主在面板展开时还会单独把状态栏时钟即时收掉、面板完全消失后才放回，节奏和整体渐隐对不上，
     * 这一次隐藏拦掉；锁屏、灵动岛等场合的隐藏照旧。
     * 状态栏内容视图两代都叫 mStatusBarContent，OS3 挂在 MiuiCollapsedStatusBarFragment，OS4 挂在 HomeStatusBarViewBinderInjector。
     */
    private fun hideStatusBarWhileExpanded() {
        val ownerClass = "com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment".toClassOrNull()
            ?: "com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderInjector".toClassOrNull()
            ?: return
        val bar = ShadeStatusBar(ownerClass)
        ownerClass.findMethod { name("hideClock"); paramCount(1) }.createHook {
            before { param ->
                val owner = param.thisObjectOrNull ?: return@before
                bar.instance = WeakReference(owner)
                if (noShadeAnimation && bar.panelExpanded(owner) && bar.inShade(owner)) param.result = null
            }
        }
        ownerClass.findMethod { name("showClock"); paramCount(1) }.createHook {
            before { param -> param.thisObjectOrNull?.let { bar.instance = WeakReference(it) } }
        }
        statusBar = bar
    }

    private class ShadeStatusBar(clazz: Class<*>) {
        var instance: WeakReference<Any>? = null
        var hidden = false
            private set
        private val content: Field = findField(clazz) { name("mStatusBarContent") }.apply { isAccessible = true }
        private val stateController: Field = clazz.declaredFieldOrNull("mStatusBarStateController")
            ?: findField(clazz) { name("statusBarStateControllerImpl") }.apply { isAccessible = true }
        private val panelExpand: Field? = clazz.declaredFieldOrNull("mNotificationPanelExpand")
        private val panelExpandFlow: Field? = clazz.declaredFieldOrNull("mNotificationPanelExpandFlow")

        fun inShade(owner: Any): Boolean {
            val controller = stateController.get(owner) ?: return false
            return controller.javaClass.getMethod("getState").invoke(controller) == 0
        }

        fun panelExpanded(owner: Any): Boolean {
            panelExpand?.let { return it.getBoolean(owner) }
            val flow = panelExpandFlow?.get(owner) ?: return false
            return flow.javaClass.getMethod("getValue").invoke(flow) == true
        }

        fun setHidden(hidden: Boolean) {
            val owner = instance?.get() ?: return
            if (!inShade(owner)) return
            val view = content.get(owner) as? View ?: return
            this.hidden = hidden
            view.animate().cancel()
            view.animate()
                .alpha(if (hidden) 0f else 1f)
                .setDuration(160)
                .setInterpolator(if (hidden) AlphaOut else AlphaIn)
                .start()
        }

        private companion object {
            val AlphaOut = PathInterpolator(0f, 0f, 0.8f, 1f)
            val AlphaIn = PathInterpolator(0.4f, 0f, 1f, 1f)
        }
    }

    private fun Class<*>.declaredFieldOrNull(name: String): Field? =
        generateSequence(this) { it.superclass }
            .firstNotNullOfOrNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            ?.apply { isAccessible = true }

    /** 设置变了：换一代 marker，让每个时钟在下一次 updateTime 重新认领；已挂上的立刻刷一遍。 */
    private fun reload() {
        val loaded = loadVariants()
        noShadeAnimation = loadNoShadeAnimation(loaded)
        variants = loaded
        marker = Any()
        val view = adopted.firstOrNull() ?: return
        view.post {
            if (!enabled) return@post
            if (!noShadeAnimation) {
                expandController?.get()?.let(::restoreHost)
                statusBar?.takeIf { it.hidden }?.setHidden(false)
            }
            for (clock in adopted.toList()) {
                if (!clock.isAttachedToWindow) continue
                adopt(clock)
                updateTimeMethod.invoke(clock)
            }
            expandController?.get()?.let { controller ->
                if (applyClockSizes(controller)) {
                    updateTranslationYMethod.invoke(controller)
                    onExpansionChangedMethod.invoke(notificationCallbackField.get(controller), progressField.getFloat(controller))
                }
            }
        }
    }

    /** 只替换宿主的字号输入，缩放、位置与显示时序继续使用原实现。 */
    private fun applyClockSizes(controller: Any): Boolean {
        val bigSp = variants["big_time"]?.sizeSp?.takeIf { it.isFinite() && it > 0f } ?: 0f
        val statusSp = variants["clock"]?.sizeSp?.takeIf { it.isFinite() && it > 0f } ?: 0f
        if (bigSp <= 0f && statusSp <= 0f && controller !in clockSizes) return false
        val clock = notificationBigTime(controller) ?: return false
        fun pixels(sizeSp: Float): Int? = sizeSp.takeIf { it.isFinite() && it > 0f }?.let {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, it, clock.resources.displayMetrics)
                .roundToInt().coerceAtLeast(1)
        }
        val (bigOverride, statusOverride) = clockSizes.getOrPut(controller) { SizeOverride() to SizeOverride() }
        val oldBig = bigTimeSizeField.getInt(controller)
        val oldStatus = statusBarClockSizeField.getInt(controller)
        val big = bigOverride.resolve(oldBig, pixels(bigSp))
        val status = statusOverride.resolve(savedStatusBarClockSize ?: oldStatus, pixels(statusSp))
        // 取消动画原本会暂存小字号并让两个端点相同；保持这份已有约定。
        val endpoint = if (savedStatusBarClockSize != null) {
            savedStatusBarClockSize = status
            big
        } else {
            status
        }
        if (big != oldBig) bigTimeSizeField.setInt(controller, big)
        if (endpoint != oldStatus) statusBarClockSizeField.setInt(controller, endpoint)
        if (bigSp == 0f && statusSp == 0f) clockSizes.remove(controller)
        return big != oldBig || endpoint != oldStatus
    }

    private fun adopt(view: TextView) {
        (view.getTag(tickKey) as? Runnable)?.let(view::removeCallbacks)
        view.setTag(tickKey, null)
        view.setTag(markKey, marker)
        val original = view.getTag(originalKey) as? Original
        if (original == null) view.setTag(originalKey, Original(view)) else original.restore(view)
        val name = nameOf(view)
        val variant = name?.let(variants::get)
        view.setTag(variantKey, variant)
        adopted.add(view)
        if (variant != null) {
            style(view, variant)
            keepCollapsed(view)
        }
    }

    // 宿主挂载后会重设 pad_clock 可见性，隐藏开关需在绘制前持续生效。
    private fun keepCollapsed(view: TextView) {
        if (view.getTag(emptyKey) != null) return
        view.setTag(emptyKey, true)
        // 未挂窗口时 ViewTreeObserver 是临时实例，挂上再注册。
        doOnAttached(view) { clock ->
            clock.viewTreeObserver.addOnPreDrawListener {
                val original = clock.getTag(originalKey) as? Original
                val variant = clock.getTag(variantKey) as? Variant
                if (original != null && variant != null) {
                    original.setHidden(clock, variant.hidden)
                }
                true
            }
        }
    }

    private fun doOnAttached(view: TextView, action: (TextView) -> Unit) {
        if (view.isAttachedToWindow) {
            action(view)
            return
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                action(view)
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    private fun restyle(view: TextView) {
        (view.getTag(variantKey) as? Variant)?.let { style(view, it) }
    }

    /** 左右为 0 表示沿用宿主内边距。 */
    private fun applyPadding(view: TextView, v: Variant) {
        if (v.hidden) return
        val density = view.resources.displayMetrics.density
        val top = if (v.offset != 12f) ((v.offset - 12f) * 0.5f * density).roundToInt() else 0
        (view.getTag(originalKey) as? Original)?.applyPadding(
            view,
            startPx = (v.leftDp * density).roundToInt().takeIf { v.leftDp != 0f },
            endPx = (v.rightDp * density).roundToInt().takeIf { v.rightDp != 0f },
            top = top,
        )
    }

    /**
     * 取消下拉动画时照 Pad 的做法：大时钟不再从状态栏时钟放大过来，而是和下面的迷你时钟同进同退：
     * 位移、透明度都直接取迷你时钟的值，快速收回时一起缩回去、一起淡出，节奏与宿主完全一致。
     * 宿主每帧仍会按进度改字号、缩放，这里跟在后面钉回终态；把状态栏字号字段设成大字号，宿主的半程切换就成了空操作。
     */
    private fun pinBigTime(controller: Any) {
        val clock = notificationBigTime(controller) ?: return
        if (!noShadeAnimation) {
            restoreHost(controller)
            return
        }
        val big = bigTimeSizeField.getInt(controller)
        val current = statusBarClockSizeField.getInt(controller)
        if (current != big) {
            savedStatusBarClockSize = current
            statusBarClockSizeField.setInt(controller, big)
        }
        if (clock.textSize != big.toFloat()) clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, big.toFloat())
        val params = clock.layoutParams
        if (params != null && params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            clock.requestLayout()
        }
        clock.scaleX = 1f
        clock.scaleY = 1f
        val date = headerView(controller, notificationDateTimeField)
        if (date != null) {
            clock.translationX = date.translationX
            clock.translationY = date.translationY
            clock.alpha = date.alpha
        } else {
            clock.translationX = 0f
            clock.translationY = 0f
        }
    }

    /**
     * 关掉开关：还回状态栏字号字段，让宿主重算缩放系数，再让宿主按当前进度和 appearance 重走一帧，
     * 字号、缩放、位移、透明度全部回到它自己的状态。直接把透明度置 1 会让大时钟停在面板收起后的位置上。
     */
    private fun restoreHost(controller: Any) {
        val saved = savedStatusBarClockSize ?: return
        savedStatusBarClockSize = null
        statusBarClockSizeField.setInt(controller, saved)
        updateTranslationYMethod.invoke(controller)
        val callback = notificationCallbackField.get(controller) ?: return
        onAppearanceChangedMethod.invoke(callback, newAppearanceField.getBoolean(callback), false)
        onExpansionChangedMethod.invoke(callback, progressField.getFloat(controller))
    }

    /** 迷你时钟的淡入淡出是 Folme 在后续帧里推进的，面板不动就没有进度回调，这里自己跟几帧把值抄过来。 */
    private fun followMiniClock(controller: Any) {
        val clock = notificationBigTime(controller) ?: return
        val deadline = SystemClock.uptimeMillis() + 600
        val tick = object : Runnable {
            override fun run() {
                pinBigTime(controller)
                if (noShadeAnimation && SystemClock.uptimeMillis() < deadline) clock.postOnAnimation(this)
            }
        }
        clock.postOnAnimation(tick)
    }

    private var lazyGet: Method? = null

    private fun notificationBigTime(controller: Any): TextView? = headerView(controller, notificationBigTimeField) as? TextView

    private fun headerView(controller: Any, field: Field): View? {
        val lazy = headerControllerField.get(controller) ?: return null
        val get = lazyGet ?: lazy.javaClass.getMethod("get").also { lazyGet = it }
        val header = get.invoke(lazy) ?: return null
        return field.get(header) as? View
    }

    private val boldCache = HashMap<Typeface, Typeface>()

    private const val BoldSettings = "'wght' 701"
    private val boldAxes: List<FontVariationAxis> = FontVariationAxis.fromFontVariationSettings(BoldSettings).orEmpty().toList()

    /** 公开类上的隐藏成员桩不了，只能反射；SystemUI 是平台签名，隐藏 API 对它放行。 */
    private val createWithVariation: Method? by lazy {
        runCatching {
            Typeface::class.java.getMethod("createFromTypefaceWithVariation", Typeface::class.java, List::class.java)
        }.getOrNull()
    }

    /**
     * 宿主的 MiPro 是可变字体，且构造时写死了 wght 轴，Typeface.create 改字重对它无效，得改轴。
     * 按来源缓存 701 字重；生成的字体也映射到自身，重复刷新样式时不再派生新字体。
     * 反射不可用时退到 Paint.setFontVariationSettings 这条公开 API。
     */
    private fun bold(base: Typeface?): Typeface {
        val source = base ?: Typeface.DEFAULT
        if (boldCache.size > 32) boldCache.clear()
        boldCache[source]?.let { return it }
        val result = runCatching { createWithVariation?.invoke(null, source, boldAxes) as? Typeface }.getOrNull()
            ?: Paint().apply { typeface = source }.let {
                if (it.setFontVariationSettings(BoldSettings)) it.typeface else Typeface.create(source, 701, false)
            }
        boldCache[source] = result
        boldCache[result] = result
        return result
    }

    private fun nameOf(view: TextView): String? {
        val id = view.id
        if (id == View.NO_ID) return null
        return nameById.getOrPut(id) {
            variants.keys.firstOrNull { view.resources.idOf(it, "id", view.context.packageName) == id }
        }
    }

    private fun style(view: TextView, v: Variant) {
        if (v.hidden) {
            (view.getTag(originalKey) as? Original)?.setHidden(view, true)
            return
        }
        val density = view.resources.displayMetrics.density
        (view.getTag(originalKey) as? Original)?.applySize(view, v.sizeSp)
        if (v.bold) view.typeface = bold(view.typeface)
        if (v.twoLines) {
            view.isSingleLine = false
            view.maxLines = 2
            view.textAlignment = when (v.align) {
                1 -> View.TEXT_ALIGNMENT_CENTER
                2 -> View.TEXT_ALIGNMENT_TEXT_END
                else -> View.TEXT_ALIGNMENT_TEXT_START
            }
            view.setLineSpacing(0f, v.spacing)
        }
        applyPadding(view, v)
        if (v.fixedWidthDp > 30f) view.width = (v.fixedWidthDp * density).roundToInt()
    }

    /** [now] 由调用方给：渲染与排下一拍须用同一时刻，见 [startTicker]。 */
    private fun render(view: TextView, v: Variant, now: Long = System.currentTimeMillis()) {
        val calendar = calendarField.get(controllerField.get(view)) ?: return
        if (v.hasSeconds) setTimeMethod.invoke(calendar, now, *setTimeExtraArgs)
        val text = formatMethod.invoke(calendar, view.context, v.format) as? CharSequence ?: return
        if (!TextUtils.equals(text, view.text)) view.text = text
    }

    private fun startTicker(view: TextView, v: Variant) {
        val tick = object : Runnable {
            override fun run() {
                if (view.getTag(markKey) !== marker) return
                if (!view.isAttachedToWindow) {
                    view.setTag(tickKey, null)
                    return
                }
                // 渲染与调度共用时间基准，避免渲染跨过秒边界后漏掉下一秒。
                val now = System.currentTimeMillis()
                val next = SystemClock.uptimeMillis() + (1000 - now % 1000)
                if (view.isShown) render(view, v, now)
                view.postDelayed(this, (next - SystemClock.uptimeMillis()).coerceAtLeast(1))
            }
        }
        view.setTag(tickKey, tick)
        view.postDelayed(tick, 1000 - System.currentTimeMillis() % 1000)
    }
}
