package io.github.lingqiqi.meowceiler.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi5211.meowui.component.MeowActionPreference
import io.github.lingqiqi5211.meowui.component.MeowSliderPreference
import io.github.lingqiqi5211.meowui.component.MeowSwitchPreference
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceWriter

// 这些包装存在的唯一理由：把对 MeowUI 顶层 Composable 的调用挪出
// MeowPreferenceSectionScope 的接收者作用域。在 scope 里写裸名会解析到 scope 成员，
// 成员再调 item() 时 collectingInBody 已 false，条目登记不进去、整行静默消失。

@Composable
internal fun MeowSwitchPreferenceRow(
    key: PreferenceKey<Boolean>,
    titleRes: Int,
    summaryRes: Int?,
    testTag: String,
) {
    MeowSwitchPreference(
        key = key,
        title = stringResource(titleRes),
        summary = summaryRes?.let { stringResource(it) },
        modifier = Modifier.testTag(testTag),
    )
}

@Composable
internal fun MeowActionRow(
    titleRes: Int,
    testTag: String,
    summaryRes: Int?,
    value: String?,
    navigation: Boolean,
    onClick: () -> Unit,
) {
    MeowActionPreference(
        title = stringResource(titleRes),
        modifier = Modifier.testTag(testTag),
        summary = summaryRes?.let { stringResource(it) },
        value = value,
        navigation = navigation,
        onClick = onClick,
    )
}

/**
 * Int 滑块。
 *
 * MeowUI 绑 key 的滑块只接 `PreferenceKey<Float>`，而这类参数（行列数、间隔、尺寸）都是整数语义，
 * 所以自己接一层：读写走 Int，滑动过程用 Float。
 *
 * 拖动期间用本地值回显、抬手才提交一次 —— 每帧写远程存储再等值回环会让滑块发抖。
 */
@Composable
internal fun MeowIntSliderRow(
    key: PreferenceKey<Int>,
    titleRes: Int,
    range: IntRange,
    step: Int,
    testTag: String,
) {
    val stored by rememberMeowPreferenceValue(key)
    val write = rememberMeowPreferenceWriter(key)

    var draft by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(stored) {
        if (!dragging) draft = null
    }

    val shown = draft ?: stored.toFloat()
    MeowSliderPreference(
        title = stringResource(titleRes),
        value = shown,
        onValueChange = { value ->
            dragging = true
            draft = value
        },
        modifier = Modifier.testTag(testTag),
        summary = shown.toInt().toString(),
        valueRange = range.first.toFloat()..range.last.toFloat(),
        // Compose 的 steps 数的是两端之间的档位数，所以要减一。
        steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
        valueText = { it.toInt().toString() },
        onValueChangeFinished = {
            dragging = false
            draft?.let { write(it.toInt()) }
        },
    )
}

/** 标题是运行时字符串（功能 id、宿主包名这类）的行。 */
@Composable
internal fun MeowTextActionRow(
    title: String,
    value: String?,
    testTag: String,
    navigation: Boolean,
    onClick: () -> Unit,
) {
    MeowActionPreference(
        title = title,
        modifier = Modifier.testTag(testTag),
        value = value,
        navigation = navigation,
        onClick = onClick,
    )
}
