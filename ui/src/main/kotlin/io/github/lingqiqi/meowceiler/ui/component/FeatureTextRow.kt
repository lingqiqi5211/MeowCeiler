package io.github.lingqiqi.meowceiler.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi5211.meowui.component.MeowPreferenceSectionScope
import io.github.lingqiqi5211.meowui.component.MeowTextInputPreference
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceWriter

@Suppress("FunctionName")
fun MeowPreferenceSectionScope.FeatureTextRow(
    key: PreferenceKey<String>,
    titleRes: Int,
    visible: Boolean = true,
    allowBlank: Boolean = false,
    testTag: String = "row.${key.name}",
) = item(key = key.name, visible = visible, container = false) {
    MeowTextRow(key, titleRes, allowBlank, testTag)
}

@Composable
private fun MeowTextRow(key: PreferenceKey<String>, titleRes: Int, allowBlank: Boolean, testTag: String) {
    val stored by rememberMeowPreferenceValue(key)
    val write = rememberMeowPreferenceWriter(key)
    MeowTextInputPreference(
        title = stringResource(titleRes),
        value = stored,
        onValueChange = { if (it != stored) write(it) },
        modifier = Modifier.testTag(testTag),
        allowBlank = allowBlank,
        blankErrorText = stringResource(R.string.dialog_blank_error),
        confirmText = stringResource(R.string.dialog_confirm),
        cancelText = stringResource(R.string.dialog_cancel),
    )
}
