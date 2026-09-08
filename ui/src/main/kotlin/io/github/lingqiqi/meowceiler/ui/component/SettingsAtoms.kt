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

@Composable
fun SettingsCard(
    testTag: String,
    content: @Composable MeowPreferenceSectionScope.() -> Unit,
) {
    MeowPreferenceSection(modifier = Modifier.testTag(testTag), content = content)
}

@Suppress("FunctionName")
fun MeowPreferenceSectionScope.FeatureSwitchRow(
    key: PreferenceKey<Boolean>,
    titleRes: Int,
    summaryRes: Int? = null,
    visible: Boolean = true,
    testTag: String = "row.${key.name}",
) = item(key = key.name, visible = visible, container = false) {
    MeowSwitchPreferenceRow(key, titleRes, summaryRes, testTag)
}

@Suppress("FunctionName")
fun MeowPreferenceSectionScope.SettingsNavigationRow(
    titleRes: Int,
    testTag: String,
    summaryRes: Int? = null,
    visible: Boolean = true,
    onClick: () -> Unit,
) = item(key = testTag, visible = visible, container = false) {
    MeowActionRow(titleRes, testTag, summaryRes, value = null, navigation = true, onClick = onClick)
}

@Suppress("FunctionName")
fun MeowPreferenceSectionScope.SettingsInfoRow(
    titleRes: Int,
    value: String,
    testTag: String,
) = item(key = testTag, container = false) {
    MeowActionRow(titleRes, testTag, summaryRes = null, value = value, navigation = false, onClick = {})
}

/** 值按 [step] 分档；通过 [visible] 更新分组中的可见性。 */
@Suppress("FunctionName")
fun MeowPreferenceSectionScope.FeatureSliderRow(
    key: PreferenceKey<Float>,
    titleRes: Int,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    unit: String = "",
    showPlus: Boolean = false,
    visible: Boolean = true,
    testTag: String = "row.${key.name}",
) = item(key = key.name, visible = visible, container = false) {
    MeowSliderRow(key, titleRes, range, step, unit, showPlus, testTag)
}

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

@Composable
fun MeowPreferenceSectionByTitle(
    title: String,
    testTag: String,
    content: @Composable MeowPreferenceSectionScope.() -> Unit,
) {
    MeowPreferenceSection(modifier = Modifier.testTag(testTag), title = title, content = content)
}
