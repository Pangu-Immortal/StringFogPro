# StringFogPro

StringFog 字符串加密（VirtualApp 适配版，稳定依赖）。

从 [StringFog](https://github.com/MegatronKing/StringFog)（Apache-2.0）改写，适配 **AGP 9.2.1 / Gradle 9.6 / compileSdk 36 / Java 21**，用于 release 构建期对字符串字面量做 `XOR + Base64` 加密、运行时解密。本仓为「已适配、稳定不再变」的抽离产物，通过 JitPack 发布。

## 组成

| 模块 | 说明 | 坐标 |
| --- | --- | --- |
| `stringfog` | 运行时 AAR，提供 `StringFogRuntime.decrypt(String)` 解密入口 | `com.github.Pangu-Immortal.StringFogPro:stringfog:v1.0.0` |
| `stringfog-gradle-plugin` | AGP `AsmClassVisitorFactory` 字节码插件，构建期加密 | `com.github.Pangu-Immortal.StringFogPro:stringfog-gradle-plugin:v1.0.0` |

## 用法

`settings.gradle.kts`（`dependencyResolutionManagement.repositories`）：

```kotlin
maven { url = uri("https://jitpack.io") }
```

根 `build.gradle.kts` 加入插件 classpath：

```kotlin
buildscript {
    repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
    dependencies { classpath("com.github.Pangu-Immortal.StringFogPro:stringfog-gradle-plugin:v1.0.0") }
}
```

需加密的库/应用模块：

```kotlin
apply(plugin = "com.va.stringfog")

dependencies {
    implementation("com.github.Pangu-Immortal.StringFogPro:stringfog:v1.0.0")
}
```

- 仅对 `release` 变体生效；`-Pva.stringfog.enabled=false` 可关闭。
- 运行时解密器 `com.vapro.vae.stringfog.StringFogRuntime` 已随 AAR 的 `consumer-rules.pro` 自动 keep。

## License

Apache License 2.0。原始 StringFog © MegatronKing，见 `LICENSE`。
