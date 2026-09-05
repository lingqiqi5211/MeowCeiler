package io.github.lingqiqi.meowceiler.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.lingqiqi5211.meowui.component.MeowPreferenceSection
import io.github.lingqiqi5211.meowui.component.MeowPreferenceSectionScope
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey

/** 带标题的设置分组。行必须经由 scope 扩展或 [MeowPreferenceSectionScope.item] 声明，直接发 Composable 不会进分组。 */
@Composable
fun SettingsSection(
    titleRes: Int,
    testTag: String,
    content: @Composable MeowPreferenceSectionScope.() -> Unit,
) {
    MeowPreferenceSection(
        modifier = Modifier.testTag(testTag),
        title = stringResource(titleRes),
        content = content,
    )
}

/** 无标题分组，用于页面自带大标题的场合。 */
@Composable
fun SettingsCard(
    testTag: String,
    content: @Composable MeowPreferenceSectionScope.() -> Unit,
) {
    MeowPreferenceSection(modifier = Modifier.testTag(testTag), content = content)
}

/** 功能开关。summary 写这个功能做什么，不写适配版本 —— 那是 hook 头注释的事。 */
@Suppress("FunctionName")
fun MeowPreferenceSectionScope.FeatureSwitchRow(
    key: PreferenceKey<Boolean>,
    titleRes: Int,
    summaryRes: Int? = null,
    testTag: String = "row.${key.name}",
) = item(key = key.name, container = false) {
    MeowSwitchPreferenceRow(key, titleRes, summaryRes, testTag)
}

/** 打开另一个页面的行。 */
@Suppress("FunctionName")
fun MeowPreferenceSectionScope.SettingsNavigationRow(
    titleRes: Int,
    testTag: String,
    summaryRes: Int? = null,
    onClick: () -> Unit,
) = item(key = testTag, container = false) {
    MeowActionRow(titleRes, testTag, summaryRes, value = null, navigation = true, onClick = onClick)
}

/** 只读信息行。 */
@Suppress("FunctionName")
fun MeowPreferenceSectionScope.SettingsInfoRow(
    titleRes: Int,
    value: String,
    testTag: String,
) = item(key = testTag, container = false) {
    MeowActionRow(titleRes, testTag, summaryRes = null, value = value, navigation = false, onClick = {})
}

/** 功能的整数滑块。[visible] 为 false 时走 `item(visible = ...)` 收起，直接不声明会让分区停在旧内容上。 */
@Suppress("FunctionName")
fun MeowPreferenceSectionScope.FeatureIntSliderRow(
    key: PreferenceKey<Int>,
    titleRes: Int,
    range: IntRange,
    step: Int = 1,
    visible: Boolean = true,
    testTag: String = "row.${key.name}",
) = item(key = key.name, visible = visible, container = false) {
    MeowIntSliderRow(key, titleRes, range, step, testTag)
}

/**
 * 标题不是资源、而是运行时字符串的行。
 *
 * 功能 id、宿主包名这些没有对应的 string 资源 —— 它们本来就不该被翻译。
 */
@Suppress("FunctionName")
fun MeowPreferenceSectionScope.SettingsValueRow(
    title: String,
    value: String?,
    testTag: String,
    navigation: Boolean = false,
    onClick: () -> Unit = {},
) = item(key = testTag, container = false) {
    MeowTextActionRow(title, value, testTag, navigation, onClick)
}

/** 分组标题是运行时字符串（宿主包名这类）的分组。 */
@Composable
fun MeowPreferenceSectionByTitle(
    title: String,
    testTag: String,
    content: @Composable MeowPreferenceSectionScope.() -> Unit,
) {
    MeowPreferenceSection(modifier = Modifier.testTag(testTag), title = title, content = content)
}
