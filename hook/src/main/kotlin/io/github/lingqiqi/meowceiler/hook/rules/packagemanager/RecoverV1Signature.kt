package io.github.lingqiqi.meowceiler.hook.rules.packagemanager

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import io.github.lingqiqi.meowceiler.hook.util.MLog
import io.github.lingqiqi.meowceiler.shared.Preferences
import java.util.zip.ZipEntry

internal object RecoverV1Signature : PackageManagerHooker(Preferences.PackageManager.Verification) {
    override fun onHook() {
        val verifier = findClass("android.util.apk.ApkSignatureVerifier")
        val signingDetails = findClass("android.content.pm.SigningDetails")
        // SigningDetails(Signature[] signatures, int signatureSchemeVersion)
        val signingDetailsConstructor =
            signingDetails
                .getDeclaredConstructor(
                    Array<Signature>::class.java,
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
        val withDigests = findClass("android.util.apk.ApkSignatureVerifier\$SigningDetailsWithDigests")
        val withDigestsConstructor =
            withDigests
                .getDeclaredConstructor(signingDetails, Map::class.java)
                .apply { isAccessible = true }
        val parseInput = findClass("android.content.pm.parsing.result.ParseInput")
        val parseResult = findClass("android.content.pm.parsing.result.ParseResult")
        val isError = parseResult.getMethod("isError")
        val getErrorCode = parseResult.getMethod("getErrorCode")
        val getResult = parseResult.getMethod("getResult")
        val parseTypeImpl = findClass("android.content.pm.parsing.result.ParseTypeImpl")
        val reset = parseTypeImpl.getMethod("reset")
        val forDefaultParsing = parseTypeImpl.getMethod("forDefaultParsing")
        val success = parseInput.getMethod("success", Any::class.java)
        val strictJarFile = findClass("android.util.jar.StrictJarFile")
        val jarConstructor =
            strictJarFile
                .getDeclaredConstructor(
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                ).apply { isAccessible = true }
        val findEntry =
            strictJarFile
                .getDeclaredMethod("findEntry", String::class.java)
                .apply { isAccessible = true }
        val close = strictJarFile.getDeclaredMethod("close").apply { isAccessible = true }
        val loadCertificates =
            verifier
                .getDeclaredMethod(
                    "loadCertificates",
                    parseInput,
                    strictJarFile,
                    ZipEntry::class.java,
                ).apply { isAccessible = true }
        val convertToSignatures =
            verifier
                .getDeclaredMethod(
                    "convertToSignatures",
                    Array<Array<java.security.cert.Certificate>>::class.java,
                ).apply { isAccessible = true }
        val activityThread = findClass("android.app.ActivityThread")
        val currentActivityThread =
            activityThread
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
        val getSystemContext =
            activityThread
                .getDeclaredMethod("getSystemContext")
                .apply { isAccessible = true }

        // https://cs.android.com/android/platform/superproject/+/android-9.0.0_r59:frameworks/base/core/java/android/util/apk/ApkSignatureVerifier.java;l=162
        // private static PackageParser.SigningDetails verifyV1Signature(String apkPath, boolean verifyFull)
        // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r34:frameworks/base/core/java/android/util/apk/ApkSignatureVerifier.java;l=355
        // private static SigningDetailsWithDigests verifyV1Signature(String apkPath, boolean verifyFull)
        // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r74:frameworks/base/core/java/android/util/apk/ApkSignatureVerifier.java;l=362
        // private static ParseResult<SigningDetailsWithDigests> verifyV1Signature(ParseInput input, String apkPath, boolean verifyFull)
        val verifyV1Signature =
            verifier
                .getDeclaredMethod(
                    "verifyV1Signature",
                    parseInput,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                ).apply { isAccessible = true }
        check(verifyV1Signature.returnType == parseResult)
        val recovering = ThreadLocal.withInitial { false }
        installAfter(verifyV1Signature) {
            if (recovering.get() == true) return@installAfter
            val originalResult = result ?: return@installAfter
            if (isError.invoke(originalResult) != true || getErrorCode.invoke(originalResult) != -103) {
                return@installAfter
            }
            val input = arg(0) ?: return@installAfter
            if (!parseTypeImpl.isInstance(input)) return@installAfter
            val apkPath = argAs<String>(1)
            recovering.set(true)
            try {
                var signaturesBefore: Array<Signature>? = null
                // use previous signatures, get from package manager
                if (isEnabled(Preferences.PackageManager.PreviousSignatures)) {
                    try {
                        val thread = currentActivityThread.invoke(null)
                        val context = thread?.let { getSystemContext.invoke(it) as Context }
                        val packageManager = context?.packageManager
                        if (packageManager == null) {
                            MLog.w(id, "Cannot get the Package Manager... Are you using MiUI?")
                        } else {
                            val info = packageManager.getPackageArchiveInfo(apkPath, 0)
                            if (info != null) {
                                val installed =
                                    packageManager.getPackageInfo(
                                        info.packageName,
                                        PackageManager.GET_SIGNING_CERTIFICATES,
                                    )
                                signaturesBefore = installed.signingInfo?.signingCertificateHistory
                            }
                        }
                    } catch (t: Throwable) {
                        MLog.w(id, "cannot get signatures from installed package: ${t.message}")
                    }
                }
                // if previous signatures not found, parse it from apk
                if (signaturesBefore == null && isEnabled(Preferences.PackageManager.Digest)) {
                    try {
                        // verifyV1Signature(String apkPath, boolean verifyFull)
                        // verifyV1Signature(ParseInput input, String apkPath, boolean verifyFull) // Android 13
                        val jar = jarConstructor.newInstance(apkPath, true, false)
                        try {
                            val manifest = requireNotNull(findEntry.invoke(jar, "AndroidManifest.xml"))
                            //  9 private static Certificate[][] loadCertificates(StrictJarFile jarFile, ZipEntry entry)
                            // 13 private static ParseResult<Certificate[][]> loadCertificates(
                            //     ParseInput input, StrictJarFile jarFile, ZipEntry entry)
                            val scratchInput = forDefaultParsing.invoke(null)
                            val certificates = loadCertificates.invoke(null, scratchInput, jar, manifest)
                            check(isError.invoke(certificates) == false) { "cannot read manifest certificates" }
                            @Suppress("UNCHECKED_CAST")
                            signaturesBefore =
                                convertToSignatures.invoke(
                                    null,
                                    getResult.invoke(certificates),
                                ) as Array<Signature>
                        } finally {
                            runCatching { close.invoke(jar) }
                                .onFailure { MLog.w(id, "cannot close signature archive: ${it.message}") }
                        }
                    } catch (t: Throwable) {
                        MLog.w(id, "Unexpected error while parsing signatures: ${t.message}")
                    }
                }
                val details =
                    signingDetailsConstructor.newInstance(
                        signaturesBefore ?: arrayOf(Signature(CorePatchSignature.SIGNATURE)),
                        1,
                    )
                // 修复 java.lang.ClassCastException: Cannot cast
                // PackageParser$SigningDetails to SigningDetailsWithDigests
                val recovered = withDigestsConstructor.newInstance(details, null)
                reset.invoke(input)
                result = success.invoke(input, recovered)
            } finally {
                recovering.remove()
            }
        }
    }
}
