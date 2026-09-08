package io.github.lingqiqi.meowceiler.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.settings.AppLanguage
import io.github.lingqiqi5211.meowui.component.MeowPreferenceSectionScope

@Composable
fun MeowPreferenceSectionScope.AppLanguageRow() {
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLanguage.current(context)) }
    val labels = mapOf(
        AppLanguage.System to stringResource(R.string.settings_language_system),
        AppLanguage.Chinese to stringResource(R.string.settings_language_zh),
        AppLanguage.English to stringResource(R.string.settings_language_en),
    )
    MeowPopupPreference(
        title = stringResource(R.string.settings_language),
        value = language,
        options = AppLanguage.entries,
        onValueChange = {
            language = it
            AppLanguage.apply(context, it)
        },
        modifier = Modifier.testTag("row.language"),
        optionLabel = labels::getValue,
    )
}
