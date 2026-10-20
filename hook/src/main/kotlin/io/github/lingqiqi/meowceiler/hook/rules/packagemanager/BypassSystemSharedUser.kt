package io.github.lingqiqi.meowceiler.hook.rules.packagemanager

import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import java.lang.reflect.Field
import java.lang.reflect.Modifier

internal object BypassSystemSharedUser : PackageManagerHooker(Preferences.PackageManager.SharedUser) {
    override fun onHook() {
        // https://cs.android.com/android/platform/superproject/+/android-14.0.0_r75:frameworks/base/services/core/java/com/android/server/pm/ReconcilePackageUtils.java
        val reconcilePackageUtils = findClass("com.android.server.pm.ReconcilePackageUtils")
        val reconcilePackages = reconcilePackageUtils.declaredMethods.first { it.name == "reconcilePackages" }
        val allowNonPreloads =
            reconcilePackageUtils
                .getDeclaredField("ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS")
                .apply { isAccessible = true }
        check(Modifier.isStatic(allowNonPreloads.modifiers) && allowNonPreloads.type == Boolean::class.java)
        // Resolve the target field before reading its internal ART offset.
        allowNonPreloads.get(null)
        val fieldOffset = Field::class.java.getDeclaredMethod("getOffset").apply { isAccessible = true }
        val offset = (fieldOffset.invoke(allowNonPreloads) as Int).toLong()
        val unsafeClass = findClass("sun.misc.Unsafe")
        val unsafe =
            unsafeClass
                .getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null) as sun.misc.Unsafe
        check(EzXposed.deoptimize(reconcilePackages)) { "failed to deoptimize reconcilePackages" }
        reconcilePackages.createHook {
            intercept { chain ->
                // 锁使用宿主 Class，关闭状态和跨热重载的调用也不能读到临时放行标记。
                synchronized(reconcilePackageUtils) {
                    if (!enabled || !isEnabled(Preferences.PackageManager.Enabled) ||
                        !isEnabled(Preferences.PackageManager.Digest)
                    ) {
                        return@synchronized chain.proceed()
                    }
                    val previous = allowNonPreloads.getBoolean(null)
                    try {
                        unsafe.putBoolean(reconcilePackageUtils, offset, true)
                        chain.proceed()
                    } finally {
                        unsafe.putBoolean(reconcilePackageUtils, offset, previous)
                    }
                }
            }
        }
    }
}
