package io.github.lingqiqi.meowceiler.hook.util

import androidx.annotation.StringRes
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed

/** 模块自己的字符串；直接用资源 ID，避免资源压缩后名称被重写。 */
fun moduleString(
    @StringRes resId: Int,
): String? =
    runCatching {
        EzXposed.moduleRes.getString(resId)
    }.getOrNull()
