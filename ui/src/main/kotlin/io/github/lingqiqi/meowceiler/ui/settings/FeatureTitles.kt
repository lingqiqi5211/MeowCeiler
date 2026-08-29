package io.github.lingqiqi.meowceiler.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.ui.R

/**
 * 功能 id → 显示名。
 *
 * hook 侧拿不到模块资源（宿主进程里没有模块的 resources），所以日志记录里只有与语言无关的 id。
 * 名字只能在这边补。
 *
 * 加功能时这里跟着加一行 —— 和在分类页加一行 `FeatureSwitchRow` 是同一个动作。漏了不会崩，
 * 日志页退回显示原始 id。
 *
 * 这不是功能注册表：日志页的清单仍然由记录本身决定，这里只负责把 id 翻成人话。
 */
private val Titles: Map<String, Int> = mapOf(
    Preferences.SystemUi.DoubleTapToSleep.name to R.string.systemui_double_tap_to_sleep,
)

@Composable
fun featureTitle(tag: String): String = Titles[tag]?.let { stringResource(it) } ?: tag
