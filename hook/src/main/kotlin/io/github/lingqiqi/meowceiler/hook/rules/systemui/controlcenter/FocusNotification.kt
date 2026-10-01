package io.github.lingqiqi.meowceiler.hook.rules.systemui.controlcenter

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import io.github.lingqiqi.meowceiler.hook.base.Feature
import io.github.lingqiqi.meowceiler.hook.base.StaticHooker
import io.github.lingqiqi.meowceiler.hook.util.MLog
import io.github.lingqiqi.meowceiler.hook.util.Settings
import io.github.lingqiqi.meowceiler.hook.utils.systemui.FocusAppReporter
import io.github.lingqiqi.meowceiler.hook.utils.systemui.SystemUiPlugins
import io.github.lingqiqi.meowceiler.shared.FocusAppRegistry
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.shared.hyperOsVersion
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.findAllMethods
import io.github.lingqiqi5211.ezhooktool.core.findField
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.getField
import io.github.lingqiqi5211.ezhooktool.core.toClassOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 超级岛通知白名单：宿主只让名单内的应用发，且 HyperOS 3 起还要过一次小米服务鉴权。
 *
 * HyperOS 3 由模块记录、控制媒体岛；HyperOS 4 只补充系统页面的应用记录，已有开关值不动。
 * 接点参考 HyperCeiler 的 FocusNotifLyric。
 */
@Feature(
    name = "超级岛通知白名单",
    since = "2026-09-08",
    target = "MIUISystemUIPlugin 17.03.260226.r / 16.0",
    updated = "2026-10-01",
)
object FocusNotification : StaticHooker(Preferences.SystemUi.FocusUnlock) {
    private const val FocusPlugin = "miui.systemui.notification.FocusNotificationPluginImpl"
    private const val SettingsManager = "miui.systemui.notification.NotificationSettingsManager"
    private const val FocusUtils = "miui.systemui.notification.focus.FocusNotifUtils"
    private const val AuthManager = "miui.systemui.notification.auth.AuthManager"

    /** 宿主自己的每应用超级岛记录，SystemUI 与设置侧都读它。 */
    private const val NotificationPrefs = "app_notification"

    // 独立保存成功来源，系统已有的 _focus 开关不能作为成功通知的证据。
    private const val SuccessfulPrefs = "meowceiler_successful_focus_apps"
    private const val SuccessfulPackages = "packages"

    /** 宿主写这份记录用的模式，读写两侧要一致。 */
    private const val MultiProcess = 4

    /** HyperOS 3 的媒体岛开关由模块管理，HyperOS 4 保留系统逻辑。 */
    private const val MediaIsland =
        "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaControllerImpl"
    private const val MediaBinder =
        "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewBinderImpl"

    private val known = ConcurrentHashMap.newKeySet<String>()
    private val successful = ConcurrentHashMap.newKeySet<String>()
    private val successfulLock = Mutex()
    private lateinit var getFocusState: Method
    private lateinit var setShowFocus: Method

    @Volatile
    private var mediaHidden: Set<String> = emptySet()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var mediaController: WeakReference<Any>? = null

    override fun onHook() {
        if (hyperOsVersion >= 4) installSystemAppList()
        SystemUiPlugins.onLoaded(FocusPlugin, id, ::unlock)
        if (hyperOsVersion != 3) return
        mediaHidden = Settings.read(Preferences.SystemUi.FocusMediaHidden)
        installMediaIsland()
        Settings.scope.launch {
            Settings.observe(Preferences.SystemUi.FocusMediaHidden).collectLatest { hidden ->
                val previous = mediaHidden
                mediaHidden = hidden
                if (previous != hidden) {
                    mainHandler.post {
                        runCatching {
                            val controller = mediaController?.get()
                            if (controller == null) return@runCatching
                            val data = controller.getField("topMediaData")
                            if (data == null) return@runCatching
                            val packageName = data.getField("packageName") as? String ?: return@runCatching
                            if ((packageName in previous) != (packageName in mediaHidden)) syncMediaIsland(controller, data)
                        }.onFailure { MLog.w(id, "cannot refresh media island", it) }
                    }
                }
            }
        }
    }

    private fun installMediaIsland() {
        val controller =
            MediaIsland.toClassOrNull() ?: run {
                MLog.w(id, "$MediaIsland not found, media island stays as is")
                return
            }
        controller.findMethod { name("addDynamicIslandView") }.createHook {
            intercept { chain ->
                val packageName = chain.args.lastOrNull()?.getField("packageName") as? String
                val owner = chain.thisObject
                if (owner != null && mediaController?.get() !== owner) mediaController = WeakReference(owner)
                if (packageName != null && packageName in mediaHidden) {
                    if (owner != null) {
                        runCatching { removeMediaIsland(owner) }
                            .onFailure { MLog.w(id, "cannot remove media island", it) }
                    }
                    null
                } else {
                    chain.proceed()
                }
            }
        }
        // 媒体数据绑定覆盖播放状态及应用切换，应用记录挂在这里。
        val binder =
            MediaBinder.toClassOrNull() ?: run {
                MLog.w(id, "$MediaBinder not found, media apps stay unlisted")
                return
            }
        binder
            .findMethod {
                name("bindMediaData")
                paramCount(1)
            }.createHook {
                before { param ->
                    val data = param.args.getOrNull(0)
                    val packageName = data?.getField("packageName") as? String
                    FocusAppReporter.record(FocusAppRegistry.Media, packageName)
                }
            }
    }

    private fun removeMediaIsland(controller: Any) {
        val key = controller.getField("currentKey") as? String ?: return
        controller.callMethod("removeDynamicIslandView\$1", key, true)
    }

    private fun syncMediaIsland(
        controller: Any,
        data: Any,
    ) {
        val packageName = data.getField("packageName") as? String ?: return
        if (packageName in mediaHidden) {
            removeMediaIsland(controller)
        } else if (controller.getField("currentKey") == null) {
            controller.callMethod(
                "addDynamicIslandView",
                controller.getField("miuiPlayerHolder"),
                controller.getField("miuiDummyPlayerHolder"),
                data,
            )
        }
    }

    private fun installSystemAppList() {
        val name = "com.miui.systemui.notification.NotificationSettingsManager"
        val manager = checkNotNull(name.toClassOrNull()) { "$name not found" }
        getFocusState =
            manager.findMethod {
                name("getFocusState")
                paramCount(2)
            }
        setShowFocus =
            manager.findMethod {
                name("setShowFocus")
                paramCount(3)
            }
        EzXposed.runOnApplicationAttach { context ->
            Settings.scope.launch {
                runCatching {
                    successfulLock.withLock {
                        successful.addAll(successfulPreferences(context).getStringSet(SuccessfulPackages, emptySet()).orEmpty())
                    }
                }.onFailure { MLog.w(id, "cannot load successful focus apps", it) }
            }
        }
        // 只补名单资格，不改页面最终结果；宿主的启动入口、排除名单和开关判断仍生效。
        manager
            .findMethod {
                name("isInSupportBlockFocusXmsList")
                paramCount(1)
            }.createHook {
                after { param ->
                    if (param.result == false && param.args[0] in successful) param.result = true
                }
            }
    }

    private fun unlock(classLoader: ClassLoader) {
        val managerClass = checkNotNull(SettingsManager.toClassOrNull(classLoader)) { "$SettingsManager not found" }
        val utilsClass = checkNotNull(FocusUtils.toClassOrNull(classLoader)) { "$FocusUtils not found" }
        val authClass = checkNotNull(AuthManager.toClassOrNull(classLoader)) { "$AuthManager not found" }
        managerClass
            .findAllMethods { name("canShowFocus") }
            .plus(managerClass.findAllMethods { name("canCustomFocus") })
            .forEach { method ->
                check(method.returnType == Boolean::class.javaPrimitiveType)
                method.createHook { returnConstant(true) }
            }
        utilsClass.findAllMethods { name("canShowFocus") }.forEach { method ->
            val index = method.parameterTypes.indexOfFirst { it == String::class.java }
            check(index >= 0 && method.returnType == Boolean::class.javaPrimitiveType)
            method.createHook {
                intercept { chain ->
                    if (hyperOsVersion < 4) return@intercept true
                    val context = chain.args.firstOrNull() as? Context ?: return@intercept chain.proceed()
                    val packageName = chain.args.getOrNull(index) as? String ?: return@intercept chain.proceed()
                    runCatching {
                        val preferences = context.getSharedPreferences(NotificationPrefs, MultiProcess)
                        getFocusState.invoke(null, preferences, "${packageName}_focus") != 0
                    }.getOrElse {
                        MLog.w(id, "cannot read focus permission", it)
                        chain.proceed()
                    }
                }
            }
        }
        approveAuth(authClass)
        if (hyperOsVersion >= 4) installSuccessfulNotifications(classLoader)
        for (name in listOf("AuthServiceCallback", "LowVersionAuthServiceCallback")) {
            val callbackName = "$AuthManager\$$name\$onAuthResult\$1"
            val callback = checkNotNull(callbackName.toClassOrNull(classLoader)) { "$callbackName not found" }
            val bundleField = callback.findField { name("\$authBundle") }
            callback
                .findMethod {
                    name("invokeSuspend")
                    paramCount(1)
                }.createHook {
                    before { param ->
                        runCatching {
                            (bundleField.get(param.thisObject) as? Bundle)?.putInt("result_code", 0)
                        }.onFailure { MLog.w(id, "cannot update auth result", it) }
                    }
                }
        }
    }

    private fun installSuccessfulNotifications(classLoader: ClassLoader) {
        val name = "miui.systemui.notification.focus.FocusNotificationController"
        val controller = checkNotNull(name.toClassOrNull(classLoader)) { "$name not found" }
        val authResult = controller.findField { name("authResult") }
        val inflateResult = controller.findField { name("inflateResult") }
        val showing = controller.findField { name("islandShowingMap") }
        val notifications = controller.findField { name("sbnMap") }
        val contextField = controller.findField { name("sysuiCtx") }
        val utilName = "miui.systemui.notification.NotificationUtil"
        val util = checkNotNull(utilName.toClassOrNull(classLoader)) { "$utilName not found" }
        val targetPackage =
            util.findMethod {
                name("getSbnTargetPkg")
                paramCount(2)
            }
        controller
            .findMethod {
                name("inflateFinishCallback")
                paramCount(3)
            }.createHook {
                intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val owner = chain.thisObject ?: return@runCatching
                        val key = chain.args[0] as? String ?: return@runCatching
                        // 失败也会回调构建完成；只有鉴权、构建成功且宿主已接纳岛数据才记入列表。
                        if ((authResult.get(owner) as? Map<*, *>)?.get(key) != true ||
                            (inflateResult.get(owner) as? Map<*, *>)?.get(key) != true ||
                            (showing.get(owner) as? Map<*, *>)?.containsKey(key) != true
                        ) {
                            return@runCatching
                        }
                        val sbn =
                            (notifications.get(owner) as? Map<*, *>)?.get(key) as? StatusBarNotification
                                ?: return@runCatching
                        val context = contextField.get(owner) as Context
                        val packageName = targetPackage.invoke(null, context, sbn) as? String ?: return@runCatching
                        register(context, packageName)
                    }.onFailure { MLog.w(id, "cannot record successful focus notification", it) }
                    result
                }
            }
    }

    /** HyperOS 3 起自定义超级岛要过小米的鉴权服务，结果码 0 才算通过。 */
    private fun approveAuth(auth: Class<*>) {
        auth.findAllMethods { name("getAuthStatus") }.forEach { method ->
            val index = method.parameterTypes.indexOfFirst { it == Bundle::class.java }
            check(index >= 0)
            method.createHook {
                before { param -> (param.args.getOrNull(index) as? Bundle)?.putInt("result_code", 0) }
            }
        }
    }

    private fun successfulPreferences(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(SuccessfulPrefs, Context.MODE_PRIVATE)

    /** 成功来源独立落盘，不覆盖用户已有的允许或禁止状态。 */
    private fun register(
        context: Context,
        packageName: String,
    ) {
        if (!FocusAppRegistry.isValid(FocusAppRegistry.Notification, packageName) || !known.add(packageName)) return
        Settings.scope.launch {
            runCatching {
                successfulLock.withLock {
                    val preferences = context.getSharedPreferences(NotificationPrefs, MultiProcess)
                    val key = "${packageName}_focus"
                    if (!preferences.contains(key)) setShowFocus.invoke(null, context, packageName, true)
                    val source = successfulPreferences(context)
                    val packages = source.getStringSet(SuccessfulPackages, emptySet()).orEmpty()
                    if (packageName !in packages) {
                        check(source.edit().putStringSet(SuccessfulPackages, HashSet(packages + packageName)).commit())
                    }
                    if (successful.add(packageName)) MLog.i(id, "recorded successful focus app $packageName")
                }
            }.onFailure {
                known.remove(packageName)
                MLog.w(id, "cannot record $packageName", it)
            }
        }
    }
}
