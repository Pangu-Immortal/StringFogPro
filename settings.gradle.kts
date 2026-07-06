/**
 * ===============================================================================
 * 功能：StringFogPro 多模块工程设置。
 * 函数简介：聚合两个可发布模块——:stringfog（纯 Java 运行时 + 算法库）与
 *   :stringfog-gradle-plugin（AGP 9.x 字符串加密插件）。JitPack 仅构建这两个模块。
 *
 * 说明：sample/ 是独立的 Android 演示构建（自带 settings，从 mavenLocal 消费本仓插件+运行时），
 *   刻意不纳入本 settings，从而 JitPack 纯 JVM 容器永不触碰 Android SDK，构建稳过。
 * ===============================================================================
 */

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StringFogPro"

include(":stringfog")
include(":stringfog-gradle-plugin")
