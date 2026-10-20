package io.github.lingqiqi.meowceiler.hook.rules.packagemanager

import android.content.pm.ApplicationInfo
import io.github.lingqiqi.meowceiler.shared.Preferences

internal object AllowHiddenApisForSystemApps : PackageManagerHooker(Preferences.PackageManager.SystemAppHiddenApis) {
    override fun onHook() {
        val applicationInfo = findClass("android.content.pm.ApplicationInfo")
        // if app is system app, allow to use hidden api, even if app not using a system signature
        // https://cs.android.com/android/platform/superproject/+/android-9.0.0_r61:frameworks/base/core/java/android/content/pm/ApplicationInfo.java;l=1678
        // private boolean isPackageWhitelistedForHiddenApis()
        val isPackageWhitelisted = applicationInfo.getDeclaredMethod("isPackageWhitelistedForHiddenApis")
        install(isPackageWhitelisted) {
            val info = thisObjectOrNull as? ApplicationInfo ?: return@install
            if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            ) {
                result = true
            }
        }
    }
}
