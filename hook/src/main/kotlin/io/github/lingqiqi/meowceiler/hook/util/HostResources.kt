package io.github.lingqiqi.meowceiler.hook.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.view.View

/**
 * 按名字访问宿主资源。
 *
 * 宿主的资源 ID 每个版本都会变，模块里只能写名字。这一组是 `getIdentifier` 的薄封装，
 * 统一「查不到返回 0 / null」而不是抛 `NotFoundException` —— 在 hook 回调里抛出去就是宿主崩溃，
 * 而某个版本少一个资源是常态。
 *
 * `getIdentifier` 是字符串查表，不便宜。热路径上先在 `onReady` 之类的地方解析成 Int 再用，
 * 见 [io.github.lingqiqi.meowceiler.hook.base.ContextScope]。
 */
@SuppressLint("DiscouragedApi")
fun Resources.idOf(name: String, type: String, packageName: String): Int =
    getIdentifier(name, type, packageName)

/** 默认查宿主自己的包。 */
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

/** 直接给像素值。dimen 的 ID 本身没什么用，要的都是解析后的尺寸。 */
fun Context.dimenPxByName(name: String): Int? =
    idOf(name, "dimen").takeIf { it != 0 }?.let(resources::getDimensionPixelSize)

/** 四边同值。 */
fun View.setPadding(padding: Int) = setPadding(padding, padding, padding, padding)

/** 只改左右，保留宿主自己的上下间距。 */
fun View.setPaddingHorizontal(left: Int, right: Int = left) =
    setPadding(left, paddingTop, right, paddingBottom)

fun View.setPaddingVertical(top: Int, bottom: Int = top) =
    setPadding(paddingLeft, top, paddingRight, bottom)
