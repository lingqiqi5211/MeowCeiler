package io.github.lingqiqi.meowceiler.hook.utils.systemui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import io.github.lingqiqi.meowceiler.hook.util.MLog
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.getField
import io.github.lingqiqi5211.ezhooktool.core.getStaticField
import io.github.lingqiqi5211.ezhooktool.core.toClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * 系统天气：读 Provider 的当前天气，取天气应用的图标，唤起天气应用。
 *
 * 只读内容与资源，不进天气应用的进程，作用域仍旧只有系统界面。
 */
object SystemWeather {
    private const val WeatherPackage = "com.miui.weather2"
    private const val AssetImages = "flutter_assets/assets/images/"
    private val uri = Uri.parse("content://weather/weather")

    /** 下标即 Provider 的 `weather_type`，与天气应用 `array/weather_type` 同序。 */
    private val iconNames = arrayOf(
        "icon_sunny", "icon_cloudy", "icon_overcast", "icon_fog",
        "icon_heavy_rain", "icon_heavy_rain", "icon_heavy_rain", "icon_t_storm",
        "icon_moderate_rain", "icon_heavy_rain", "icon_moderate_rain", "icon_light_rain",
        "icon_rain_snow", "icon_heavy_snow", "icon_moderate_snow", "icon_heavy_snow",
        "icon_moderate_snow", "icon_light_snow", "icon_sand", "icon_sand",
        "icon_sand", "icon_float_dirt", "icon_ice_rain", "icon_float_dirt",
        "icon_pm_dirt", "icon_ice_rain",
    )

    private val bitmaps = HashMap<String, Bitmap>()

    @Volatile
    private var cachedContext: Context? = null

    data class Weather(
        val city: String,
        val description: String,
        val temperature: String,
        val type: Int,
    ) {
        /** [describe] 为 false 时省掉天气描述，图标那一路用它。 */
        fun text(showCity: Boolean, describe: Boolean = true): String =
            listOfNotNull(city.takeIf { showCity }, description.takeIf { describe }, temperature)
                .filter(String::isNotBlank)
                .joinToString(" ")
    }

    /** 图标插在地区与温度之间，替掉天气描述那一段。 */
    fun format(weather: Weather, showCity: Boolean, icon: Drawable?, iconSize: Int): CharSequence {
        if (icon == null) return weather.text(showCity)
        val builder = SpannableStringBuilder()
        if (showCity && weather.city.isNotBlank()) builder.append(weather.city).append(' ')
        icon.setBounds(0, 0, iconSize, iconSize)
        val start = builder.length
        builder.append(' ')
        builder.setSpan(ImageSpan(icon, ImageSpan.ALIGN_CENTER), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (weather.temperature.isNotBlank()) builder.append(' ').append(weather.temperature)
        return builder
    }

    /** Provider 变更后延迟 200ms 再读：天气应用会连着写好几列。 */
    fun observe(context: Context, tag: String): Flow<Weather?> = callbackFlow {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val registered = runCatching { resolver.registerContentObserver(uri, true, observer) }
            .onFailure { MLog.w(tag, "Cannot observe system weather", it) }
            .isSuccess
        trySend(Unit)
        awaitClose { if (registered) runCatching { resolver.unregisterContentObserver(observer) } }
    }.conflate().map {
        delay(200)
        runCatching { query(context) }
            .onFailure { MLog.w(tag, "Cannot read system weather", it) }
            .getOrNull()
    }.flowOn(Dispatchers.IO)

    private fun query(context: Context): Weather? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            fun column(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name)).orEmpty()
            Weather(
                city = column("city_name"),
                description = column("description"),
                temperature = column("temperature"),
                type = column("weather_type").toIntOrNull() ?: -1,
            )
        }

    /**
     * 取天气应用的图标。宿主自带的 `weather_icon_N` 另有一套编号，且画得糙。
     *
     * 新版天气应用是 Flutter 写的，图标在 flutter_assets 里；旧版在资源表。位图按名字留着，
     * 每次现包一层 [BitmapDrawable]：调用方会改 bounds，共用一个实例会互相盖掉。
     */
    fun icon(context: Context, type: Int, tag: String): Drawable? {
        val name = iconNames.getOrNull(type) ?: return null
        val weather = weatherContext(context, tag) ?: return null
        bitmaps[name]?.let { return BitmapDrawable(weather.resources, it) }
        val bitmap = runCatching {
            weather.assets.open("$AssetImages$name.webp").use(BitmapFactory::decodeStream)
        }.getOrNull()
        if (bitmap != null) {
            bitmaps[name] = bitmap
            return BitmapDrawable(weather.resources, bitmap)
        }
        val id = weather.resources.getIdentifier(name, "drawable", WeatherPackage)
        if (id == 0) return null
        return runCatching { weather.getDrawable(id) }
            .onFailure { MLog.w(tag, "Cannot load weather icon $name", it) }
            .getOrNull()
    }

    private fun weatherContext(context: Context, tag: String): Context? {
        cachedContext?.let { return it }
        return runCatching { context.createPackageContext(WeatherPackage, 0) }
            .onFailure { MLog.w(tag, "Weather app unavailable", it) }
            .getOrNull()
            ?.also { cachedContext = it }
    }

    /** 走宿主的 ActivityStarter：通知中心是锁屏之上的窗口，直接 startActivity 不会收起面板。 */
    fun open(tag: String) {
        runCatching {
            val intent = Intent().apply {
                component = ComponentName(WeatherPackage, "$WeatherPackage.ActivityWeatherMain")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val stub = "miui.stub.MiuiStub".toClass().getStaticField("INSTANCE") ?: error("MiuiStub unavailable")
            val provider = stub.getField("mSysUIProvider") ?: error("SystemUI provider unavailable")
            val starter = provider.getField("mActivityStarter")?.callMethod("get") ?: error("ActivityStarter unavailable")
            starter.callMethod("startActivity", intent, true)
        }.onFailure { MLog.w(tag, "Cannot open Weather", it) }
    }
}
