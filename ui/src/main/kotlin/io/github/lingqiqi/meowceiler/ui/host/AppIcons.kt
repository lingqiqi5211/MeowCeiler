package io.github.lingqiqi.meowceiler.ui.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.appiconloader.AppIconLoader
import java.util.concurrent.ConcurrentHashMap

/** 使用 AppIconLoader 统一图标遮罩和视觉尺寸；不为非自适应图标添加白底。 */
object AppIcons {
    private const val CacheBytes = 4 * 1024 * 1024

    private const val KeySeparator = '@'

    private val loaders = ConcurrentHashMap<Int, AppIconLoader>()

    private val cache = object : LruCache<String, Bitmap>(CacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    @Volatile private var receiver: BroadcastReceiver? = null

    fun cached(packageName: String, sizePx: Int): Bitmap? = cache.get(key(packageName, sizePx))

    /** 阻塞调用，须在 IO 线程执行；加载失败返回 null。 */
    fun load(context: Context, packageName: String, sizePx: Int): Bitmap? {
        cached(packageName, sizePx)?.let { return it }
        val application = context.applicationContext
        registerReceiver(application)
        return runCatching {
            loaders.getOrPut(sizePx) { AppIconLoader(sizePx, false, application) }
                .loadIcon(application.packageManager.getApplicationInfo(packageName, 0))
        }.getOrNull()?.also { cache.put(key(packageName, sizePx), it) }
    }

    private fun invalidate(packageName: String) {
        cache.snapshot().keys
            .filter { it.substringBeforeLast(KeySeparator) == packageName }
            .forEach(cache::remove)
    }

    @Synchronized
    private fun registerReceiver(application: Context) {
        if (receiver != null) return
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.data?.schemeSpecificPart?.let(::invalidate)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        application.registerReceiver(created, filter, Context.RECEIVER_NOT_EXPORTED)
        receiver = created
    }

    private fun key(packageName: String, sizePx: Int) = "$packageName$KeySeparator$sizePx"
}

/** 按实际像素尺寸解码，并将尺寸纳入缓存键，避免密度变化后图标模糊。 */
@Composable
@ReadOnlyComposable
fun appIconSizePx(size: Dp): Int = with(LocalDensity.current) { size.roundToPx() }

/** 图标状态按包名和像素尺寸区分；缓存未命中时在 IO 线程加载。 */
@Composable
fun rememberAppIcon(packageName: String, sizePx: Int): ImageBitmap? {
    val context = LocalContext.current
    var icon by remember(context, packageName, sizePx) {
        mutableStateOf(AppIcons.cached(packageName, sizePx)?.asImageBitmap())
    }
    LaunchedEffect(context, packageName, sizePx) {
        if (icon != null) return@LaunchedEffect
        icon = withContext(Dispatchers.IO) {
            AppIcons.load(context, packageName, sizePx)
        }?.asImageBitmap()
    }
    return icon
}
