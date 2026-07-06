/**
 * ===============================================================================
 * 功能：StringFogPro 独立发布仓设置。
 * 函数简介：统一插件仓库、依赖仓库，聚合 stringfog（运行时 AAR）与
 *           stringfog-gradle-plugin（AGP 字节码插件 JAR）两个子模块。
 * 说明：本仓从 VirtualApp 抽离 StringFog 已适配版，作为稳定 JitPack 依赖对外发布。
 * ===============================================================================
 */

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Java 工具链自动下载：保证 JDK 21 可用（JitPack 环境按 jitpack.yml 指定 openjdk21）。
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StringFogPro"

// :stringfog —— StringFog 运行时源码库（Android library，产出 AAR，含 decrypt(String) 解密入口）。
// :stringfog-gradle-plugin —— AGP 9.x AsmClassVisitorFactory 字节码插件（JAR，构建期字符串加密）。
include(":stringfog", ":stringfog-gradle-plugin")
