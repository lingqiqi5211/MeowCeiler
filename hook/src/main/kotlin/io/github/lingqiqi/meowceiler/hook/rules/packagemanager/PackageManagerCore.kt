package io.github.lingqiqi.meowceiler.hook.rules.packagemanager

import android.content.pm.Signature
import android.os.Build
import io.github.lingqiqi.meowceiler.hook.base.DynamicHooker
import io.github.lingqiqi.meowceiler.hook.base.StaticHooker
import io.github.lingqiqi.meowceiler.hook.util.MLog
import io.github.lingqiqi.meowceiler.hook.util.Settings
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import io.github.lingqiqi5211.meowui.core.preference.PreferenceKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object PackageManagerCore : StaticHooker() {
    override fun onInit() {
        PackageManagerSwitchState.bind()
        PackageManagerExtensions.attachTo(this)
        attach(BypassDowngrade)
        attach(BypassVerification)
        attach(RecoverV1Signature)
        attach(BypassIntegrity)
        attach(BypassResourceArscRestrictions)
        attach(BypassExactSignature)
        attach(BypassDigestCapabilities)
        attach(BypassSharedUser)
        attach(BypassSystemSharedUser)
        attach(BypassScanSignatureScheme)
        attach(AllowHiddenApisForSystemApps)
        attach(AllowPreviousSignatures)
        attach(BypassVerificationAgent)
        attach(BypassApkSigningBlockUtils)
        attach(BypassMessageDigest)
        attach(BypassStrictJarVerifier)
    }
}

internal abstract class PackageManagerHooker(
    switch: PreferenceKey<Boolean>,
) : DynamicHooker(switch) {
    protected open val enabledForCallback: Boolean get() = enabled

    protected fun findClass(name: String): Class<*> = EzXposed.classLoader.loadClass(name)

    protected fun install(
        method: Method,
        block: HookParam.() -> Unit,
    ) {
        if (!EzXposed.deoptimize(method)) MLog.w(id, "failed to deoptimize ${method.name}")
        method.createBeforeHook {
            if (enabledForCallback && isEnabled(Preferences.PackageManager.Enabled)) block(it)
        }
    }

    protected fun installAfter(
        method: Method,
        block: HookParam.() -> Unit,
    ) {
        if (!EzXposed.deoptimize(method)) MLog.w(id, "failed to deoptimize ${method.name}")
        method.createAfterHook {
            if (enabled && isEnabled(Preferences.PackageManager.Enabled)) block(it)
        }
    }

    protected fun installAfter(
        constructor: Constructor<*>,
        block: HookParam.() -> Unit,
    ) {
        if (!EzXposed.deoptimize(constructor)) MLog.w(id, "failed to deoptimize ${constructor.declaringClass.simpleName}")
        constructor.createAfterHook {
            if (enabled && isEnabled(Preferences.PackageManager.Enabled)) block(it)
        }
    }

    protected fun isEnabled(key: PreferenceKey<Boolean>): Boolean = PackageManagerSwitchState.isEnabled(key)
}

internal object PackageManagerSwitchState {
    private val values = ConcurrentHashMap<String, Boolean>()
    private val jobs = mutableListOf<Job>()

    fun bind() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        Preferences.PackageManager.all.forEach { key ->
            values[key.name] = Settings.read(key) == true
            jobs +=
                Settings.scope.launch {
                    Settings.observe(key).collect { value -> values[key.name] = value == true }
                }
        }
    }

    fun isEnabled(key: PreferenceKey<Boolean>): Boolean = values[key.name] == true
}

private object BypassDowngrade : PackageManagerHooker(Preferences.PackageManager.Downgrade) {
    override fun onHook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val clazz = findClass("com.android.server.pm.PackageManagerServiceUtils")
        clazz.declaredMethods
            .filter { it.name == "checkDowngrade" && it.returnType == Void.TYPE }
            .filter {
                it.parameterTypes.lastOrNull()?.name ==
                    "android.content.pm.PackageInfoLite"
            }.forEach { method ->
                // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r1:frameworks/base/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java;l=1375
                // public static void checkDowngrade(com.android.server.pm.parsing.pkg.AndroidPackage before, PackageInfoLite after)
                // https://cs.android.com/android/platform/superproject/+/android-14.0.0_r1:frameworks/base/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java;l=1499
                // public static void checkDowngrade(com.android.server.pm.pkg.AndroidPackage before, PackageInfoLite after)
                // OneUI inlines the checkDowngrade methods into one, so we need to hook all methods with the same name and parameter types
                install(method) { result = null }
            }
    }
}

private object BypassVerification : PackageManagerHooker(Preferences.PackageManager.Verification) {
    override fun onHook() {
        val clazz = findClass("com.android.server.pm.PackageManagerServiceUtils")
        val method =
            clazz.declaredMethods.first {
                it.name == "verifySignatures" && it.returnType == Boolean::class.java
            }
        // https://cs.android.com/android/platform/superproject/+/android-9.0.0_r61:frameworks/base/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java;l=552
        // public static boolean verifySignatures(
        //     PackageSetting pkgSetting,
        //     PackageSetting disabledPkgSetting,
        //     PackageParser.SigningDetails parsedSignatures,
        //     boolean compareCompat,
        //     boolean compareRecover)
        // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r34:frameworks/base/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java;l=625
        // public static boolean verifySignatures(
        //     PackageSetting pkgSetting,
        //     PackageSetting disabledPkgSetting,
        //     PackageParser.SigningDetails parsedSignatures,
        //     boolean compareCompat,
        //     boolean compareRecover,
        //     boolean isRollback)
        install(method) { result = false }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // ensure verifySignatures success
            // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java;l=621
            val canJoinSharedUserIdMethod = clazz.declaredMethods.first { it.name == "canJoinSharedUserId" }
            if (!EzXposed.deoptimize(canJoinSharedUserIdMethod)) {
                MLog.w(id, "failed to deoptimize canJoinSharedUserId")
            }
        }
    }
}

private object BypassIntegrity : PackageManagerHooker(Preferences.PackageManager.Integrity) {
    override val enabledForCallback: Boolean
        get() = enabled || isEnabled(Preferences.PackageManager.Verification)

    override fun onHook() {
        val clazz = findClass("android.util.apk.ApkSignatureVerifier")
        // No signature found in package of version " + minSignatureSchemeVersion
        // + " or newer for package " + apkPath
        // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r48:frameworks/base/core/java/android/util/apk/ApkSignatureVerifier.java;l=460
        // public static int getMinimumSignatureSchemeVersionForTargetSdk(int targetSdk)
        clazz.declaredMethods
            .filter { it.name == "getMinimumSignatureSchemeVersionForTargetSdk" }
            .forEach { method -> install(method) { result = 0 } }
    }
}

private object BypassResourceArscRestrictions : PackageManagerHooker(Preferences.PackageManager.ResourceArscRestrictions) {
    override fun onHook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val assetManager = findClass("android.content.res.AssetManager")
        // Targeting R+ (version " + Build.VERSION_CODES.R + " and above) requires"
        // + " the resources.arsc of installed APKs to be stored uncompressed"
        // + " and aligned on a 4-byte boundary
        // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r48:frameworks/base/core/java/android/content/res/AssetManager.java;l=828
        // public boolean containsAllocatedTable()
        val method = assetManager.getDeclaredMethod("containsAllocatedTable")
        install(method) { result = false }
    }
}

private object BypassExactSignature : PackageManagerHooker(Preferences.PackageManager.ExactSignature) {
    override fun onHook() {
        val signingDetails =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                findClass("android.content.pm.SigningDetails")
            } else {
                findClass("android.content.pm.PackageParser\$SigningDetails")
            }
        // https://cs.android.com/android/platform/superproject/+/android-9.0.0_r61:frameworks/base/core/java/android/content/pm/PackageParser.java;l=6036
        val method = signingDetails.getDeclaredMethod("signaturesMatchExactly", signingDetails)
        install(method) { result = true }
    }
}

private object BypassDigestCapabilities : PackageManagerHooker(Preferences.PackageManager.Digest) {
    override fun onHook() {
        val signingDetails =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                findClass("android.content.pm.SigningDetails")
            } else {
                findClass("android.content.pm.PackageParser\$SigningDetails")
            }
        // https://cs.android.com/android/platform/superproject/+/android-9.0.0_r61:frameworks/base/core/java/android/content/pm/PackageParser.java;l=5851
        // public boolean checkCapability(SigningDetails oldDetails, @CertCapabilities int flags)
        val checkCapability = signingDetails.getDeclaredMethod("checkCapability", signingDetails, Int::class.javaPrimitiveType)
        install(checkCapability) {
            val flags = argAs<Int>(1)
            if (flags != 4 && flags != 16) result = true
        }
        // https://cs.android.com/android/platform/superproject/+/android-9.0.0_r61:frameworks/base/core/java/android/content/pm/PackageParser.java;l=5962
        // public boolean checkCapabilityRecover(SigningDetails oldDetails, @CertCapabilities int flags)
        // New package has a different signature
        val checkCapabilityRecover =
            signingDetails.getDeclaredMethod(
                "checkCapabilityRecover",
                signingDetails,
                Int::class.javaPrimitiveType,
            )
        install(checkCapabilityRecover) {
            // Don't handle PERMISSION (grant SIGNATURE permissions to pkgs with this cert)
            // Or applications will have all privileged permissions
            // https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/pm/PackageParser.java;l=5947
            val flags = argAs<Int>(1)
            if (flags != 4 && flags != 16) result = true
        }

        val keySetManager = findClass("com.android.server.pm.KeySetManagerService")
        // https://cs.android.com/android/platform/superproject/+/android-9.0.0_r61:frameworks/base/services/core/java/com/android/server/pm/KeySetManagerService.java;l=346
        // public boolean shouldCheckUpgradeKeySetLocked(PackageSettingBase oldPs, int scanFlags)
        val shouldCheck =
            keySetManager.declaredMethods.first {
                it.name == "shouldCheckUpgradeKeySetLocked" && it.returnType == Boolean::class.java
            }
        // https://cs.android.com/android/platform/superproject/+/android-9.0.0_r61:frameworks/base/services/core/java/com/android/server/pm/KeySetManagerService.java;l=367
        // public boolean checkUpgradeKeySetLocked(PackageSettingBase oldPS, PackageParser.Package newPkg)
        val checkUpgrade =
            keySetManager.declaredMethods.first {
                it.name == "checkUpgradeKeySetLocked" && it.returnType == Boolean::class.java
            }

        fun inPackageUpdate(): Boolean =
            Thread.currentThread().stackTrace.any {
                // https://cs.android.com/android/platform/superproject/+/android-9.0.0_r61:frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java;l=17068
                // private void installPackageLI(InstallArgs args, PackageInstalledInfo res)
                // https://cs.android.com/android/platform/superproject/+/android-10.0.0_r47:frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java;l=17246
                // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r74:frameworks/base/services/core/java/com/android/server/pm/InstallPackageHelper.java;l=1074
                // private PrepareResult preparePackageLI(InstallArgs args, PackageInstalledInfo res)
                // https://cs.android.com/android/platform/superproject/+/android-15.0.0_r36:frameworks/base/services/core/java/com/android/server/pm/InstallPackageHelper.java;l=1381
                // private void preparePackage(InstallRequest request)
                // https://cs.android.com/android/platform/superproject/+/android15-qpr2-release:frameworks/base/services/core/java/com/android/server/pm/InstallPackageHelper.java;l=1275
                // private List<ReconciledPackage> reconcileInstallPackages(List<InstallRequest> requests, Map<String, Settings.VersionInfo> versionInfos)

                // API 35
                "preparePackage" == it.methodName ||
                    // API 35
                    "reconcileInstallPackages" == it.methodName ||
                    // API 29
                    "preparePackageLI" == it.methodName ||
                    // API 28
                    "installPackageLI" == it.methodName
            }
        install(shouldCheck) { if (inPackageUpdate()) result = true }
        install(checkUpgrade) { if (inPackageUpdate()) result = true }
    }
}

private object BypassApkSigningBlockUtils : PackageManagerHooker(Preferences.PackageManager.Verification) {
    override fun onHook() {
        val apkSigningBlockUtils = findClass("android.util.apk.ApkSigningBlockUtils")
        // https://cs.android.com/android/platform/superproject/+/android-9.0.0_r61:frameworks/base/core/java/android/util/apk/ApkSigningBlockUtils.java;l=303
        val parseVerityDigest =
            apkSigningBlockUtils.declaredMethods.first {
                it.name == "parseVerityDigestAndVerifySourceLength"
            }
        install(parseVerityDigest) {
            result = (argAs<ByteArray>(0)).copyOfRange(0, 32)
        }

        val verifyIntegrity =
            apkSigningBlockUtils.declaredMethods.first {
                it.name == "verifyIntegrityForVerityBasedAlgorithm"
            }
        install(verifyIntegrity) { result = null }
    }
}

private object BypassMessageDigest : PackageManagerHooker(Preferences.PackageManager.Verification) {
    override fun onHook() {
        val messageDigest = findClass("java.security.MessageDigest")
        // https://cs.android.com/android/platform/superproject/main/+/main:libcore/ojluni/src/main/java/java/security/MessageDigest.java;l=518
        // public static boolean isEqual(byte[] digesta, byte[] digestb)
        val isEqual = messageDigest.getDeclaredMethod("isEqual", ByteArray::class.java, ByteArray::class.java)
        install(isEqual) { result = true }
    }
}

private object BypassStrictJarVerifier : PackageManagerHooker(Preferences.PackageManager.Enabled) {
    override fun onHook() {
        val strictJarVerifier = findClass("android.util.jar.StrictJarVerifier")
        val bypassDigest = Preferences.PackageManager.Digest
        val usePreviousSignatures = Preferences.PackageManager.PreviousSignatures
        val pkcs7 = findClass("sun.security.pkcs.PKCS7")
        val pkcs7Constructor = pkcs7.getDeclaredConstructor(ByteArray::class.java).apply { isAccessible = true }
        val getSignerInfos = pkcs7.getDeclaredMethod("getSignerInfos")
        val signerInfo = findClass("sun.security.pkcs.SignerInfo")
        val getCertificateChain =
            signerInfo
                .getDeclaredMethod("getCertificateChain", pkcs7)
                .apply { isAccessible = true }
        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/util/jar/StrictJarVerifier.java;l=324
        // static Certificate[] verifyBytes(byte[] blockBytes, byte[] sfBytes)
        val verifyBytes = strictJarVerifier.getDeclaredMethod("verifyBytes", ByteArray::class.java, ByteArray::class.java)
        installAfter(verifyBytes) {
            if (isEnabled(bypassDigest) && !isEnabled(usePreviousSignatures)) {
                val block = pkcs7Constructor.newInstance(arg(0))
                val signers = getSignerInfos.invoke(block) as Array<*>
                if (signers.isNotEmpty()) {
                    val certificates = getCertificateChain.invoke(signers[0], block) as List<*>
                    result = certificates.map { it as java.security.cert.Certificate }.toTypedArray()
                }
            }
        }

        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/util/jar/StrictJarVerifier.java;l=529
        // private static boolean verifyMessageDigest(byte[] expected, byte[] encodedActual)
        val verifyMessageDigest =
            strictJarVerifier.declaredMethods.first {
                it.name == "verifyMessageDigest" && it.returnType == Boolean::class.java
            }
        install(verifyMessageDigest) {
            if (isEnabled(Preferences.PackageManager.Verification)) result = true
        }

        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/util/jar/StrictJarVerifier.java;l=502
        // private boolean verify(
        //     Attributes attributes,
        //     String entry,
        //     byte[] data,
        //     int start,
        //     int end,
        //     boolean ignoreSecondEndline,
        //     boolean ignorable)
        val verify =
            strictJarVerifier.declaredMethods.first {
                it.name == "verify" && it.returnType == Boolean::class.java
            }
        install(verify) {
            if (isEnabled(Preferences.PackageManager.Verification)) result = true
        }

        val rollbackProtection =
            strictJarVerifier
                .getDeclaredField("signatureSchemeRollbackProtectionsEnforced")
                .apply { isAccessible = true }
        installAfter(strictJarVerifier.declaredConstructors.first()) {
            if (isEnabled(Preferences.PackageManager.Verification)) {
                rollbackProtection.set(thisObjectOrNull, false)
            }
        }
    }
}

private object AllowPreviousSignatures : PackageManagerHooker(Preferences.PackageManager.PreviousSignatures) {
    override fun onHook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val installPackageHelper = findClass("com.android.server.pm.InstallPackageHelper")
        val parsedPackage = findClass("com.android.internal.pm.parsing.pkg.ParsedPackage")
        val method =
            installPackageHelper.getDeclaredMethod(
                "doesSignatureMatchForPermissions",
                String::class.java,
                parsedPackage,
                Int::class.javaPrimitiveType,
            )
        val getPackageName = parsedPackage.getMethod("getPackageName").apply { isAccessible = true }
        installAfter(method) {
            if (isEnabled(Preferences.PackageManager.PreviousSignatures) && result == false) {
                // If we decide to crack this then at least make sure they are same apks, avoid another one that tries to impersonate.
                val existingPackageName = getPackageName.invoke(arg(1)) as? String
                if (existingPackageName == arg(0)) result = true
            }
        }
    }
}

private object BypassVerificationAgent : PackageManagerHooker(Preferences.PackageManager.VerificationAgent) {
    override fun onHook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val verifyingSession = findClass("com.android.server.pm.VerifyingSession")
        val installFlags = verifyingSession.getDeclaredField("mInstallFlags").apply { isAccessible = true }
        val startVerify = verifyingSession.declaredMethods.first { it.name == "handleStartVerify" }
        install(startVerify) {
            val session = thisObjectOrNull ?: return@install
            val flags = installFlags.getInt(session)
            installFlags.setInt(session, flags or 0x00080000)
        }
        val adbVerification =
            verifyingSession.declaredMethods.first {
                it.name == "isAdbVerificationEnabled" && it.returnType == Boolean::class.java
            }
        install(adbVerification) { result = false }
    }
}
