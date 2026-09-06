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

/**
 * 应用图标。走 iconloaderlib（`AppIconLoader`）而不是 `getApplicationIcon` + `toBitmap`：
 * 后者拿到什么画什么，自适应图标和老式位图混在一列里，形状和留白各不相同。
 * 这条路把自适应图标按设备遮罩裁一次，老式图标逐像素量过再缩放，一列图标的视觉重量才一致。
 *
 * `shrinkNonAdaptiveIcons` 保持 false：置 true 会给老式图标垫一层白底再缩进安全区，
 * 列表尺寸下那圈白边比图标本身还显眼。
 */
object AppIcons {
    private const val CacheBytes = 4 * 1024 * 1024

    private const val KeySeparator = '@'

    private val loaders = ConcurrentHashMap<Int, AppIconLoader>()

    private val cache = object : LruCache<String, Bitmap>(CacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    @Volatile private var receiver: BroadcastReceiver? = null

    fun cached(packageName: String, sizePx: Int): Bitmap? = cache.get(key(packageName, sizePx))

    /** 阻塞：由调用方放到 IO 线程。取不到图标（包没了、没图标）返回 null，槽位留空比塞个默认图标诚实。 */
    fun load(context: Context, packageName: String, sizePx: Int): Bitmap? {
        cached(packageName, sizePx)?.let { return it }
        val application = context.applicationContext
        registerReceiver(application)
        return runCatching {
            loaders.getOrPut(sizePx) { AppIconLoader(sizePx, false, application) }
                .loadIcon(application.packageManager.getApplicationInfo(packageName, 0))
        }.getOrNull()?.also { cache.put(key(packageName, sizePx), it) }
    }

    /** 装了、换了、卸了都要重解码：换过的包会换图标，卸掉的包不该留着位图。 */
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

/**
 * 图标槽位的实际像素数。
 *
 * 必须按像素而不是 dp 光栅化：dp 当像素用只在 1x 屏上对得上，3x 屏上会解出三分之一宽的位图再被放大，
 * 列表看着就是糊的。这个值也要进 remember 的 key，密度变了要重解。
 */
@Composable
@ReadOnlyComposable
fun appIconSizePx(size: Dp): Int = with(LocalDensity.current) { size.roundToPx() }

/**
 * [packageName] 在 [sizePx] 下的图标，缓存命中就是第一帧，未命中在 IO 线程解完再填。
 *
 * 状态本身也带 key：`remember` 不带的话，同一个调用点换了包名会先画着上一张图，
 * 下面那句「已经有图就不加载」再把这次加载跳过去。
 */
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
