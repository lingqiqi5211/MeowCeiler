package io.github.lingqiqi.meowceiler.hook.rules.packagemanager

import io.github.lingqiqi.meowceiler.shared.Preferences

internal object BypassScanSignatureScheme : PackageManagerHooker(Preferences.PackageManager.Verification) {
    override fun onHook() {
        val scanPackageUtils = findClass("com.android.server.pm.ScanPackageUtils")
        val assertMinSignatureSchemeIsValid =
            scanPackageUtils.declaredMethods.first {
                it.name == "assertMinSignatureSchemeIsValid" && it.returnType == Void.TYPE
            }
        install(assertMinSignatureSchemeIsValid) { result = null }
    }
}
