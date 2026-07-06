/**
 * ===============================================================================
 * 功能：stringfog 运行时模块构建配置（纯 Java 运行时 + 算法库）。
 * 函数简介：把 IStringFog 契约、XorFog/AesFog 内置算法、StringFogRuntime 解密调度器
 *           打成一个普通 JAR，供 App 运行时解密 与 插件构建期加密 共同复用。
 *
 * 设计要点：
 *   1. 纯 java-library：仅依赖 JDK（Base64/charset/javax.crypto），零 Android API，
 *      JitPack 纯 JVM 容器即可编译发布，彻底规避 Android SDK 下载阻塞。
 *   2. Java 17 目标：下限兼容；被 Java 21 主工程消费无碍（低版本字节码向上兼容）。
 *   3. withSourcesJar：附带源码 JAR，便于消费端查看算法实现。
 *   4. maven-publish：JitPack 多模块坐标 com.github.Pangu-Immortal.StringFogPro:stringfog:<tag>。
 * ===============================================================================
 */

plugins {
    `java-library`
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    // 单元测试：验证 XOR/AES 往返、StringFogRuntime 双入口调度、v1.1.0 向后兼容。
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "stringfog"
        }
    }
}
