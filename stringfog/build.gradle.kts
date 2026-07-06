/**
 * ===============================================================================
 * 功能：StringFog 运行时源码库构建配置（独立发布版）。
 * 函数简介：提供构建期 StringFog 插件插入的 decrypt(String) 运行时入口，产出 AAR，
 *           通过 JitPack 以稳定依赖对外发布。
 * 发布坐标：com.github.Pangu-Immortal.StringFogPro:stringfog:<tag>
 * ===============================================================================
 */

plugins {
    id("com.android.library")
    id("maven-publish")
}

android {
    namespace = "com.vapro.vae.stringfog"
    compileSdk {
        version = release(36) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "36.0.0"
    enableKotlin = false

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        targetSdk = 36
    }

    // 关键逻辑：暴露 release 变体为可发布组件，供 maven-publish 的 from(components["release"]) 使用。
    publishing {
        singleVariant("release")
    }
}

group = "com.github.Pangu-Immortal.StringFogPro"
version = "1.0.0"

// AGP 组件在 afterEvaluate 后才创建，publication 必须延迟到 afterEvaluate 注册。
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.Pangu-Immortal.StringFogPro"
                artifactId = "stringfog"
                version = "1.0.0"
            }
        }
    }
}
