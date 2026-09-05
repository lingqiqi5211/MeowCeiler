package io.github.lingqiqi.meowceiler.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.ui.R

/**
 * 功能 id → 显示名。hook 侧拿不到模块资源，日志记录里只有 id，名字在这边补。
 * 加功能时跟着加一行；漏了日志页退回显示原始 id。
 */
private val Titles: Map<String, Int> = mapOf(
    Preferences.SystemUi.DoubleTapToSleep.name to R.string.systemui_double_tap_to_sleep,
)

@Composable
fun featureTitle(tag: String): String = Titles[tag]?.let { stringResource(it) } ?: tag
