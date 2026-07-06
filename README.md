# StringFogPro

StringFog 字符串加密**运行时库**（VirtualApp 适配版，稳定依赖）。

单模块、单坐标、一行依赖。从 [StringFog](https://github.com/MegatronKing/StringFog)（Apache-2.0）改写，提供 release 构建期被字节码插桩插入的字符串解密入口 `StringFogRuntime.decrypt(String)`（`XOR + Base64` 还原）。

> 本仓**只含运行时**（纯 Java JAR，零 Android 依赖）。构建期做字符串加密的 Gradle 字节码插桩插件属于**构建工具**、非可发布运行时代码，按上游工程约定保留为其本地 `included build`，不在本依赖内。

## 依赖坐标

| 坐标 | 形态 |
| --- | --- |
| `com.github.Pangu-Immortal:StringFogPro:v1.0.0` | 纯 Java JAR |

## 用法

`settings.gradle.kts`（`dependencyResolutionManagement.repositories`）：

```kotlin
maven { url = uri("https://jitpack.io") }
```

需要用到解密运行时的模块：

```kotlin
dependencies {
    implementation("com.github.Pangu-Immortal:StringFogPro:v1.0.0")
}
```

- 运行时类：`com.vapro.vae.stringfog.StringFogRuntime`，静态方法 `decrypt(String)`。
- 若消费端启用 R8/ProGuard 混淆：`decrypt` 的所有调用点由字节码插桩以 `INVOKESTATIC` 直引，R8 会随引用一并保留并一致重命名，通常无需额外 keep；如需固定类名可自行加
  `-keep class com.vapro.vae.stringfog.StringFogRuntime { public static java.lang.String decrypt(java.lang.String); }`。

## License

Apache License 2.0。原始 StringFog © MegatronKing，见 `LICENSE`。
