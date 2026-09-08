package io.github.lingqiqi.meowceiler.hook.rules.systemui.controlcenter

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lingqiqi.meowceiler.hook.base.Feature
import io.github.lingqiqi.meowceiler.hook.base.StaticHooker
import io.github.lingqiqi.meowceiler.hook.util.MLog
import io.github.lingqiqi.meowceiler.hook.util.Settings
import io.github.lingqiqi.meowceiler.hook.util.boldTypeface
import io.github.lingqiqi.meowceiler.hook.util.dimenPxByName
import io.github.lingqiqi.meowceiler.hook.util.findViewByName
import io.github.lingqiqi.meowceiler.hook.utils.systemui.SystemWeather
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.findAllMethods
import io.github.lingqiqi5211.ezhooktool.core.findConstructor
import io.github.lingqiqi5211.ezhooktool.core.findField
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.getField
import io.github.lingqiqi5211.ezhooktool.core.toClass
import io.github.lingqiqi5211.ezhooktool.xposed.EzResources
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
import kotlin.math.roundToInt

// 宿主接点参考 HyperCeiler 的 NotificationWeather / WeatherView。
@Feature(
    name = "通知中心天气",
    since = "2026-09-08",
    target = "HyperOS 3/4 CombinedHeaderController",
    updated = "2026-09-08",
)
object NotificationWeather : StaticHooker(Preferences.SystemUi.NotificationWeather) {
    private val viewKey = EzResources.fakeResId("meowceiler.notification.weather")
    private val bindingKey = EzResources.fakeResId("meowceiler.notification.weather.binding")
    private val cleanupKey = EzResources.fakeResId("meowceiler.notification.weather.cleanup")
    private val heightKey = EzResources.fakeResId("meowceiler.notification.weather.height")
    private val featureTag = Preferences.SystemUi.NotificationWeather.name
    private val bindings = mutableSetOf<HeaderBinding>()
    private lateinit var verticalMode: Method
    private lateinit var topPadding: Method
    private lateinit var updateTopPadding: Method

    override fun onHook() {
        verticalMode = "com.miui.utils.configs.MiuiConfigs".toClass().findMethod {
            name("isVerticalMode"); parameterTypes(Context::class.java)
        }
        installNotificationSpacing()
        val headerClass = "com.android.systemui.qs.MiuiNotificationHeaderView".toClass()
        val date = findField(headerClass) { name("mDateView") }
        val horizontal = findField(headerClass) { name("mLandClock") }
        fun bind(view: View) {
            val header = view as? ViewGroup ?: return
            if (!header.isAttachedToWindow) return
            (header.getTag(bindingKey) as? HeaderBinding)?.let { it.configurationChanged(); return }
            val dateView = date.get(header) as? TextView ?: return
            val horizontalView = horizontal.get(header) as? TextView ?: return
            // Runnable 来自平台类加载器，新模块可同步释放旧模块的视图及高度覆盖。
            (header.getTag(cleanupKey) as? Runnable)?.run()
            for (source in listOf(dateView, horizontalView)) {
                (source.getTag(viewKey) as? View)?.let { (it.parent as? ViewGroup)?.removeView(it) }
                source.setTag(viewKey, null)
            }
            (header.getTag(heightKey) as? Int)?.let {
                header.layoutParams = header.layoutParams.apply { height = it }
                header.setTag(heightKey, null)
            }
            val binding = HeaderBinding(header, dateView, horizontalView)
            header.setTag(bindingKey, binding)
            header.setTag(cleanupKey, Runnable { binding.dispose() })
            bindings.add(binding)
            binding.start()
        }
        val refreshMethods = listOf("onAttachedToWindow", "updateResources", "updateHeaderResources", "updateLayout")
            .flatMap { methodName -> headerClass.findAllMethods { name(methodName); paramCount(0) } }
        check(refreshMethods.any { it.name == "onAttachedToWindow" }) { "Notification header attach method unavailable" }
        refreshMethods.forEach { method ->
            method.createHook { after { param -> (param.thisObjectOrNull as? View)?.let(::bind) } }
        }
        headerClass.findMethod { name("onDetachedFromWindow"); paramCount(0) }.createHook {
            before { param ->
                ((param.thisObjectOrNull as? View)?.getTag(bindingKey) as? HeaderBinding)?.dispose()
            }
        }
        // 安装热重载不会重建宿主窗口，由新一代模块主动接管已挂载的标题区。
        Settings.scope.launch(Dispatchers.Main.immediate) {
            val windowManager = "android.view.WindowManagerGlobal".toClass()
            val manager = windowManager.findMethod { name("getInstance"); paramCount(0) }.invoke(null)
            val roots = windowManager.findMethod { name("getWindowViews"); paramCount(0) }.invoke(manager) as List<*>
            fun visit(view: View) {
                if (headerClass.isInstance(view)) { bind(view); return }
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            roots.filterIsInstance<View>().forEach(::visit)
        }
    }

    private fun installNotificationSpacing() {
        val topClass = "com.android.systemui.shade.NotificationTopPaddingControllerImpl".toClass()
        topPadding = topClass.findMethod { name("getTopPadding"); paramCount(0) }
        val injector = findField(topClass) { name("nsslControllerInjector") }
        val stack = findField(injector.type) { name("layout") }
        val topState = findField(topClass) { name("statusBarStateController") }
        val getState = topState.type.findMethod { name("getState"); paramCount(0) }
        topPadding.createHook {
            after { param ->
                val instance = param.thisObjectOrNull ?: return@after
                if (getState.invoke(topState.get(instance)) == 1) return@after
                val view = stack.get(injector.get(instance)) as? View ?: return@after
                val original = param.result as? Float ?: return@after
                param.result = original + additionalSpacing(view.rootView, original)
            }
        }
        val animator = "com.android.systemui.shade.MiuiNotificationPanelAnimControllerImpl".toClass()
        val panel = findField(animator) { name("panelView") }
        val animatorState = findField(animator) { name("statusBarStateController") }
        animator.findMethod { name("getHeaderHeight"); paramCount(0) }.createHook {
            after { param ->
                val instance = param.thisObjectOrNull ?: return@after
                if (getState.invoke(animatorState.get(instance)) == 1) return@after
                val view = panel.get(instance) as? View ?: return@after
                val original = param.result as? Float ?: return@after
                param.result = original + additionalSpacing(view.rootView, original)
            }
        }
        updateTopPadding = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout".toClass()
            .findMethod { name("updateTopPadding"); parameterTypes(Float::class.java, Boolean::class.java) }
    }

    // 顶部间距每帧都问，rootView 要顺着父链走，绑定时取一次就够。
    private fun additionalSpacing(root: View, original: Float): Float = bindings.firstOrNull {
        it.root === root && it.header.visibility == View.VISIBLE
    }?.let { it.extraHeight * (original / it.baseHeight.coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f

    private data class Style(
        val city: Boolean = false,
        val newLine: Boolean = false,
        val bold: Boolean = false,
        val sizeSp: Float = 0f,
        val icon: Boolean = false,
        val marginDp: Float = 0f,
    )

    private class Slot(val source: TextView) {
        val weather = TextView(source.context).apply {
            id = View.generateViewId()
            isSingleLine = true
            visibility = View.GONE
            setOnClickListener { SystemWeather.open(featureTag) }
        }
        var newLine: Boolean? = null
        var icon: Drawable? = null
        var measureNeeded = true
        var measuredForWidth = -1
    }

    private class HeaderBinding(val header: ViewGroup, date: TextView, horizontal: TextView) {
        private val slots = listOf(Slot(date), Slot(horizontal))
        private var style = Style()
        private var weather: SystemWeather.Weather? = null
        private var updates: Job? = null
        private var disposed = false
        private var refreshing = false
        private var vertical = verticalMode.invoke(null, header.context) as Boolean
        private var gap = (header.resources.displayMetrics.density * 5).roundToInt()
        private var inlineMargin = (header.context.dimenPxByName("notification_panel_time_date_space") ?: 0) + gap
        private var layoutChanged = false
        val root: View = header.rootView
        private val tree = header.viewTreeObserver
        private val sync = ViewTreeObserver.OnPreDrawListener {
            runCatching { refresh() }.getOrElse {
                MLog.w(featureTag, "Cannot update weather layout", it)
                dispose()
                true
            }
        }
        private val stack = root.findViewByName("notification_stack_scroller")
        private val paddingController = stack?.callMethod("getNsslInjector")
            ?.getField("nsslControllerInjector")?.getField("qsController")?.callMethod("get")
            ?.getField("mNotificationTopPaddingController")?.callMethod("get")
        private val anchors = if (header is LinearLayout) emptyList() else {
            val bottomToBottom = findField(date.layoutParams.javaClass) { name("bottomToBottom") }
            (0 until header.childCount).map(header::getChildAt).filter {
                bottomToBottom.getInt(it.layoutParams) == 0
            }.map(::BottomAnchor)
        }
        var baseHeight = header.layoutParams.height
            private set
        var extraHeight = 0
            private set
        private var appliedHeight: Int? = null
        private var lastVisibility = header.visibility

        fun start() {
            tree.addOnPreDrawListener(sync)
            updates = Settings.scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                try {
                    val preferences = with(Preferences.SystemUi) {
                        combine(
                            Settings.observe(NotificationWeatherCity), Settings.observe(NotificationWeatherNewLine),
                            Settings.observe(NotificationWeatherBold), Settings.observe(NotificationWeatherSize),
                            Settings.observe(NotificationWeatherIcon),
                        ) { city, newLine, bold, size, icon -> Style(city, newLine, bold, size, icon) }
                    }
                    val styles = combine(preferences, Settings.observe(Preferences.SystemUi.NotificationWeatherMargin)) { next, margin ->
                        next.copy(marginDp = margin)
                    }
                    combine(SystemWeather.observe(header.context, featureTag), styles) { value, next -> value to next }
                        .collect { (value, next) ->
                            weather = value
                            style = next
                            slots.forEach { it.measureNeeded = true }
                            render()
                            refresh()
                        }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) { dispose() }
                }
            }
        }

        private fun render() {
            for (slot in slots) {
                syncAppearance(slot)
                val icon = weather?.takeIf { style.icon }?.let { SystemWeather.icon(header.context, it.type, featureTag) }
                slot.icon = icon
                slot.weather.text = weather?.let {
                    SystemWeather.format(it, style.city, icon, (slot.weather.textSize * 1.2f).roundToInt())
                } ?: ""
            }
        }

        fun configurationChanged() {
            vertical = verticalMode.invoke(null, header.context) as Boolean
            gap = (header.resources.displayMetrics.density * 5).roundToInt()
            inlineMargin = (header.context.dimenPxByName("notification_panel_time_date_space") ?: 0) + gap
            slots.forEach { it.measureNeeded = true }
            render()
            refresh()
        }

        fun refresh(): Boolean {
            if (disposed || refreshing) return true
            refreshing = true
            layoutChanged = false
            try {
                for ((index, slot) in slots.withIndex()) {
                    if (slot.newLine != style.newLine) place(slot)
                    syncAppearance(slot)
                    val visible = if ((index == 0) != vertical || slot.weather.text.isNullOrBlank()) View.GONE else slot.source.visibility
                    if (slot.weather.visibility != visible) {
                        slot.weather.visibility = visible
                        layoutChanged = true
                    }
                    updateMargins(slot)
                }
                updateHeight()
            } finally {
                refreshing = false
            }
            return !layoutChanged
        }

        private fun place(slot: Slot) {
            val source = slot.source
            val target = if (style.newLine) header else source.parent as? ViewGroup ?: return
            val params = when {
                target is LinearLayout -> LinearLayout.LayoutParams(-2, -2).apply {
                    // 同一行交给 LinearLayout 的基线对齐；设了 CENTER_VERTICAL 会盖掉它，
                    // 两边字号不同时就差出几 dp。
                    if (style.newLine) gravity = Gravity.START
                }
                source.layoutParams?.javaClass?.name == ConstraintParams -> {
                    if (source.id == View.NO_ID) return
                    val clazz = source.layoutParams.javaClass
                    val result = clazz.findConstructor { parameterTypes(Int::class.java, Int::class.java) }
                        .newInstance(-2, -2) as ViewGroup.MarginLayoutParams
                    val links = if (style.newLine) {
                        listOf("startToStart", "topToBottom")
                    } else {
                        listOf("startToEnd", "baselineToBaseline")
                    }
                    for (field in links) {
                        findField(clazz) { name(field) }.setInt(result, source.id)
                    }
                    result
                }
                else -> error("Unsupported weather parent: ${target.javaClass.name}")
            }
            (slot.weather.parent as? ViewGroup)?.removeView(slot.weather)
            slot.weather.layoutParams = params
            if (style.newLine) target.addView(slot.weather) else target.addView(slot.weather, target.indexOfChild(source) + 1)
            source.setTag(viewKey, slot.weather)
            slot.newLine = style.newLine
            slot.measureNeeded = true
            layoutChanged = true
        }

        private fun updateMargins(slot: Slot) {
            val params = slot.weather.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val margin = (header.resources.displayMetrics.density * style.marginDp.coerceAtLeast(0f)).roundToInt()
            val start = if (style.newLine && header is LinearLayout) {
                var left = 0
                var child: View = slot.source
                while (child !== header) {
                    left += child.left
                    child = child.parent as? View ?: return
                }
                if (header.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    header.width - left - slot.source.width - header.paddingRight
                } else left - header.paddingLeft
            } else if (style.newLine) 0 else if (margin > 0) margin else {
                if (slot.source.parent is LinearLayout) inlineMargin else gap
            }
            val top = if (style.newLine) margin else 0
            if (params.marginStart != start || params.topMargin != top) {
                params.marginStart = start
                params.topMargin = top
                slot.weather.layoutParams = params
                slot.measureNeeded = true
                layoutChanged = true
            }
        }

        private fun syncAppearance(slot: Slot) {
            val view = slot.weather
            val source = slot.source
            val size = if (style.sizeSp > 0f) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, style.sizeSp, header.resources.displayMetrics) else source.textSize
            if (view.textSize != size) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                if (slot.icon != null) weather?.let {
                    view.text = SystemWeather.format(it, style.city, slot.icon, (size * 1.2f).roundToInt())
                }
                slot.measureNeeded = true
                layoutChanged = true
            }
            // 字体留白跟着源走：只有一边算了留白，同一行的基线就对不齐。
            if (view.includeFontPadding != source.includeFontPadding) {
                view.includeFontPadding = source.includeFontPadding
                slot.measureNeeded = true
                layoutChanged = true
            }
            val face = if (style.bold) boldTypeface(source.typeface) else source.typeface
            if (view.typeface !== face) { view.typeface = face; slot.measureNeeded = true; layoutChanged = true }
            if (view.currentTextColor != source.currentTextColor) view.setTextColor(source.currentTextColor)
            view.translationX = source.translationX
            view.translationY = source.translationY
            view.alpha = source.alpha
        }

        private fun updateHeight() {
            var extra = 0
            if (style.newLine) for (slot in slots) {
                if (slot.weather.visibility == View.GONE) continue
                val params = slot.weather.layoutParams as ViewGroup.MarginLayoutParams
                val width = (header.width - header.paddingLeft - header.paddingRight - params.marginStart - params.marginEnd).coerceAtLeast(0)
                if (slot.measureNeeded || slot.measuredForWidth != width) {
                    slot.weather.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    slot.measureNeeded = false
                    slot.measuredForWidth = width
                }
                extra += slot.weather.measuredHeight + params.topMargin + params.bottomMargin
            }
            val params = header.layoutParams
            if (params.height != appliedHeight) baseHeight = params.height
            if (baseHeight < 0) return
            val changed = extraHeight != extra
            extraHeight = extra
            for (anchor in anchors) if (anchor.apply(extra)) layoutChanged = true
            val height = baseHeight + extra
            // 线性布局等量增高；约束布局同时补偿原底边锚点，均保留时钟位置。
            if (params.height != height) {
                header.setTag(heightKey, baseHeight)
                params.height = height
                header.layoutParams = params
                layoutChanged = true
            }
            appliedHeight = height
            if (changed || lastVisibility != header.visibility) {
                lastVisibility = header.visibility
                refreshTopPadding()
            }
        }

        private fun refreshTopPadding() {
            if (stack != null && paddingController != null) {
                val padding = topPadding.invoke(paddingController) as Float
                updateTopPadding.invoke(stack, padding, false)
            }
        }

        fun dispose() {
            if (disposed) return
            disposed = true
            updates?.cancel()
            updates = null
            if (tree.isAlive) tree.removeOnPreDrawListener(sync)
            for (slot in slots) {
                (slot.weather.parent as? ViewGroup)?.removeView(slot.weather)
                if (slot.source.getTag(viewKey) === slot.weather) slot.source.setTag(viewKey, null)
            }
            if (header.getTag(bindingKey) === this) {
                if (header.layoutParams.height == appliedHeight) header.layoutParams = header.layoutParams.apply { height = baseHeight }
                header.setTag(bindingKey, null)
                header.setTag(cleanupKey, null)
                header.setTag(heightKey, null)
            }
            bindings.remove(this)
            anchors.forEach { it.apply(0) }
            refreshTopPadding()
        }
    }

    // ConstraintLayout 的原子视图锚定底边，增加等量底边距可保持原位置。
    private class BottomAnchor(private val view: View) {
        private var original = (view.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        private var applied: Int? = null

        fun apply(extra: Int): Boolean {
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            if (params.bottomMargin != applied) original = params.bottomMargin
            val margin = original + extra
            applied = margin
            if (params.bottomMargin == margin) return false
            params.bottomMargin = margin
            view.layoutParams = params
            return true
        }
    }

    private const val ConstraintParams = "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams"
}
