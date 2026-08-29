plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.lingqiqi.meowceiler.shared"
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
    // MeowUI 全项目只在这里声明一次，版本号也只有一处。
    api(libs.meowui.xposed)
}
