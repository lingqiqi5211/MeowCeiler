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

// MeowUI 以复合构建引入：Gradle 会把 io.github.lingqiqi5211.meowui:* 的坐标替换成这里的工程。
//
// 仓库 git init 之后应改成 submodule（third_party/meowui，钉 test 分支）；现在指向同级目录，
// 免得为一个还没初始化的仓库先加 submodule。MeowUI 自己还嵌着 miuix 的 submodule，
// 它的 settings.gradle.kts 里有 require() 检查，没拉全会直接报错。
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
