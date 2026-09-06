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
import io.github.lingqiqi5211.meowui.component.MeowTextInputDialog
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Int 滑块。MeowUI 绑 key 的滑块只接 `PreferenceKey<Float>`，这里读写走 Int、滑动用 Float。
 * 拖动期间用本地值回显、抬手才提交：每帧写远程再等回环会让滑块发抖。
 */
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

/** 标题是运行时字符串（功能 id、宿主包名这类）的行。 */
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

@Composable
internal fun MeowSliderRow(
    key: PreferenceKey<Float>,
    titleRes: Int,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String,
    showPlus: Boolean,
    testTag: String,
) {
    val stored by rememberMeowPreferenceValue(key)
    val write = rememberMeowPreferenceWriter(key)
    val scale = SliderScale(range, step, showPlus)

    var draft by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var showInput by remember { mutableStateOf(false) }
    LaunchedEffect(stored) {
        if (!dragging) draft = null
    }

    val title = stringResource(titleRes)
    val rangeError = stringResource(R.string.slider_input_error, scale.number(range.start), scale.number(range.endInclusive))
    MeowSliderPreference(
        title = title,
        value = draft ?: stored,
        onValueChange = { value ->
            dragging = true
            draft = value
        },
        modifier = Modifier.testTag(testTag),
        valueRange = range,
        steps = scale.steps,
        valueText = { "${scale.display(scale.snap(it))} $unit".trim() },
        onValueChangeFinished = {
            dragging = false
            draft?.let { write(scale.snap(it)) }
        },
        onClick = { showInput = true },
    )
    MeowTextInputDialog(
        show = showInput,
        title = title,
        initialValue = scale.number(stored),
        placeholder = stringResource(
            R.string.slider_input_hint,
            scale.number(range.start),
            scale.number(range.endInclusive),
            scale.number(key.defaultValue),
        ),
        allowBlank = false,
        blankErrorText = stringResource(R.string.dialog_blank_error),
        confirmText = stringResource(R.string.dialog_confirm),
        cancelText = stringResource(R.string.dialog_cancel),
        validator = { text -> if (scale.parse(text) == null) rangeError else null },
        onConfirm = { text ->
            scale.parse(text)?.let(write)
            showInput = false
        },
        onDismissRequest = { showInput = false },
    )
}

/** 分档、显示和解析。档从 range 起点数起，落档后按 step 的小数位取整，去掉浮点尾数。 */
private class SliderScale(
    private val range: ClosedFloatingPointRange<Float>,
    private val step: Float,
    private val showPlus: Boolean,
) {
    private val decimals = BigDecimal(step.toString()).stripTrailingZeros().scale().coerceAtLeast(0)

    val steps: Int = (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)

    fun snap(value: Float): Float {
        val stepped = range.start + ((value - range.start) / step).roundToInt() * step
        return BigDecimal(stepped.toString()).setScale(decimals, RoundingMode.HALF_UP).toFloat()
            .coerceIn(range.start, range.endInclusive)
    }


    fun number(value: Float): String = String.format(Locale.US, "%.${decimals}f", value)

    fun display(value: Float): String {
        val text = number(value)
        return if (showPlus && value > 0f) "+$text" else text
    }

    fun parse(text: String): Float? =
        text.trim().removePrefix("+").toFloatOrNull()?.takeIf { it in range }?.let(::snap)
}

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
