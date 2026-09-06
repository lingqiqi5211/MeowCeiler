package io.github.lingqiqi.meowceiler.ui.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lingqiqi.meowceiler.ui.R
import io.github.lingqiqi.meowceiler.ui.component.SettingsCard
import io.github.lingqiqi.meowceiler.ui.component.SettingsInfoRow
import io.github.lingqiqi.meowceiler.ui.component.SettingsSection
import io.github.lingqiqi5211.meowui.component.MeowPreferenceScreen
import io.github.lingqiqi5211.meowui.theme.MeowTheme

private const val ProjectUrl = "https://github.com/lingqiqi5211/MeowCeiler"

private val HeroIconSize = 72.dp

/**
 * 应用自己的启动器图标。读自身资源而不是复制一份矢量；不用 `getApplicationIcon()`，HyperOS 会先整形。
 * 边界直接给目标尺寸：AdaptiveIconDrawable 自己负责内缩和遮罩，再裁一次会切掉边。
 */
@Composable
fun AboutPage(onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val versionName = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    MeowPreferenceScreen {
        AboutHero(versionName)

        SettingsCard(testTag = "section.about.links") {
            MeowActionPreference(
                title = stringResource(R.string.about_source),
                navigation = true,
                onClick = { uriHandler.openUri(ProjectUrl) },
            )
            MeowActionPreference(
                title = stringResource(R.string.about_licenses),
                navigation = true,
                onClick = onOpenLicenses,
            )
        }

        SettingsSection(titleRes = R.string.about_device, testTag = "section.about.device") {
            SettingsInfoRow(R.string.about_hyperos, hyperOsName(), "row.about.hyperos")
            SettingsInfoRow(
                R.string.about_android,
                "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "row.about.android",
            )
            SettingsInfoRow(R.string.about_model, Build.MODEL, "row.about.model")
        }
    }
}

@Composable
private fun AboutHero(versionName: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        rememberLauncherIcon(HeroIconSize)?.let { icon ->
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(HeroIconSize),
            )
        }
        Spacer(Modifier.height(12.dp))
        BasicText(
            text = stringResource(R.string.app_name),
            style = MeowTheme.typography.pageTitle.copy(color = MeowTheme.colors.onBackground),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = versionName,
            style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurfaceVariant),
        )
    }
}

@Composable
private fun rememberLauncherIcon(size: Dp): ImageBitmap? {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    return remember(context, sizePx) {
        val icon = context.getDrawable(context.applicationInfo.icon) ?: return@remember null
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, sizePx, sizePx)
        icon.draw(Canvas(bitmap))
        bitmap.asImageBitmap()
    }
}

private fun hyperOsName(): String = readProperty("ro.mi.os.version.name")

private fun readProperty(key: String): String = runCatching {
    Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java, String::class.java)
        .invoke(null, key, "") as? String
}.getOrNull().orEmpty()
