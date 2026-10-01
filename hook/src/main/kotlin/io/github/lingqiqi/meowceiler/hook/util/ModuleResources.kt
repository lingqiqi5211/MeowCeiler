package io.github.lingqiqi.meowceiler.hook.util

import io.github.lingqiqi.meowceiler.shared.ModulePackage
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed

/** 模块自己的字符串。注入宿主界面的文案宿主没有，只能从模块 APK 取。 */
fun moduleString(name: String): String? = runCatching {
    val resources = EzXposed.moduleRes
    resources.getIdentifier(name, "string", ModulePackage)
        .takeIf { it != 0 }
        ?.let(resources::getString)
}.getOrNull()
