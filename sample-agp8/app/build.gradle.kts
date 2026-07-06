/**
 * ===============================================================================
 * 功能：StringFogPro AGP 8.x 适配验证 App 构建配置（纯 Java，无 Kotlin）。
 * 函数简介：apply com.android.application 8.7.3 + com.va.stringfog 2.1.0（本仓插件，mavenLocal）。
 *   仅一个含明文字符串的纯 Java 类，release 变体经 StringFog 插桩加密，debug 变体不加密。
 *
 * 关键点：
 *   1. AGP 8.7.3 无内置 Kotlin，刻意只用纯 Java，避免额外引入 kotlin 插件的复杂度。
 *   2. release minify 关闭——聚焦展示 StringFog 插桩效果，排除 R8 shrink 干扰。
 *   3. runtime 依赖 stringfog——运行时 StringFogRuntime.decrypt 由它提供。
 * ===============================================================================
 */

plugins {
    id("com.android.application") version "8.7.3"
    id("com.va.stringfog") version "2.1.0"   // 本仓插件（mavenLocal）
}

android {
    namespace = "com.vapro.agp8.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vapro.agp8.sample"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
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

// StringFog 加密配置：XOR 算法，最小串长 1。
stringfog {
    enabled.set(true)
    algorithm.set("xor")
    minLength.set(1)
}
