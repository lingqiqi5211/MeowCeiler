package io.github.lingqiqi.meowceiler.shared

import android.content.res.Resources
import io.github.lingqiqi5211.ezhooktool.core.callStaticMethod
import io.github.lingqiqi5211.ezhooktool.core.getStaticField
import io.github.lingqiqi5211.ezhooktool.core.toClass

/** 与 HyperCeiler 的 Miui.isPad 一致：优先系统标记，读取失败时使用通用判断。 */
val isPadDevice: Boolean by lazy {
    runCatching {
        "miui.os.Build".toClass().getStaticField("IS_TABLET") as Boolean
    }.getOrElse {
        Resources.getSystem().configuration.smallestScreenWidthDp >= 600 || isFoldableDevice()
    }
}

private fun isFoldableDevice(): Boolean {
    val type = readProperty("persist.sys.multi_display_type").toIntOrNull() ?: 1
    return if (type > 1) {
        (type and 15) in 3..5
    } else {
        readProperty("persist.sys.muiltdisplay_type").toIntOrNull() == 2
    }
}

fun readProperty(key: String): String = runCatching {
    "android.os.SystemProperties".toClass().callStaticMethod("get", key, "") as? String
}.getOrNull().orEmpty()
