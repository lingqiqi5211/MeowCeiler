// DexKit 2.2.0 里带 key 的缓存查询标了 @DexKitExperimentalApi。这里显式 opt-in：缓存正是引它的
// 理由，不用就得自己再写一套。升级 DexKit 时先看这个 API 有没有变。
@file:OptIn(DexKitExperimentalApi::class)

package io.github.lingqiqi.meowceiler.hook.util

import android.content.pm.ApplicationInfo
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.DexKitCacheBridge.RecyclableBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * DexKit 会话。
 *
 * 原生桥不便宜（要把宿主整个 dex 读进内存），所以按会话开：一个 scope 里所有需要 DexKit 的功能
 * 包在同一个 [session] 里装，块结束就释放。查询结果按 key 落盘，第二次启动直接命中缓存、
 * 根本不会创建原生桥。
 *
 * 用法（在 scope 的 `onInit` 里）：
 * ```
 * object SystemUi : StaticHooker() {
 *     override fun onInit() {
 *         attach(DoubleTapToSleep)          // 不用 DexKit 的照旧
 *         DexKit.session {                  // 桥只开一次，块结束即释放
 *             attach(SomeDexKitHooker)
 *         }
 *     }
 * }
 * ```
 *
 * key 由调用方负责唯一，约定用 `功能id.用途`。**改了查询条件就要换 key**（或改宿主版本），
 * 否则会命中旧结果。
 */
object DexKit {

    /** 宿主 cache 目录下的子目录名。宿主 apk 路径与目录都来自 [HostApp]。 */
    private const val CacheDir = "meowceiler"
    private const val CacheFile = "dexkit.json"

    private val lock = Any()

    private var depth = 0
    private var cache: DexKitCache? = null
    private var bridge: RecyclableBridge? = null

    /**
     * 原生库在一个进程里只能被一个 classloader 加载。热重载后的新 generation 加载会失败 ——
     * 那之后只能吃缓存，缓存没命中的 key 直接报错，不静默返回错的东西。
     */
    private var nativeReady = false

    fun <T> session(block: () -> T): T {
        open()
        return try {
            block()
        } finally {
            close()
        }
    }

    /** 查一个方法。 */
    fun method(key: String, query: RecyclableBridge.FindMethodBuilder): Method =
        lookup(key) { it.getMethod(key, query).getMethodInstance(EzXposed.classLoader) }

    /** 查一个构造器。查询条件写 `name("<init>")`。 */
    fun constructor(key: String, query: RecyclableBridge.FindMethodBuilder): Constructor<*> =
        lookup(key) { it.getMethod(key, query).getConstructorInstance(EzXposed.classLoader) }

    /** 查一组同名 / 同特征的方法。 */
    fun methods(key: String, query: RecyclableBridge.FindMethodBuilder): List<Method> =
        lookup(key) { bridge ->
            bridge.getMethods(key, query).map { it.getMethodInstance(EzXposed.classLoader) }
        }

    fun clazz(key: String, query: RecyclableBridge.FindClassBuilder): Class<*> =
        lookup(key) { it.getClass(key, query).getInstance(EzXposed.classLoader) }

    fun classes(key: String, query: RecyclableBridge.FindClassBuilder): List<Class<*>> =
        lookup(key) { bridge ->
            bridge.getClasses(key, query).map { it.getInstance(EzXposed.classLoader) }
        }

    fun field(key: String, query: RecyclableBridge.FindFieldBuilder): Field =
        lookup(key) { it.getField(key, query).getFieldInstance(EzXposed.classLoader) }

    fun fields(key: String, query: RecyclableBridge.FindFieldBuilder): List<Field> =
        lookup(key) { bridge ->
            bridge.getFields(key, query).map { it.getFieldInstance(EzXposed.classLoader) }
        }

    private fun <T> lookup(key: String, block: (RecyclableBridge) -> T): T {
        require(key.isNotEmpty()) { "DexKit key must not be empty" }
        val current = synchronized(lock) { bridge }
            ?: error("DexKit.$key queried outside DexKit.session { }")
        return try {
            block(current)
        } catch (error: UnsatisfiedLinkError) {
            // 只有缓存未命中、需要真的开桥时才会走到这里。
            throw IllegalStateException(
                "DexKit native bridge unavailable (libdexkit is already owned by an older " +
                    "module generation). Key '$key' is not cached; restart the host process.",
                error,
            )
        }
    }

    private fun open() {
        synchronized(lock) {
            if (depth++ > 0) return

            val appInfo = HostApp.applicationInfo
                ?: error("DexKit needs the host ApplicationInfo; HookEntry.onPackageReady did not run")

            if (cache == null) {
                val current = DexKitCache(cacheFileOf(appInfo), stampOf(appInfo))
                runCatching { DexKitCacheBridge.init(current) }
                    // 同一 generation 里已经 init 过；DexKit 只允许一次，复用即可。
                    .onFailure { if (it !is IllegalStateException) throw it }
                cache = current
                nativeReady = runCatching { System.loadLibrary("dexkit") }
                    .onFailure { MLog.w("libdexkit not loadable, DexKit falls back to cache only: ${it.message}") }
                    .isSuccess
            }

            bridge = createBridge(appInfo)
            MLog.d("dexkit session opened (native=$nativeReady)")
        }
    }

    private fun close() {
        synchronized(lock) {
            if (--depth > 0) return
            depth = 0
            bridge?.close()
            bridge = null
            cache?.flush()
            MLog.d("dexkit session closed")
        }
    }

    /** 有 split APK 时必须走 classLoader，`sourceDir` 只覆盖 base.apk。 */
    private fun createBridge(appInfo: ApplicationInfo): RecyclableBridge {
        val tag = appInfo.packageName
        return if (appInfo.splitSourceDirs.isNullOrEmpty()) {
            DexKitCacheBridge.create(tag, appInfo.sourceDir)
        } else {
            DexKitCacheBridge.create(tag, EzXposed.classLoader)
        }
    }

    private fun cacheFileOf(appInfo: ApplicationInfo): File =
        File(File(File(appInfo.dataDir, "cache"), CacheDir), CacheFile)

    /** 宿主 apk 换了(升级、换 rom)，上一版扒出来的签名就不能用了。 */
    private fun stampOf(appInfo: ApplicationInfo): String =
        "${appInfo.packageName}:${File(appInfo.sourceDir).lastModified()}"
}
