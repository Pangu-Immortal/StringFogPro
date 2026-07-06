/**
 * ===============================================================================
 * 功能：StringFogPro Android 演示 App 构建配置。
 * 函数简介：apply com.android.application + kotlin.android + com.va.stringfog（本仓插件），
 *   包含 Java 与 Kotlin 两个含明文字符串的类；release 变体经 StringFog 插桩加密，
 *   debug 变体不加密（release-only），用于「加密前(debug) / 加密后(release)」反编译对照。
 *
 * 关键点：
 *   1. release minify 关闭——聚焦展示 StringFog 插桩效果，排除 R8 shrink 干扰（否则未引用类被删）。
 *   2. runtime 依赖 stringfog——运行时 StringFogRuntime.decrypt 由它提供。
 *   3. stringfog { } 演示默认 XOR（零配置）；如需 AES 见注释。
 * ===============================================================================
 */

plugins {
    id("com.android.application") version "9.2.1"
    id("com.va.stringfog") version "2.0.0"   // 本仓插件（mavenLocal）
    // 注：AGP 9.x 内置 Kotlin 支持（自带 kotlin 扩展），无需再 apply org.jetbrains.kotlin.android
}

android {
    namespace = "com.vapro.vae.stringfog.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vapro.vae.stringfog.sample"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            // 关闭 R8：聚焦 StringFog 插桩效果，避免 shrink 删除未引用的演示类
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 运行时解密器（本仓 stringfog 模块，mavenLocal）
    implementation("com.github.Pangu-Immortal.StringFogPro:stringfog:2.0.0")
}

// StringFog 加密配置：默认 XOR（零配置，向后兼容 v1.1.0）。
stringfog {
    enabled.set(true)
    algorithm.set("xor")
    minLength.set(1)
    // 如需 AES：algorithm.set("aes"); key.set("my-release-key-2026")
    // 如需仅加密特定包：fogPackages.add("com.vapro.vae.stringfog.sample")
}
