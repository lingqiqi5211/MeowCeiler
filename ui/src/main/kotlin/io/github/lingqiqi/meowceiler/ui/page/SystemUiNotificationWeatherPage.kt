package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.component.FeatureSliderRow
import io.github.lingqiqi.meowceiler.ui.component.FeatureSwitchRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue

@Composable
fun SystemUiNotificationWeatherPage(onBack: () -> Unit) {
    val p = Preferences.SystemUi
    val enabled by rememberMeowPreferenceValue(p.NotificationWeather)
    HostPage(
        titleRes = R.string.systemui_notification_weather,
        hostPackage = Scope.SystemUi,
        onBack = onBack,
    ) {
        SettingsCard(testTag = "section.systemui.notification.weather") {
            FeatureSwitchRow(
                key = p.NotificationWeather,
                titleRes = R.string.preference_enabled,
                summaryRes = R.string.systemui_notification_weather_summary,
            )
        }
        if (!enabled) return@HostPage

        SettingsCard(testTag = "section.systemui.notification.weather.style") {
            FeatureSwitchRow(key = p.NotificationWeatherCity, titleRes = R.string.systemui_notification_weather_city)
            FeatureSwitchRow(key = p.NotificationWeatherIcon, titleRes = R.string.systemui_notification_weather_icon)
            FeatureSwitchRow(key = p.NotificationWeatherNewLine, titleRes = R.string.systemui_notification_weather_new_line)
            FeatureSwitchRow(key = p.NotificationWeatherBold, titleRes = R.string.systemui_notification_weather_bold)
            FeatureSliderRow(p.NotificationWeatherSize, R.string.systemui_notification_weather_size, 0f..30f, 1f, "sp")
            FeatureSliderRow(p.NotificationWeatherMargin, R.string.systemui_notification_weather_margin, 0f..40f, 0.5f, "dp")
        }
    }
}
