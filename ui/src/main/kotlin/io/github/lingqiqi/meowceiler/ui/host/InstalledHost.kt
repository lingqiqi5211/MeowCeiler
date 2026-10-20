package io.github.lingqiqi.meowceiler.ui.host

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.lingqiqi.meowceiler.ui.R

/** 图标不放这里：由 [rememberAppIcon] 按包名和槽位尺寸加载，共用一份缓存。 */
data class InstalledHost(
    val entry: HostEntry,
    val label: String,
)

/** 系统框架直接提供入口，其余宿主只返回已安装且启用的应用。 */
@Composable
fun rememberInstalledHosts(): List<InstalledHost> {
    val context = LocalContext.current
    val systemFrameworkLabel = stringResource(R.string.scope_system_framework)
    return remember(context, systemFrameworkLabel) {
        HostEntry.entries.mapNotNull { entry ->
            if (entry == HostEntry.SystemFramework) {
                InstalledHost(entry, systemFrameworkLabel)
            } else {
                entry.resolve(context)
            }
        }
    }
}

private fun HostEntry.resolve(context: Context): InstalledHost? =
    runCatching {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        if (!info.enabled) return null
        InstalledHost(entry = this, label = pm.getApplicationLabel(info).toString())
    }.getOrNull()
