package io.github.lingqiqi.meowceiler.ui.host

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** 图标不放这里：由 [rememberAppIcon] 按包名和槽位尺寸加载，共用一份缓存。 */
data class InstalledHost(
    val entry: HostEntry,
    val label: String,
)

/** 只返回真正装着且启用的宿主。 */
@Composable
fun rememberInstalledHosts(): List<InstalledHost> {
    val context = LocalContext.current
    return remember(context) {
        HostEntry.entries.mapNotNull { entry -> entry.resolve(context) }
    }
}

private fun HostEntry.resolve(context: Context): InstalledHost? = runCatching {
    val pm = context.packageManager
    val info = pm.getApplicationInfo(packageName, 0)
    if (!info.enabled) return null
    InstalledHost(entry = this, label = pm.getApplicationLabel(info).toString())
}.getOrNull()
