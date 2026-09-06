import com.android.build.api.dsl.ApplicationBuildType
import com.android.build.api.variant.impl.VariantOutputImpl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
            isMinifyEnabled = false
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

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

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
