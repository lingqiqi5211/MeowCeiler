plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.lingqiqi.meowceiler.hook"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = 0

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
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
    api(project(":shared"))

    implementation(libs.ezhooktool.core)
    implementation(libs.ezhooktool.xposed102)

    // AAR 自带 libdexkit.so，会跟着打进 app 的 APK。
    implementation(libs.dexkit)

    // 框架在宿主进程里提供，模块自己的 App 进程里没有 —— 必须 compileOnly。
    compileOnly(libs.libxposed.api)

    // 隐藏 API 桩：只参与编译，不进包。运行时用设备上真实的 framework 类。
    compileOnlyApi(project(":hidden-api"))
}
