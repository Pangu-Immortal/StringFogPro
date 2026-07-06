/**
 * ===============================================================================
 * 功能：StringFogPro 演示（sample）独立多模块构建设置。
 * 函数简介：sample 是与主仓解耦的独立 Gradle 构建——从 mavenLocal 消费本仓已发布的
 *   插件（com.va.stringfog）与运行时（stringfog）；刻意独立，使 JitPack 纯 JVM 容器不触碰它。
 *
 * 两个演示模块：
 *   - :app  application 变体（打 APK，反编译 DEX 佐证字符串加密）。
 *   - :lib  library 变体（打 AAR，反编译 classes.jar 佐证库字符串同样被加密）。
 *
 * 本地演示运行：先在仓库根执行 ./gradlew publishToMavenLocal，
 *   再执行 ./gradlew -p sample :app:assembleRelease :lib:assembleRelease :app:assembleDebug。
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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()          // 本仓运行时 stringfog 从这里解析
        google()
        mavenCentral()
    }
}

rootProject.name = "stringfog-sample"

include(":app")
include(":lib")
