/**
 * ===============================================================================
 * 功能：StringFogPro Android 演示 App（application 变体）构建配置。
 * 函数简介：apply com.android.application + com.va.stringfog（本仓插件），含 Java 与 Kotlin
 *   两个含明文字符串的类；release 变体经 StringFog 插桩加密，debug 变体不加密（release-only），
 *   用于「加密前(debug) / 加密后(release)」反编译对照。
 *
 * 配置驱动（便于用同一份源码产出各模式反编译证据，无需改文件）：
 *   通过 -P 属性覆盖 stringfog { } 各项，默认零配置走 XOR：
 *     -Psf.algo=aes|xor|<FQCN>   算法（默认 xor）
 *     -Psf.key=<key>             密钥（默认不设→内置/DEFAULT_KEY）
 *     -Psf.bytes=true            bytes 模式（默认 false）
 *     -Psf.randomKey=true        每串随机密钥（默认 false）
 *     -Psf.mapping=true          输出映射文件（默认 false）
 *     -Psf.minLength=<n>         最小串长阈值（默认 1）
 *
 * 关键点：
 *   1. release minify 关闭——聚焦展示 StringFog 插桩效果，排除 R8 shrink 干扰（否则未引用类被删）。
 *   2. runtime 依赖 stringfog——运行时 StringFogRuntime.decrypt 由它提供。
 * ===============================================================================
 */

plugins {
    id("com.android.application") version "9.2.1"
    id("com.va.stringfog") version "2.1.0"   // 本仓插件（mavenLocal）
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
        versionName = "2.1.0"
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
    implementation("com.github.Pangu-Immortal.StringFogPro:stringfog:2.1.0")
}

// 读取 -P 属性的小工具（缺省回落默认值）。
fun prop(name: String, default: String): String = (project.findProperty(name)?.toString() ?: default)
fun boolProp(name: String): Boolean = (project.findProperty(name)?.toString() ?: "false").toBoolean()

// StringFog 加密配置：默认 XOR（零配置，向后兼容 v1.1.0），可用 -P 覆盖以产出各模式证据。
stringfog {
    enabled.set(true)
    algorithm.set(prop("sf.algo", "xor"))
    (project.findProperty("sf.key")?.toString())?.let { key.set(it) }
    minLength.set(prop("sf.minLength", "1").toInt())
    bytesMode.set(boolProp("sf.bytes"))
    randomKeyPerString.set(boolProp("sf.randomKey"))
    mappingEnabled.set(boolProp("sf.mapping"))
}
