plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.lingqiqi.meowceiler"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = 0

    defaultConfig {
        applicationId = namespace
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // 只发 arm64。HyperOS 4 的设备没有 32 位的，x86 只有模拟器用得上；
            // 不筛的话 dexkit 的 .so 会白带四份、多出 1MB 多。
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    // 没有这段，META-INF/xposed/* 会在打包时被丢掉，框架就认不出这是个模块。
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

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":hook"))
    implementation(libs.androidx.activity.compose)

    // 打包进 APK，但运行时由框架在宿主进程提供 —— 模块自己的进程里没有。
    compileOnly(libs.libxposed.api)

    // 这个反过来：跑在模块自己的进程里，要打进包。热重载与作用域都走它。
    implementation(libs.libxposed.service)
}
