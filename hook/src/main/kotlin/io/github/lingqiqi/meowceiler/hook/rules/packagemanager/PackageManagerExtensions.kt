package io.github.lingqiqi.meowceiler.hook.rules.packagemanager

import io.github.lingqiqi.meowceiler.hook.base.BaseHooker
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook

internal object PackageManagerExtensions {
    fun attachTo(parent: BaseHooker) {
        parent.attach(DisableLowApiCheck)
        parent.attach(DisablePersistent)
        parent.attach(BypassIsolationViolation)
        parent.attach(AllowUpdateSystemApp)
    }
}

private object DisableLowApiCheck : PackageManagerHooker(Preferences.PackageManager.LowApiCheck) {
    override fun onHook() {
        val type = findClass("com.android.server.pm.InstallPackageHelper")
        val requestType = findClass("com.android.server.pm.InstallRequest")
        val argsField = requestType.getDeclaredField("mInstallArgs").apply { isAccessible = true }
        val flags = argsField.type.getDeclaredField("mInstallFlags").apply { isAccessible = true }
        val method = type.getDeclaredMethod("preparePackage", requestType)
        check(method.returnType == Void.TYPE)
        install(method) {
            val request = arg(0) ?: return@install
            val installArgs = argsField.get(request) ?: return@install
            flags.setInt(installArgs, flags.getInt(installArgs) or 0x01000000)
        }
    }
}

private object DisablePersistent : PackageManagerHooker(Preferences.PackageManager.Persistent) {
    private val installing = ThreadLocal.withInitial { false }

    override fun onHook() {
        val packageSetting = findClass("com.android.server.pm.PackageSetting")
        val isPersistent = packageSetting.getDeclaredMethod("isPersistent")
        check(isPersistent.returnType == Boolean::class.javaPrimitiveType)
        val installer = findClass("com.android.server.pm.InstallPackageHelper")
        val requestType = findClass("com.android.server.pm.InstallRequest")
        val preparePackage = installer.getDeclaredMethod("preparePackage", requestType)
        check(preparePackage.returnType == Void.TYPE)
        check(EzXposed.deoptimize(preparePackage)) { "failed to deoptimize preparePackage" }
        preparePackage.createHook {
            intercept { chain ->
                val previous = installing.get()
                try {
                    installing.set(true)
                    chain.proceed()
                } finally {
                    if (previous == true) installing.set(true) else installing.remove()
                }
            }
        }
        installAfter(isPersistent) {
            if (result == true && installing.get() == true) result = false
        }
    }
}

private object BypassIsolationViolation : PackageManagerHooker(Preferences.PackageManager.IsolationViolation) {
    override fun onHook() {
        val type = findClass("com.android.server.pm.PackageManagerServiceImpl")
        val parsedPackage = findClass("com.android.internal.pm.parsing.pkg.ParsedPackage")
        val installSource = findClass("com.android.server.pm.InstallSource")
        val method = type.getDeclaredMethod("verifyIsolationViolation", parsedPackage, installSource)
        check(method.returnType == Void.TYPE)
        install(method) { result = null }
    }
}

private object AllowUpdateSystemApp : PackageManagerHooker(Preferences.PackageManager.AllowUpdateSystemApp) {
    override fun onHook() {
        val type = findClass("com.android.server.pm.PackageManagerServiceImpl")
        val method = type.getDeclaredMethod("canBeUpdate", String::class.java)
        check(method.returnType == Void.TYPE)
        install(method) { result = null }
    }
}
