import com.android.build.api.dsl.ApplicationBuildType
import com.android.build.api.variant.impl.VariantOutputImpl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries)
}

val apkId = "MeowCeiler-Lite"

fun runGitCommand(vararg args: String): String? = runCatching {
    ProcessBuilder(listOf("git") + args)
        .redirectErrorStream(true)
        .start()
        .let { process ->
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && output.isNotBlank()) output else null
        }
}.getOrNull()

val gitHash: String by lazy { runGitCommand("rev-parse", "--short", "HEAD") ?: "unknown" }
val gitCommitCount: Int by lazy { runGitCommand("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0 }
val gitCommitCountSinceTag: Int by lazy {
    runGitCommand("describe", "--tags", "--long", "--match", "[0-9]*", "--always")
        ?.let { """^.+-(\d+)-g[0-9a-fA-F]+$""".toRegex().matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        ?: 0
}
val gitVersionCode: Int by lazy { 5 + gitCommitCount }
val canaryVersionNameSuffix: String by lazy {
    buildString {
        append('-')
        if (gitCommitCountSinceTag > 0) {
            append(gitCommitCountSinceTag)
            append('-')
        }
        append(gitHash)
        append("-r")
        append(gitVersionCode)
    }
}
val buildTimeSuffix: String by lazy {
    SimpleDateFormat("MMddHHmm").apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }.format(Date())
}
val dateSuffix: String by lazy {
    SimpleDateFormat("yyyyMMdd").apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }.format(Date())
}

val signingProperties: Properties? = rootProject.file("signing.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

android {
    namespace = "io.github.lingqiqi.meowceiler"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = 0

    defaultConfig {
        applicationId = namespace
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = gitVersionCode
        versionName = "3.4.174"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("hasProperties") {
            signingProperties?.let {
                storeFile = rootProject.file(it.getProperty("storeFile"))
                storePassword = it.getProperty("storePassword")
                keyAlias = it.getProperty("keyAlias")
                keyPassword = it.getProperty("keyPassword")
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        val configSigning: ApplicationBuildType.() -> Unit = {
            signingConfig = signingConfigs.findByName(if (signingProperties != null) "hasProperties" else "debug")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            configSigning()
            versionNameSuffix = "-$dateSuffix"
        }

        create("canary") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            versionNameSuffix = canaryVersionNameSuffix
        }

        debug {
            isMinifyEnabled = false
            versionNameSuffix = "-$buildTimeSuffix-r$gitVersionCode"
            if (signingProperties != null) signingConfig = signingConfigs.findByName("hasProperties")
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        additionalParameters += listOf("--allow-reserved-package-id", "--package-id", "0x64")
    }

    sourceSets.getByName("main").jniLibs.srcDir(
        layout.buildDirectory
            .dir("generated/nativeWeather")
            .get()
            .asFile,
    )

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libmeow_runtime.so", "**/libmeow_native_payload.so")
        }
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

val buildNativeWeather by tasks.registering {
    val rust = rootProject.file("native/rust")
    val output = layout.buildDirectory.dir("generated/nativeWeather/arm64-v8a")
    inputs.files(
        fileTree(rust) {
            include("**/Cargo.toml", "**/Cargo.lock", "**/*.rs")
            exclude("target/**", "native-relay-sdk/**")
        },
    )
    outputs.dir(output)
    doLast {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val host =
            if (windows) {
                "windows-x86_64"
            } else if (System.getProperty("os.name").startsWith("Mac")) {
                "darwin-x86_64"
            } else {
                "linux-x86_64"
            }
        val sdk =
            androidComponents.sdkComponents.sdkDirectory
                .get()
                .asFile
        val ndk = System.getenv("ANDROID_NDK_HOME")?.let(::file) ?: sdk.resolve("ndk/28.2.13676358")
        val clang = ndk.resolve("toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android35-clang" + if (windows) ".cmd" else "")
        check(clang.isFile) { "Install NDK 28.2.13676358 or set ANDROID_NDK_HOME" }
        val cargo =
            System.getenv("CARGO") ?: File(System.getProperty("user.home"), ".cargo/bin/cargo" + if (windows) ".exe" else "").absolutePath
        val process =
            ProcessBuilder(
                cargo,
                "build",
                "--locked",
                "--offline",
                "--release",
                "--target",
                "aarch64-linux-android",
                "-p",
                "meow-native-runtime",
                "-p",
                "meow-native-payload",
            ).directory(rust)
                .redirectErrorStream(true)
                .apply {
                    environment()["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = clang.absolutePath
                }.start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(logger::lifecycle) }
        check(process.waitFor() == 0) { "Native weather build failed" }
        val destination = output.get().asFile.apply { mkdirs() }
        rust
            .resolve(
                "target/aarch64-linux-android/release/libmeow_native_payload.so",
            ).copyTo(destination.resolve("libmeow_native_payload.so"), overwrite = true)
        rust
            .resolve(
                "target/aarch64-linux-android/release/meow-native-runtime",
            ).copyTo(destination.resolve("libmeow_runtime.so"), overwrite = true)
    }
}
tasks.named("preBuild") { dependsOn(buildNativeWeather) }

androidComponents {
    onVariants { variant ->
        val suffix = variant.buildType?.let { android.buildTypes.findByName(it) }?.versionNameSuffix.orEmpty()
        variant.outputs.forEach {
            (it as VariantOutputImpl).outputFileName =
                "$apkId-${android.defaultConfig.versionName}$suffix-${variant.buildType}.apk"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":hook"))
    implementation(libs.androidx.activity.compose)

    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
}
