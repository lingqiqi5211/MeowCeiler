plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.lingqiqi.meowceiler.ui"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = 0

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    api(project(":shared"))
    implementation(libs.androidx.activity.compose)
    // MeowUI 是 implementation 引的图标，没 api 出来；底栏要 ImageVector，这里自己声明。
    implementation(libs.compose.material.icons.extended)
    implementation(libs.appiconloader)
}
