@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://api.xposed.info/") {
            mavenContent { includeGroupAndSubgroups("io.github.libxposed") }
        }
    }
}

rootProject.name = "MeowCeiler"

// 使用同级 MeowUI 工程替换 Maven 坐标；其 miuix 子模块需一并检出。
val meowUiDir = file("../MeowUI")
require(meowUiDir.resolve("settings.gradle.kts").isFile) {
    """
    MeowUI 不在 ${meowUiDir.absolutePath}

        git clone --recurse-submodules -b test https://github.com/lingqiqi5211/MeowUI.git

    （放在与本仓库同级的目录下。）
    """.trimIndent()
}
includeBuild(meowUiDir)

include(":app")
include(":ui")
include(":shared")
include(":hook")
include(":hidden-api")
