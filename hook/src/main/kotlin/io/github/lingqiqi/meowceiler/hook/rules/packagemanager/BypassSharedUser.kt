package io.github.lingqiqi.meowceiler.hook.rules.packagemanager

import android.content.pm.ApplicationInfo
import android.util.ArraySet
import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed

internal object BypassSharedUser : PackageManagerHooker(Preferences.PackageManager.SharedUser) {
    override fun onHook() {
        val sharedUserSetting = findClass("com.android.server.pm.SharedUserSetting")
        val packageSetting = findClass("com.android.server.pm.PackageSetting")
        val uidFlags = sharedUserSetting.getDeclaredField("uidFlags").apply { isAccessible = true }
        // 12 final ArraySet<PackageSetting> packages;
        // 13 private final WatchedArraySet<PackageSetting> mPackages, get ArraySet mStorage
        val packages = sharedUserSetting.getDeclaredField("mPackages").apply { isAccessible = true }
        val storage = packages.type.getDeclaredField("mStorage").apply { isAccessible = true }
        val sharedSignatures = sharedUserSetting.getDeclaredField("signatures").apply { isAccessible = true }
        val packageSignatures = packageSetting.getDeclaredField("signatures").apply { isAccessible = true }
        val signingDetails = sharedSignatures.type.getDeclaredField("mSigningDetails").apply { isAccessible = true }
        val signingDetailsClass = signingDetails.type
        val checkCapability =
            signingDetailsClass
                .getDeclaredMethod(
                    "checkCapability",
                    signingDetailsClass,
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
        val checkCapabilityInvoker =
            EzXposed.base
                .getInvoker(checkCapability)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
        val mergeLineageWith =
            signingDetailsClass
                .getDeclaredMethod(
                    "mergeLineageWith",
                    signingDetailsClass,
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }

        val removePackage = sharedUserSetting.getDeclaredMethod("removePackage", packageSetting)
        installAfter(removePackage) {
            val sharedUser = thisObjectOrNull ?: return@installAfter
            if (!isEnabled(Preferences.PackageManager.Digest)) return@installAfter
            if (uidFlags.getInt(sharedUser) and ApplicationInfo.FLAG_SYSTEM != 0) {
                return@installAfter // do not modify system's signature
            }
            if (result != true) return@installAfter
            val sharedUserSig = signingDetails.get(sharedSignatures.get(sharedUser)) ?: return@installAfter
            val packageStorage = storage.get(packages.get(sharedUser)) as ArraySet<*>
            if (packageStorage.isEmpty()) return@installAfter
            var newSignatures: Any? = null
            for (i in 0 until packageStorage.size) {
                val pkg = packageStorage.valueAt(i) ?: continue
                val packagesSignatures = signingDetails.get(packageSignatures.get(pkg)) ?: continue
                val b1 = checkCapabilityInvoker.invoke(packagesSignatures, sharedUserSig, 0) as Boolean
                val b2 = checkCapabilityInvoker.invoke(sharedUserSig, packagesSignatures, 0) as Boolean
                // if old signing exists, return
                if (b1 || b2) return@installAfter
                // otherwise, choose the first signature we meet, and merge with others if possible
                // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/pm/ReconcilePackageUtils.java;l=193;drc=c9a8baf585e8eb0f3272443930301a61331b65c1
                // respect to system
                newSignatures =
                    if (newSignatures == null) {
                        packagesSignatures
                    } else {
                        // MERGE_RESTRICTED_CAPABILITY
                        mergeLineageWith.invoke(newSignatures, packagesSignatures, 2)
                    }
            }
            if (newSignatures != null) signingDetails.set(sharedSignatures.get(sharedUser), newSignatures)
        }

        val addPackage = sharedUserSetting.getDeclaredMethod("addPackage", packageSetting)
        installAfter(addPackage) {
            val sharedUser = thisObjectOrNull ?: return@installAfter
            if (!isEnabled(Preferences.PackageManager.Digest)) return@installAfter
            if (uidFlags.getInt(sharedUser) and ApplicationInfo.FLAG_SYSTEM != 0) {
                return@installAfter // do not modify system's signature
            }
            val toAdd = arg(0) ?: return@installAfter
            val packageStorage = storage.get(packages.get(sharedUser)) as ArraySet<*>
            if (!packageStorage.contains(toAdd) || packageStorage.isEmpty()) return@installAfter
            val sharedUserSig = signingDetails.get(sharedSignatures.get(sharedUser)) ?: return@installAfter
            var newSignatures: Any? = null
            for (i in 0 until packageStorage.size) {
                val pkg = packageStorage.valueAt(i) ?: continue
                val packagesSignatures = signingDetails.get(packageSignatures.get(pkg)) ?: continue
                val b1 = checkCapabilityInvoker.invoke(packagesSignatures, sharedUserSig, 0) as Boolean
                val b2 = checkCapabilityInvoker.invoke(sharedUserSig, packagesSignatures, 0) as Boolean
                // if old signing exists, return
                if (b1 || b2) return@installAfter
                // otherwise, choose the first signature we meet, and merge with others if possible
                // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/pm/ReconcilePackageUtils.java;l=193;drc=c9a8baf585e8eb0f3272443930301a61331b65c1
                // respect to system
                newSignatures =
                    if (newSignatures == null) {
                        packagesSignatures
                    } else {
                        // MERGE_RESTRICTED_CAPABILITY
                        mergeLineageWith.invoke(newSignatures, packagesSignatures, 2)
                    }
            }
            if (newSignatures != null) signingDetails.set(sharedSignatures.get(sharedUser), newSignatures)
        }

        // for SharedUser
        // "Package " + packageName + " has a signing lineage " + "that diverges from the lineage of the sharedUserId"
        // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r1:frameworks/base/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java;l=725
        val hasCommonAncestor = signingDetailsClass.getDeclaredMethod("hasCommonAncestor", signingDetailsClass)
        install(hasCommonAncestor) {
            if (isEnabled(Preferences.PackageManager.Digest) &&
                // because of LSPosed's bug, we can't hook verifySignatures while deoptimize it
                Thread.currentThread().stackTrace.any { it.methodName == "verifySignatures" }
            ) {
                result = true
            }
        }
    }
}
