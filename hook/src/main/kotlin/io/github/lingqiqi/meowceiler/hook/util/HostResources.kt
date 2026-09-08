package io.github.lingqiqi.meowceiler.hook.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.view.View

/**
 * 按名字访问宿主资源。查不到返回 0 / null 而不是抛：在 hook 回调里抛出去就是宿主崩溃。
 * `getIdentifier` 是字符串查表，热路径上先解析成 Int 再用，见 [io.github.lingqiqi.meowceiler.hook.base.ContextScope]。
 */
@SuppressLint("DiscouragedApi")
fun Resources.idOf(name: String, type: String, packageName: String): Int =
    getIdentifier(name, type, packageName)

fun Context.idOf(name: String, type: String = "id", packageName: String = this.packageName): Int =
    resources.idOf(name, type, packageName)

/** 宿主布局里按 id 名字找子 View；名字不存在或树里没有都返回 null。 */
fun View.findViewByName(name: String): View? {
    val id = context.idOf(name)
    return if (id == 0) null else findViewById(id)
}

fun Activity.findViewByName(name: String): View? {
    val id = idOf(name)
    return if (id == 0) null else findViewById(id)
}

fun Context.stringByName(name: String): String? =
    idOf(name, "string").takeIf { it != 0 }?.let(::getString)

fun Context.drawableByName(name: String): Drawable? =
    idOf(name, "drawable").takeIf { it != 0 }?.let(::getDrawable)

fun Context.dimenPxByName(name: String): Int? =
    idOf(name, "dimen").takeIf { it != 0 }?.let(resources::getDimensionPixelSize)

fun View.setPadding(padding: Int) = setPadding(padding, padding, padding, padding)

fun View.setPaddingHorizontal(left: Int, right: Int = left) =
    setPadding(left, paddingTop, right, paddingBottom)

fun View.setPaddingVertical(top: Int, bottom: Int = top) =
    setPadding(paddingLeft, top, paddingRight, bottom)
