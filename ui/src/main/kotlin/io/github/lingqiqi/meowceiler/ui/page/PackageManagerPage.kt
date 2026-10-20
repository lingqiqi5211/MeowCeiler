package io.github.lingqiqi.meowceiler.ui.page

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.component.FeatureSwitchRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsSection
import io.github.lingqiqi5211.meowui.component.MeowPreferencePage
import io.github.lingqiqi5211.meowui.component.MeowTip
import io.github.lingqiqi5211.meowui.component.MeowTipStyle
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue

@Composable
fun PackageManagerPage(onBack: () -> Unit) {
    val enabled by rememberMeowPreferenceValue(Preferences.PackageManager.Enabled)

    MeowPreferencePage(title = stringResource(R.string.package_manager_title), onBackClick = onBack) {
        MeowTip(
            title = stringResource(R.string.package_manager_tip_title),
            message = stringResource(R.string.package_manager_tip),
            style = MeowTipStyle.Warning,
        )
        SettingsSection(
            titleRes = R.string.package_manager_switch,
            testTag = "section.package_manager.switch",
        ) {
            FeatureSwitchRow(
                key = Preferences.PackageManager.Enabled,
                titleRes = R.string.package_manager_enabled,
            )
        }
        SettingsSection(
            titleRes = R.string.package_manager_basic,
            testTag = "section.package_manager.basic",
        ) {
            FeatureSwitchRow(
                key = Preferences.PackageManager.Downgrade,
                titleRes = R.string.package_manager_downgrade,
                summaryRes = R.string.package_manager_downgrade_desc,
                visible = enabled,
            )
            FeatureSwitchRow(
                key = Preferences.PackageManager.Verification,
                titleRes = R.string.package_manager_verification,
                summaryRes = R.string.package_manager_verification_desc,
                visible = enabled,
            )
            FeatureSwitchRow(
                key = Preferences.PackageManager.Integrity,
                titleRes = R.string.package_manager_integrity,
                summaryRes = R.string.package_manager_integrity_desc,
                visible = enabled,
            )
            FeatureSwitchRow(
                key = Preferences.PackageManager.SharedUser,
                titleRes = R.string.package_manager_shared_user,
                summaryRes = R.string.package_manager_shared_user_desc,
                visible = enabled,
            )
            FeatureSwitchRow(
                key = Preferences.PackageManager.Digest,
                titleRes = R.string.package_manager_digest,
                summaryRes = R.string.package_manager_digest_desc,
                visible = enabled,
            )
        }
        SettingsSection(
            titleRes = R.string.package_manager_extended,
            testTag = "section.package_manager.extended",
        ) {
            FeatureSwitchRow(
                key = Preferences.PackageManager.ExactSignature,
                titleRes = R.string.package_manager_exact_signature,
                summaryRes = R.string.package_manager_exact_signature_desc,
                visible = enabled,
            )
            FeatureSwitchRow(
                key = Preferences.PackageManager.PreviousSignatures,
                titleRes = R.string.package_manager_previous_signatures,
                summaryRes = R.string.package_manager_previous_signatures_desc,
                visible = enabled,
            )
            FeatureSwitchRow(
                key = Preferences.PackageManager.VerificationAgent,
                titleRes = R.string.package_manager_verification_agent,
                summaryRes = R.string.package_manager_verification_agent_desc,
                visible = enabled,
            )
            FeatureSwitchRow(
                key = Preferences.PackageManager.LowApiCheck,
                titleRes = R.string.package_manager_low_api_check,
                summaryRes = R.string.package_manager_low_api_check_desc,
                visible = enabled,
            )
            FeatureSwitchRow(
                key = Preferences.PackageManager.Persistent,
                titleRes = R.string.package_manager_persistent,
                summaryRes = R.string.package_manager_persistent_desc,
                visible = enabled,
            )
            FeatureSwitchRow(
                key = Preferences.PackageManager.IsolationViolation,
                titleRes = R.string.package_manager_isolation_violation,
                summaryRes = R.string.package_manager_isolation_violation_desc,
                visible = enabled,
            )
            FeatureSwitchRow(
                key = Preferences.PackageManager.AllowUpdateSystemApp,
                titleRes = R.string.package_manager_allow_update_system_app,
                summaryRes = R.string.package_manager_allow_update_system_app_desc,
                visible = enabled,
            )
            FeatureSwitchRow(
                key = Preferences.PackageManager.ResourceArscRestrictions,
                titleRes = R.string.package_manager_resource_arsc_restrictions,
                visible = enabled,
            )
            FeatureSwitchRow(
                key = Preferences.PackageManager.SystemAppHiddenApis,
                titleRes = R.string.package_manager_system_app_hidden_apis,
                visible = enabled,
            )
        }
    }
}
