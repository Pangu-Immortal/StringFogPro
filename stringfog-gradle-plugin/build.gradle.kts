/**
 * ===============================================================================
 * 功能：stringfog-gradle-plugin 模块构建配置（AGP 9.x 字符串加密 Gradle 插件）。
 * 函数简介：以 kotlin-dsl 编译 Kotlin 插件源码，发布为 com.va.stringfog 插件，
 *   供 :app / :lib 在 plugins {} 应用；构建期复用 :stringfog 的算法实现做加密。
 *
 * 关键约束：
 *   1. kotlin-dsl 自动应用 java-gradle-plugin，提供 gradlePlugin { } DSL 与插件标记发布。
 *   2. :stringfog 为 implementation——构建期插桩需 XorFog/AesFog/StringFogRuntime 在 buildscript
 *      classpath 可用；发布到 JitPack 时该依赖写入插件 POM，消费端 buildscript 自动获得。
 *   3. AGP 与 ASM 仅 compileOnly——运行期由宿主 AGP 提供其内置 AGP/ASM，避免版本冲突与重复打包。
 * ===============================================================================
 */

plugins {
    `kotlin-dsl`
    `maven-publish`
}

dependencies {
    // 构建期加密复用运行时算法实现（XorFog/AesFog/StringFogRuntime.defaultKey）。
    implementation(project(":stringfog"))

    // AGP 9.2.1 公共 API（AsmClassVisitorFactory / Instrumentation / onVariants 等）。
    compileOnly("com.android.tools.build:gradle-api:9.2.1")
    // ASM 9.9（AGP 9.2.1 内置版本；ClassVisitor/MethodVisitor/Opcodes）。
    compileOnly("org.ow2.asm:asm:9.9")
    // ASM 树 API 9.9（MethodNode/InsnList/InvokeDynamicInsnNode）：invokedynamic 拼接去糖需缓冲方法体
    //   并依据真实 maxLocals 安全分配新局部；同为 AGP 9.2.1 内置版本，compileOnly（运行期由 AGP 提供）。
    compileOnly("org.ow2.asm:asm-tree:9.9")

    // 单元/集成测试：核心 ASM 层脱离 AGP 独立验证（编译类→插桩→加载→往返断言）。
    testImplementation("org.ow2.asm:asm:9.9")
    testImplementation("org.ow2.asm:asm-tree:9.9")
    testImplementation("org.ow2.asm:asm-util:9.9")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        register("stringfog") {
            id = "com.va.stringfog"
            implementationClass = "com.vapro.vae.stringfog.plugin.StringFogPlugin"
            displayName = "StringFogPro Plugin"
            description = "Release 字符串字面量加密（AGP 9.x AsmClassVisitorFactory）：XOR/AES/可扩展算法 + 细粒度配置"
        }
    }
}
