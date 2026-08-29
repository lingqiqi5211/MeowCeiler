package io.github.lingqiqi.meowceiler.hook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.lingqiqi.meowceiler.hook.base.BaseHooker
import io.github.lingqiqi.meowceiler.hook.scopes.SystemUi
// util.Settings 已经占了这个名字，scope 侧改名引入。
import io.github.lingqiqi.meowceiler.hook.scopes.Settings as SettingsScope
import io.github.lingqiqi.meowceiler.hook.util.HookLogReporter
import io.github.lingqiqi.meowceiler.hook.util.HostApp
import io.github.lingqiqi.meowceiler.hook.util.MLog
import io.github.lingqiqi.meowceiler.hook.util.SafeMode
import io.github.lingqiqi.meowceiler.hook.util.Settings
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi.meowceiler.shared.Scope
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed

class HookEntry : XposedModule() {

    private var moduleEnabled = true

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)
        Settings.init(this)
        SafeMode.init()
        HookLogReporter.init()

        moduleEnabled = Settings.read(Preferences.Module.Enabled)
        MLog.debugEnabled = Settings.read(Preferences.Module.Debug)
        MLog.i("loaded: api=${EzXposed.frameworkApiVersion} process=${param.processName}")

        // 必须等 onTargetReady：onPackageLoaded 阶段宿主自己的 dex 还没进 ClassLoader，
        // 那时 toClass() 一律 ClassNotFound。
        EzXposed.onTargetReady { install(EzXposed.packageName) }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        if (rootHookerFor(param.packageName) == null) return
        EzXposed.initOnPackageLoaded(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        if (rootHookerFor(param.packageName) == null) return
        EzXposed.initOnPackageReady(param)
        // DexKit 的缓存目录与宿主 apk 路径都要它；这时候 Application 还没建好，先记一份。
        HostApp.attach(param.applicationInfo)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        Settings.close()
        return EzXposed.handleHotReloading(param)
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        EzXposed.handleHotReloadedWithTargetReady(
            base = this,
            param = param,
            targetReady = {
                Settings.init(this@HookEntry)
                SafeMode.init()
                HookLogReporter.init()
                install(EzXposed.packageName)
            },
        )
    }

    private fun install(packageName: String) {
        if (!moduleEnabled) return
        if (SafeMode.isBlocked(packageName)) {
            MLog.w("$packageName is in safe mode, installing nothing")
            return
        }

        val root = rootHookerFor(packageName) ?: return
        MLog.i("installing hooks for $packageName")
        root.performInit()
        root.updateParentState(true)
    }

    /** 未命中分支的类不会被加载 —— 运行时的进程间隔离全部来自这里。 */
    private fun rootHookerFor(packageName: String): BaseHooker? = when (packageName) {
        Scope.SystemUi -> SystemUi
        Scope.Settings -> SettingsScope
        else -> null
    }
}
