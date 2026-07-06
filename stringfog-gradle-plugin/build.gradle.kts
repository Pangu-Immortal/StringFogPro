/**
 * ===============================================================================
 * 功能：StringFog Gradle 插件构建配置（独立发布版）。
 * 函数简介：以 kotlin-dsl 编译 Kotlin 源码，发布为 com.va.stringfog Gradle 插件 JAR，
 *           供宿主工程通过 buildscript classpath + apply(plugin="com.va.stringfog") 应用。
 * 发布坐标：com.github.Pangu-Immortal.StringFogPro:stringfog-gradle-plugin:<tag>
 *
 * 关键约束：
 *   1. kotlin-dsl 自动应用 java-gradle-plugin，提供 gradlePlugin { } DSL 与插件描述符生成。
 *   2. AGP 与 ASM 仅编译期依赖（compileOnly）：运行期由宿主 buildscript 的 AGP/ASM 提供，
 *      避免与宿主 AGP 版本冲突或重复打包。
 *   3. java-gradle-plugin + maven-publish 自动生成主 JAR 发布（artifactId = 模块名）。
 * ===============================================================================
 */

plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "com.github.Pangu-Immortal.StringFogPro"
version = "1.0.0"

dependencies {
    // AGP 9.2.1 公共 API（com.android.build.api.variant.Instrumentation / AsmClassVisitorFactory 等）。
    compileOnly("com.android.tools.build:gradle-api:9.2.1")
    // ASM 9.9（AGP 9.2.1 内置版本；ClassVisitor/MethodVisitor/Opcodes）。
    compileOnly("org.ow2.asm:asm:9.9")
}

gradlePlugin {
    plugins {
        register("stringfog") {
            id = "com.va.stringfog"
            implementationClass = "com.va.stringfog.StringFogPlugin"
            displayName = "VA StringFog Plugin"
            description = "Release 字符串字面量 XOR+Base64 加密（AGP 9.x AsmClassVisitorFactory），运行时解密"
        }
    }
}
