/**
 * ===============================================================================
 * 功能：StringFogPro 单模块构建配置（纯 Java 运行时库）。
 * 函数简介：把 StringFog 运行时解密器 StringFogRuntime 打成一个普通 JAR，
 *           通过 JitPack 以「一行简单依赖」对外发布。
 *
 * 设计要点：
 *   1. StringFogRuntime 只用 JDK 的 java.util.Base64 + StandardCharsets，零 Android API，
 *      故用 java-library（纯 JAR）而非 Android AAR——JitPack 无需下载 Android SDK（其构建
 *      容器无 dl.google.com 网络、旧 sdkmanager 在 JDK17+ 不可运行），彻底规避 SDK 阻塞。
 *   2. 单模块：仓库根即库本体，产出唯一坐标 com.github.Pangu-Immortal:StringFogPro:<tag>，
 *      消费端只需 implementation("com.github.Pangu-Immortal:StringFogPro:<tag>") 一行。
 *   3. Java 17 目标：纯 Java、下限兼容；被 Java 21 主工程消费无碍（低版本字节码向上兼容）。
 * ===============================================================================
 */

plugins {
    `java-library`
    `maven-publish`
}

group = "com.github.Pangu-Immortal"
version = "1.1.0"

java {
    // 纯 Java 17 目标：JitPack openjdk17 即可编译；产物 JAR 被 Java 21 主工程直接消费。
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // 附带源码 JAR：便于消费端查看 decrypt 实现（可选，纯增益）。
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            // JitPack 单模块坐标：group=com.github.<user>，artifactId=<repo>，version=<tag>。
            groupId = "com.github.Pangu-Immortal"
            artifactId = "StringFogPro"
            version = "1.1.0"
        }
    }
}
