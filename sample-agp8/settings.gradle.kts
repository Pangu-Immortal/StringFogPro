/**
 * ===============================================================================
 * 功能：StringFogPro AGP 8.x 适配验证（sample-agp8）独立构建设置。
 * 函数简介：与主仓、与 sample/ 完全解耦的独立 Gradle 构建——从 mavenLocal 消费本仓已发布的
 *   插件（com.va.stringfog:2.1.0）与运行时（stringfog:2.1.0），在较低 AGP 8.7.3 + Gradle 8.13
 *   工具链下验证插件的字符串加密插桩是否正确工作。
 * ===============================================================================
 */

pluginManagement {
    repositories {
        mavenLocal()          // 本仓插件 com.va.stringfog 从这里解析
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenLocal()          // 本仓运行时 stringfog 从这里解析
        google()
        mavenCentral()
    }
}

rootProject.name = "stringfog-sample-agp8"

include(":app")
