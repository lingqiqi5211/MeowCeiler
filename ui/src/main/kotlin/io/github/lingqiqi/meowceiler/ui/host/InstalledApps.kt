package io.github.lingqiqi.meowceiler.ui.host

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一条已安装应用。图标不在里面：由 [rememberAppIcon] 按包名和槽位尺寸另外加载。 */
@Immutable
data class InstalledApp(
    val packageName: String,
    val label: String,
    val system: Boolean,
)

/** [apps] 为 null 表示尚未读取。页面重建时先使用进程缓存，再刷新；常驻页面通过 [refresh] 更新。 */
@Stable
class InstalledAppsState internal constructor(private val context: Context) {
    var apps: List<InstalledApp>? by mutableStateOf(cachedApps)
        private set

    var loading: Boolean by mutableStateOf(false)
        private set

    private var token by mutableIntStateOf(0)

    fun refresh() {
        token++
    }

    internal suspend fun awaitRequests() {
        snapshotFlow { token }.collect {
            loading = true
            val loaded = withContext(Dispatchers.IO) { context.loadInstalledApps() }
            cachedApps = loaded
            apps = loaded
            loading = false
        }
    }
}

@Composable
fun rememberInstalledApps(): InstalledAppsState {
    val context = LocalContext.current.applicationContext
    val state = remember(context) { InstalledAppsState(context) }
    LaunchedEffect(state) { state.awaitRequests() }
    return state
}

@Volatile
private var cachedApps: List<InstalledApp>? = null

private fun Context.loadInstalledApps(): List<InstalledApp> = runCatching {
    val pm = packageManager
    pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        .filter(ApplicationInfo::enabled)
        .map {
            InstalledApp(
                packageName = it.packageName,
                label = pm.getApplicationLabel(it).toString(),
                system = it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        }
}.getOrDefault(emptyList())
