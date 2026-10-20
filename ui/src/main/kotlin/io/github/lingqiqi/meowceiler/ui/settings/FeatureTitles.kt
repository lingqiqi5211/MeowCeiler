package io.github.lingqiqi.meowceiler.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.ui.R

/**
 * 功能 id → 显示名。hook 侧拿不到模块资源，日志记录里只有 id，名字在这边补。
 * 加功能时跟着加一行；漏了日志页退回显示原始 id。
 */
private val Titles: Map<String, Int> =
    mapOf(
        Preferences.SystemUi.DoubleTapToSleep.name to R.string.systemui_double_tap_to_sleep,
        Preferences.SystemUi.Clock.name to R.string.systemui_clock,
        Preferences.SystemUi.NotificationWeather.name to R.string.systemui_notification_weather,
        Preferences.PackageManager.Enabled.name to R.string.package_manager_enabled,
        Preferences.PackageManager.Downgrade.name to R.string.package_manager_downgrade,
        Preferences.PackageManager.Verification.name to R.string.package_manager_verification,
        Preferences.PackageManager.Integrity.name to R.string.package_manager_integrity,
        Preferences.PackageManager.ResourceArscRestrictions.name to R.string.package_manager_resource_arsc_restrictions,
        Preferences.PackageManager.SharedUser.name to R.string.package_manager_shared_user,
        Preferences.PackageManager.Digest.name to R.string.package_manager_digest,
        Preferences.PackageManager.ExactSignature.name to R.string.package_manager_exact_signature,
        Preferences.PackageManager.PreviousSignatures.name to R.string.package_manager_previous_signatures,
        Preferences.PackageManager.VerificationAgent.name to R.string.package_manager_verification_agent,
        Preferences.PackageManager.LowApiCheck.name to R.string.package_manager_low_api_check,
        Preferences.PackageManager.Persistent.name to R.string.package_manager_persistent,
        Preferences.PackageManager.IsolationViolation.name to R.string.package_manager_isolation_violation,
        Preferences.PackageManager.AllowUpdateSystemApp.name to R.string.package_manager_allow_update_system_app,
        Preferences.PackageManager.SystemAppHiddenApis.name to R.string.package_manager_system_app_hidden_apis,
    )

@Composable
fun featureTitle(tag: String): String = Titles[tag]?.let { stringResource(it) } ?: tag
