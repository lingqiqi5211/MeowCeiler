plugins {
    alias(libs.plugins.android.library)
}

// 桩只参与编译、不进 APK。做成 Android library 是因为桩要引用 Context / View 这些平台类型。
android {
    namespace = "io.github.lingqiqi.meowceiler.hiddenapi"
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
