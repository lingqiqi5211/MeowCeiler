package io.github.lingqiqi.meowceiler.hook.util

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.FontVariationAxis
import java.lang.reflect.Method

private const val BoldSettings = "'wght' 701"

private val boldAxes: List<FontVariationAxis> =
    FontVariationAxis.fromFontVariationSettings(BoldSettings).orEmpty().toList()

/** 公开类上的隐藏成员桩不了，只能反射；SystemUI 是平台签名，隐藏 API 对它放行。 */
private val createWithVariation: Method? by lazy {
    runCatching {
        Typeface::class.java.getMethod("createFromTypefaceWithVariation", Typeface::class.java, List::class.java)
    }.getOrNull()
}

private val boldCache = HashMap<Typeface, Typeface>()

/**
 * 加粗到 701 字重。
 *
 * MiPro 是可变字体，`Typeface.create(source, BOLD)` 不动 wght 轴，屏幕上看不出变化，
 * 只能直接改轴。结果按源字体缓存，逐帧取用不会反复合成。
 */
fun boldTypeface(base: Typeface?): Typeface {
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
