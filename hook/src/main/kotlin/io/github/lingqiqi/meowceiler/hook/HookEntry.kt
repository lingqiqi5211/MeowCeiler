package io.github.lingqiqi.meowceiler.hook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.lingqiqi.meowceiler.hook.base.BaseHooker
import io.github.lingqiqi.meowceiler.hook.scopes.PackageManager
import io.github.lingqiqi.meowceiler.hook.scopes.SystemUi
import io.github.lingqiqi.meowceiler.hook.util.EzLogBridge
import io.github.lingqiqi.meowceiler.hook.util.HookLogReporter
import io.github.lingqiqi.meowceiler.hook.util.HostApp
import io.github.lingqiqi.meowceiler.hook.util.MLog
import io.github.lingqiqi.meowceiler.hook.util.SafeMode
import io.github.lingqiqi.meowceiler.hook.util.Settings
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi.meowceiler.hook.scopes.Settings as SettingsScope

class HookEntry : XposedModule() {
    private var moduleEnabled = true

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzLogBridge.install()
        EzXposed.initOnModuleLoaded(this, param)
        initSettings()
        MLog.i("loaded: api=${EzXposed.frameworkApiVersion} process=${param.processName} systemServer=${EzXposed.isSystemServer}")

        EzXposed.onTargetReady { installTarget() }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        MLog.i("system_server starting")
        EzXposed.initOnSystemServerStarting(param)
        EzXposed.targetReadyFailure?.let { MLog.e("system_server target initialization failed", it) }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (EzXposed.isSystemServer) return
        if (!param.isFirstPackage) return
        if (rootHookerFor(param.packageName) == null) return
        EzXposed.initOnPackageLoaded(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (EzXposed.isSystemServer) return
        if (!param.isFirstPackage) return
        if (rootHookerFor(param.packageName) == null) return
        EzXposed.initOnPackageReady(param)
        HostApp.attach(param.applicationInfo)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        val accepted = EzXposed.handleHotReloading(param)
        if (accepted) Settings.close()
        return accepted
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        EzLogBridge.install()
        EzXposed.handleHotReloadedWithTargetReady(
            base = this,
            param = param,
            targetReady = {
                initSettings()
                installTarget(refreshSettings = true)
            },
        )
    }

    private fun initSettings() {
        Settings.init(this)
        moduleEnabled = Settings.read(Preferences.Module.Enabled)
        MLog.debugEnabled = Settings.read(Preferences.Module.Debug)
        SafeMode.init()
        HookLogReporter.init()
    }

    private fun installTarget(refreshSettings: Boolean = false) {
        if (EzXposed.isSystemServer) {
            MLog.i("system_server target ready")
            install(PackageManager, Scope.SystemServer, refreshSettings)
        } else {
            val packageName = EzXposed.packageName
            val root = rootHookerFor(packageName) ?: return
            install(root, packageName, refreshSettings)
        }
    }

    private fun install(
        root: BaseHooker,
        host: String,
        refreshSettings: Boolean = false,
    ) {
        if (!moduleEnabled) return
        if (SafeMode.isBlocked(host)) {
            MLog.w("$host is in safe mode, installing nothing")
            return
        }

        MLog.i("installing hooks for $host")
        if (refreshSettings) root.refreshAfterHotReload()
        root.performInit()
        root.updateParentState(true)
    }

    /** 未命中分支的类不会被加载 —— 运行时的进程间隔离全部来自这里。 */
    private fun rootHookerFor(packageName: String): BaseHooker? =
        when (packageName) {
            Scope.SystemUi -> SystemUi
            Scope.Settings -> SettingsScope
            else -> null
        }
}
