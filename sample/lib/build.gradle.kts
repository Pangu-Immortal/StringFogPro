/**
 * ===============================================================================
 * 功能：StringFogPro Android 演示库（library 变体）构建配置。
 * 函数简介：apply com.android.library + com.va.stringfog（本仓插件），含 Java 与 Kotlin
 *   两个含明文字符串的库类；release 变体经 StringFog 插桩加密，产物 AAR 内的 classes.jar
 *   反编译（javap）可佐证「库字符串同样被加密」——证明 Library 变体端到端插桩成立。
 *
 * 关键点：
 *   1. AAR 的类以 Java 字节码（classes.jar）而非 DEX 形态存在，反编译用 javap -c。
 *   2. runtime 依赖 stringfog——库消费方需该运行时提供 decrypt（此处 implementation 演示打进 AAR 依赖）。
 *   3. 默认 XOR；可用 -P 覆盖同 app（见 app/build.gradle.kts 注释）。
 * ===============================================================================
 */

plugins {
    id("com.android.library") version "9.2.1"
    id("com.va.stringfog") version "2.1.0"   // 本仓插件（mavenLocal）
}

android {
    namespace = "com.vapro.vae.stringfog.sample.lib"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    buildTypes {
        release {
            // 库默认 isMinifyEnabled=false；保持关闭聚焦插桩效果
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 运行时解密器（库产物内的加密串运行期由它还原）
    implementation("com.github.Pangu-Immortal.StringFogPro:stringfog:2.1.0")
}

fun prop(name: String, default: String): String = (project.findProperty(name)?.toString() ?: default)
fun boolProp(name: String): Boolean = (project.findProperty(name)?.toString() ?: "false").toBoolean()

stringfog {
    enabled.set(true)
    algorithm.set(prop("sf.algo", "xor"))
    (project.findProperty("sf.key")?.toString())?.let { key.set(it) }
    minLength.set(prop("sf.minLength", "1").toInt())
    bytesMode.set(boolProp("sf.bytes"))
    randomKeyPerString.set(boolProp("sf.randomKey"))
    mappingEnabled.set(boolProp("sf.mapping"))
}
