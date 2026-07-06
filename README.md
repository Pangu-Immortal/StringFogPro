# StringFogPro

<div align="center">

![StringFogPro 访问计数](https://count.getloli.com/get/@StringFogPro?theme=rule34)

<p>
  <b>给字符串加上一层雾霭，使人难以窥视其真面目。<br/>
  如果对你有帮助，请点一个 <a href="https://github.com/Pangu-Immortal/StringFogPro/stargazers">Star</a> 支持一下！</b>
</p>

[![JitPack](https://jitpack.io/v/Pangu-Immortal/StringFogPro.svg)](https://jitpack.io/#Pangu-Immortal/StringFogPro)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/Release-v2.1.0-brightgreen.svg)](https://github.com/Pangu-Immortal/StringFogPro/releases)
[![AGP](https://img.shields.io/badge/AGP-8.7%20%7C%209.2%20实测-green.svg)](https://developer.android.com/build)
[![Gradle](https://img.shields.io/badge/Gradle-8.13%20%7C%209.6-02303A.svg)](https://gradle.org)
[![JDK](https://img.shields.io/badge/JDK-17%2B%20(21%20实测)-orange.svg)](https://adoptium.net)
[![Tests](https://img.shields.io/badge/tests-24%2F24%20passing-brightgreen.svg)](#适配测试矩阵)

**简体中文** | [English](#english)

</div>

> **TL;DR — 为什么选 StringFogPro？**
>
> StringFogPro 是一款自动加密 APK / AAR 中 Java·Kotlin 字符串字面量的 Gradle 插件，构建期把明文替换为「密文 + 运行时解密调用」，反编译只能看到乱码，让硬编码的接口地址、密钥、Token 不再裸奔。它在经典 [StringFog](https://github.com/MegatronKing/StringFog)（Apache-2.0）的基础上做了**现代化超集升级**：拥抱 **AGP 8.7 / 9.2** 现代插桩 API（`AsmClassVisitorFactory` + `onVariants`），**内置 AES**（原版 v3.0.0 已删除 AES），提供 **XOR / AES / 自主扩展算法**三选一，新增 **每串随机密钥（`IKeyGenerator`）**、**bytes 免 Base64 模式**、**构建期明文→密文映射文件**，支持**按包 include/exclude + 最小串长阈值**细粒度配置，运行时是**零依赖纯 Java JAR**，一次 `./gradlew assembleRelease` 全自动完成，零侵入业务代码。**24/24 单元/集成测试通过 + 真实 APK/AAR 反编译证据 + AGP 8.7/9.2 双档实测。**

---

## 目录

- [核心特性](#核心特性)
- [相对原版 StringFog 的升级](#相对原版-stringfog-的升级)
- [功能对照表（能力 · 代码位 · 测试位）](#功能对照表能力--代码位--测试位)
- [加密前 / 加密后 / 运行时（真实反编译证据）](#加密前--加密后--运行时真实反编译证据)
- [快速接入](#快速接入)
- [配置项参考](#配置项参考)
- [算法自主扩展](#算法自主扩展)
- [适配测试矩阵](#适配测试矩阵)
- [版本支持表](#版本支持表)
- [工程结构](#工程结构)
- [设计说明与已知边界](#设计说明与已知边界)
- [Roadmap（尚未实现，诚实标注）](#roadmap尚未实现诚实标注)
- [更新日志](#更新日志)
- [致谢与 License](#致谢与-license)

---

## 核心特性

- **支持 Java / Kotlin** —— 工作在 ASM 字节码层，与源语言无关；Java 与 Kotlin 编译出的类被同样加密（本文附真实反编译证据）。
- **支持 APK 加密** —— `com.android.application`（Application 变体）release 构建自动插桩（真实 DEX 反编译佐证）。
- **支持 AAR / JAR 库加密** —— `com.android.library`（Library 变体）走同一套 ASM 变换核心（真实 AAR `classes.jar` 反编译佐证）。
- **加解密算法自主扩展** —— 统一 `IStringFog` 契约，内置 **XOR + Base64（默认）** 与 **AES-128/CBC**，可注册任意自定义算法，构建期加密与运行时解密走同一实现。
- **每串随机密钥（`IKeyGenerator`）** —— 每个字符串独立随机密钥，相同明文各处密文不同，抗统计/模式分析、全包不存在可 grep 的共享密钥。
- **bytes 免 Base64 模式** —— 密文以原始 `byte[]` 字面量发射（d8 优化为 `fill-array-data`），不以可识别 Base64 文本出现，改变静态特征、免运行时 Base64 解码。
- **构建期映射文件** —— 可选输出「类#方法 · 算法 · 明文 → 密文」映射，便于审计与排查解密异常。
- **细粒度可选配置** —— `enabled` 总开关 + `-Pva.stringfog.enabled=false` 全局关；按包 `fogPackages` 白名单 / `excludePackages` 黑名单；`minLength` 最小加密串长阈值；配置校验 + 冲突告警。
- **完全 Gradle 自动化** —— `onVariants` 自动注册，仅需 apply 插件，零手工接线；仅 release 插桩，debug 保持明文便于调试。
- **零依赖纯 Java 运行时** —— 运行时 JAR 仅依赖 JDK（`Base64` / `javax.crypto`），不引入 kotlin-stdlib，POM 零依赖（实测），可嵌入任意 App。
- **向后兼容** —— 零配置默认路径与 v1.1.0 逐字节等价（同一 XOR 密钥），历史工程可零成本迁移。

---

## 相对原版 StringFog 的升级

| 维度 | 原版 StringFog（最新 5.x） | StringFogPro v2.1.0 | 是否真实现 |
| --- | --- | --- | --- |
| AGP 基线 | AGP 8.x / Gradle 8 | **AGP 8.7 与 9.2 双档实测**（`AsmClassVisitorFactory` + `onVariants`） | ✅ 双档真实构建 |
| AES 算法 | v3.0.0 起**已删除** | **内置回归**（AES-128/CBC/PKCS5，随机 IV 前置） | ✅ 单测 + APK 反编译 |
| 排除包 exclude | v1.4.0 起**已移除** | **重新提供** `excludePackages` 黑名单 | ✅ 单测 |
| 最小串长阈值 | 无 | 新增 `minLength` | ✅ 单测 |
| 每串随机密钥 `IKeyGenerator` | ✅ | **已实现**（`RandomKeyGenerator`，每串独立随机密钥） | ✅ 单测 + APK 反编译 |
| bytes 模式（免 Base64） | ✅ | **已实现**（byte[] 字面量 / `fill-array-data`） | ✅ 单测 + APK 反编译 |
| mapping 映射文件 | ✅ | **已实现**（构建期明文→密文映射输出） | ✅ 单测 + 真实构建产物 |
| 配置形态 | Groovy DSL 为主 | 类型化 **Kotlin DSL**（`Property`/`ListProperty`）+ 配置校验 | ✅ 已实现 |
| 运行时依赖 | 需引对应算法库 | **零依赖纯 Java JAR**（POM 无任何 dependency） | ✅ POM 实测 |
| 自定义算法 | `IStringFog` + `implementation` | `IStringFog` + 构建期反射 + 运行时 `register` | ✅ 单测（FQCN 反射） |

> 诚实声明：与原版的**真正差距**已收敛到「`plugins {}` 免接线声明式解析」——它需要把插件 marker 发布到 **Gradle Plugin Portal**（需外部发布账号，本项目暂无）。JitPack 下用 **buildscript classpath**（最稳）或 **`resolutionStrategy` 映射**即可（见[快速接入](#快速接入)），已实测插件按 module 坐标可解析装载。详见 [Roadmap](#roadmap尚未实现诚实标注)。
>
> 关于 bytes 模式「省体积」的诚实说明：DEX 层 `fill-array-data` 以 1 字节/密文字节存储，较 Base64 常量（≈1.33 字节/密文字节的 MUTF-8）在**密文载荷**上更省，但每串有约 8 字节固定开销；**净收益随串长/串数增长**。本仓 6 条短串样例实测整包 DEX 差异仅 +8 字节（可忽略），故 bytes 模式的**首要价值是抹除 Base64 文本特征 + 免运行时 Base64 解码**，而非对短串省体积——不夸大。

---

## 功能对照表（能力 · 代码位 · 测试位）

> 凡打 ✅ 均有对应已实现且验证过的代码；实现位置与测试位置一并列出（测试见[适配测试矩阵](#适配测试矩阵)）。

| 能力 | 本项目 | 实现位置 | 测试 / 证据位置 |
| --- | :---: | --- | --- |
| Java 字符串加密 | ✅ | `plugin/core/StringFogMethodVisitor.kt` | `sample/app` release DEX 反编译（明文=0）+ `base64LegacyXorRoundTrip` |
| Kotlin 字符串加密 | ✅ | 同上（字节码层语言无关） | `KotlinSecrets` release DEX 反编译（明文=0）|
| APK（Application 变体） | ✅ | `StringFogPlugin.kt` withId `com.android.application` | `sample/app` 真实 APK 反编译 |
| AAR / JAR（Library 变体） | ✅ | `StringFogPlugin.kt` withId `com.android.library` | `sample/lib` 真实 AAR `classes.jar` javap 反编译 |
| XOR + Base64（默认） | ✅ | `stringfog/XorFog.java` | `xorRoundTripBoundary` + APK 反编译 + v1.1.0 兼容测试 |
| AES-128/CBC | ✅ | `stringfog/AesFog.java` | `aesRoundTripBoundary` / `base64AesRoundTrip` + AES 变体 APK 反编译（3 参形态）|
| 每串随机密钥 `IKeyGenerator` | ✅ | `stringfog/IKeyGenerator.java`、`RandomKeyGenerator.java`、`FogConfigResolver.kt` | `randomKeyGenerator` / `randomKeyPerStringDistinctCipher` + APK 反编译（每串密钥字面量各异）|
| bytes 免 Base64 模式 | ✅ | `StringFogMethodVisitor.emitBytesMode` + `StringFogRuntime.decrypt(byte[]…)` | `bytesLegacyXorRoundTrip` / `bytesAesRoundTrip` + APK 反编译（`fill-array-data` + `decrypt([B)`）|
| mapping 映射文件 | ✅ | `plugin/core/MappingWriter.kt` | `mappingWriterConcurrent` + `sample/app` 真实映射产物 |
| 算法自主扩展 | ✅ | `stringfog/IStringFog.java`、`FogConfigResolver.loadCustom`、`StringFogRuntime.register` | `customAlgorithmRegister` / `customAlgorithmByFqcn`（FQCN 反射）|
| 总开关 / kill-switch | ✅ | `StringFogPlugin.kt`（DSL `enabled` + `-Pva.stringfog.enabled`) | 代码路径 + release-only（debug 明文=1 实测）|
| fogPackages 白名单 | ✅ | `plugin/core/PackageFilter.shouldInstrument` | `packageFilter`（纯逻辑单测）|
| excludePackages 黑名单 | ✅ | 同上（黑名单优先） | `packageFilter`（重叠时 exclude 优先）|
| minLength 阈值 | ✅ | `StringFogMethodVisitor.shouldFog` | `minLengthBoundary` |
| 超长串边界防护 | ✅ | `StringFogMethodVisitor.shouldFog` + `FogConfigResolver` maxUtf8 阈值 | `oversizedStringSkipped` |
| 拼接常量不误伤 | ✅ | 仅改 LDC 常量（invokedynamic BSM 常量不触碰） | `concatenationNotBroken` |
| 配置校验 / 冲突告警 | ✅ | `StringFogPlugin.validateConfig` | minLength<0 抛错、include∩exclude 告警（代码路径）|
| 完全 Gradle 自动化 | ✅ | `onVariants` 注册 | sample 仅 apply 插件即全自动加密 |
| 向后兼容 v1.1.0 | ✅ | `StringFogRuntime.decrypt(String)` + `DEFAULT_KEY` | `backwardCompatWithV110`（逐字节还原）|

---

## 加密前 / 加密后 / 运行时（真实反编译证据）

以下均来自本仓 `sample/` 模块 **v2.1.0 真实构建产物**的 `dexdump` / `javap` 反编译（`build-tools 36.0.0`），非虚构。
debug 变体不插桩（release-only），故 debug DEX 即「加密前」，release DEX 即「加密后」。

**① Java 加密后（release DEX，默认 XOR，单参路径）**

```smali
# JavaSecrets.apiToken()  —— release DEX
const-string v0, "CVJjayoOcl0bDRMnFCUtay4UfxNYbAZGfHlrXjsTC1cfXw=="
invoke-static {v0}, Lcom/vapro/vae/stringfog/StringFogRuntime;.decrypt:(Ljava/lang/String;)Ljava/lang/String;
# 反编译 release DEX 内 "sk-JAVA-PLAINTEXT-1234567890abcdef" 计数 = 0（明文消失）；debug 计数 = 1（明文）
```

**② Kotlin 同样被加密**（证明 java/kotlin 双支持）：

```smali
# KotlinSecrets.apiToken()  —— release DEX
const-string v0, "CVJjaiQMfzkFbAIiGzgmZz9hGgwKOlAULidiX2hCXAZMDnYY"
invoke-static {v0}, Lcom/vapro/vae/stringfog/StringFogRuntime;.decrypt:(...)
```

**③ AES 模式（`-Psf.algo=aes -Psf.key=my-release-key-2026`，三参路径）** —— 密钥与算法 id 随密文入栈：

```smali
# JavaSecrets.apiToken()  —— release DEX
const-string v0, "my-release-key-2026"
const-string v1, "aes"
const-string v2, "DkySB3hkRyzW6kHRx6VGwjISWgTY+L+oaRDZ1qGGh7e/R0V9IJoX9Ny/xEOoRt5ik7jOjFJWwwOhz8hcEr+FJQ=="
invoke-static {v2, v0, v1}, L…/StringFogRuntime;.decrypt:(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
```

**④ 每串随机密钥（`-Psf.randomKey=true`）** —— 每串一个随机 16 位密钥字面量（6 串实测 6 个互异密钥）：

```smali
# JavaSecrets.apiToken()  —— release DEX
const-string v0, "xni1fX647FTX3nn9"       # 该串专属随机密钥
const-string v1, "xor"
const-string v2, "CwVEeycOdxlnChURfTorYSxDWANVbAMCAH5taFIMDV0dCA=="
invoke-static {v2, v0, v1}, L…/StringFogRuntime;.decrypt:(3×String)String
```

**⑤ bytes 模式（`-Psf.bytes=true`）** —— 无 Base64 文本，密文以 `fill-array-data` 存储：

```smali
# JavaSecrets.apiToken()  —— release DEX（方法内 const-string 计数 = 0）
new-array v0, v0, [B
fill-array-data v0, 0000000c
invoke-static {v0}, L…/StringFogRuntime;.decrypt:([B)Ljava/lang/String;
```

**⑥ AAR 库端到端（`sample/lib` → `lib-release.aar` → `classes.jar` → `javap -c`）** —— 库字符串同样被加密：

```text
# LibJavaSecrets.libApiToken()  —— AAR classes.jar
0: ldc           #35    // String CVJjbSIaHjoKFxNDCj0pejRtC3k/dVIROWwzDDkVDVVLC30VXm4=
2: invokestatic  #33    // Method com/vapro/vae/stringfog/StringFogRuntime.decrypt:(String)String
# classes.jar 内 4 条库明文（Java+Kotlin）grep 计数均 = 0
```

> 复现：仓库根 `./gradlew publishToMavenLocal` →
> `./gradlew -p sample :app:assembleDebug :app:assembleRelease :lib:assembleRelease`（各模式加 `-Psf.*`）→
> `dexdump -d`（APK）/ `javap -c`（AAR classes.jar）对比即得上述证据。

---

## 快速接入

StringFogPro 通过 JitPack 发布两个坐标：插件 `stringfog-gradle-plugin` 与运行时 `stringfog`。

### 第 1 步：声明 JitPack 仓库

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
        google(); mavenCentral(); gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
        google(); mavenCentral()
    }
}
```

### 第 2 步：引入插件（二选一）

**方式 A（推荐，JitPack 最稳）：buildscript classpath + apply**

根 `build.gradle.kts`：

```kotlin
buildscript {
    repositories {
        maven { url = uri("https://jitpack.io") }
        google(); mavenCentral()
    }
    dependencies {
        classpath("com.github.Pangu-Immortal.StringFogPro:stringfog-gradle-plugin:v2.1.0")
    }
}
```

模块 `app/build.gradle.kts`：`apply(plugin = "com.va.stringfog")`。

**方式 B（声明式 `plugins {}`）：在 `settings.gradle.kts` 的 `pluginManagement` 加 `resolutionStrategy` 映射**

> 说明：JitPack 服务 module 坐标但不服务插件 marker（marker 属 `com.va.stringfog` 命名空间，
> JitPack 只服务 `com.github.*`）。用 `resolutionStrategy` 把插件 id 映射到 module 即可用 `plugins {}`
> （已实测该 module 坐标可解析装载插件）。

```kotlin
pluginManagement {
    repositories { maven { url = uri("https://jitpack.io") }; google(); mavenCentral(); gradlePluginPortal() }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.va.stringfog") {
                useModule("com.github.Pangu-Immortal.StringFogPro:stringfog-gradle-plugin:${requested.version}")
            }
        }
    }
}
```

之后模块即可：`plugins { id("com.va.stringfog") version "v2.1.0" }`。

### 第 3 步：应用插件 + 引入运行时 + 配置

`app/build.gradle.kts`：

```kotlin
plugins {
    id("com.android.application")
    id("com.va.stringfog") version "v2.1.0"   // 或方式 A 的 apply(plugin = "com.va.stringfog")
}

dependencies {
    // 运行时解密器（零依赖纯 Java JAR）
    implementation("com.github.Pangu-Immortal.StringFogPro:stringfog:v2.1.0")
}

// 可选配置；不写则零配置走默认 XOR（向后兼容 v1.1.0）
stringfog {
    enabled.set(true)
    algorithm.set("xor")                 // "xor" | "aes" | 自定义 IStringFog 全限定类名
    // key.set("your-release-key")       // 不设则 xor 用默认密钥、aes 用内置默认密钥
    minLength.set(1)
    // bytesMode.set(true)               // 密文以 byte[] 发射，免 Base64
    // randomKeyPerString.set(true)      // 每串独立随机密钥
    // mappingEnabled.set(true)          // 输出 build/outputs/stringfog/ 映射文件
    // fogPackages.add("com.your.app")           // 只加密这些包（留空=全部）
    // excludePackages.add("com.your.app.model")  // 排除这些包（优先级更高）
}
```

搞定。执行 `./gradlew assembleRelease`，release 产物里的字符串即被自动加密；debug 不受影响。

> 全局临时关闭：`./gradlew assembleRelease -Pva.stringfog.enabled=false`。

---

## 配置项参考

`stringfog { }`（`com.vapro.vae.stringfog.plugin.StringFogExtension`）：

| 配置项 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `enabled` | `Boolean` | `true` | 总开关；亦可用 `-Pva.stringfog.enabled=false` 全局关闭 |
| `algorithm` | `String` | `"xor"` | `xor` / `aes` / 自定义 `IStringFog` 全限定类名 |
| `key` | `String` | 内置默认 | 密钥字符串；未设时 xor 走 `DEFAULT_KEY`（兼容 v1.1.0），aes 走内置默认密钥 |
| `fogPackages` | `List<String>` | `[]` | 仅加密这些包前缀下的类（为空=全部加密） |
| `excludePackages` | `List<String>` | `[]` | 排除这些包前缀下的类（优先级高于 `fogPackages`） |
| `minLength` | `Int` | `1` | 最小加密串长阈值，短于此长度的串保持明文 |
| `bytesMode` | `Boolean` | `false` | 密文以原始 `byte[]` 字面量发射（免 Base64，改变静态特征） |
| `randomKeyPerString` | `Boolean` | `false` | 每个字符串独立随机密钥（强制三参路径，抗统计分析） |
| `randomKeyLength` | `Int` | `16` | 每串随机密钥长度（字符数，仅 `randomKeyPerString` 生效） |
| `mappingEnabled` | `Boolean` | `false` | 输出「明文→密文」映射到 `build/outputs/stringfog/stringfog-mapping-<variant>.txt` |

**配置健壮性**：插件在注册插桩前做校验——`minLength` 为负 / `algorithm` 空白 / `randomKeyLength` 非正 → **抛错早失败**；`fogPackages ∩ excludePackages` 重叠、`aes`/自定义算法未设 key → **告警不阻塞**（exclude 优先、内置默认密钥仅演示用）。

---

## 算法自主扩展

实现 `IStringFog`（构建期加密与运行时解密的唯一契约），构建期用 DSL 指定其全限定类名、运行时以同名 id 注册即可。

**1）实现算法**（放在能被 buildscript classpath 与 App 同时看到的位置）：

```java
package com.your.app.crypto;

import com.vapro.vae.stringfog.IStringFog;

public final class MyFog implements IStringFog {
    @Override public byte[] encrypt(byte[] data, byte[] key) { return transform(data, key); }
    @Override public byte[] decrypt(byte[] data, byte[] key) { return transform(data, key); }
    // shouldFog 有默认实现（过滤 null/空串），可选覆写
    private byte[] transform(byte[] d, byte[] k) { /* ... */ return d; }
}
```

**2）构建期指定**（`stringfog {}`）：`algorithm.set("com.your.app.crypto.MyFog")`（全限定类名，构建期反射构造，需 public 无参构造）。该实现类需在**插件的 buildscript classpath** 上可见。

**3）运行时注册**（App 早期，如 `Application.onCreate`）：

```java
StringFogRuntime.register("com.your.app.crypto.MyFog", new com.your.app.crypto.MyFog());
```

内置 `xor` / `aes` 已默认注册，无需手动 register。

---

## 适配测试矩阵

> 全部为**可复现的真实证据**：`./gradlew test`（24 单测/集成）、真实 APK/AAR 反编译、AGP 8.7 与 9.2 双档真实构建。声明=测试，未测的诚实标注。

### 一、算法正确性 & 边界（运行时单测 · `stringfog/src/test/.../StringFogRuntimeTest.java`）

| 维度 | 结果 | 证据（测试方法）|
| --- | :---: | --- |
| XOR 往返（含空/超长/中文/emoji/Base64字符/多行）| ✅ | `xorRoundTripBoundary` |
| AES 往返 + 随机 IV 密文每次不同 | ✅ | `aesRoundTripBoundary` |
| 运行时四入口：Base64 单参 / Base64 三参 / bytes 单参 / bytes 三参 | ✅ | `runtimeBase64LegacyRoundTrip` / `runtimeBase64AesRoundTrip` / `runtimeBytesLegacyRoundTrip` / `runtimeBytesMultiRoundTrip` |
| v1.1.0 逐字节向后兼容 | ✅ | `backwardCompatWithV110` |
| 自定义算法运行时注册后可解密 | ✅ | `customAlgorithmRegister` |
| 每串随机密钥：长度/字符集/差异性/往返 | ✅ | `randomKeyGenerator`、`randomKeyGeneratorInvalidLength` |
| 防御性回传 + `shouldFog` 默认实现 | ✅ | `defensiveFallback` |

### 二、插桩与配置 E2E（插件集成测试 · `stringfog-gradle-plugin/src/test/.../StringFogTransformTest.kt`）

> 手法：ASM 生成含明文类 → 核心访问器插桩（`ClassWriter(0)` 不重算 maxStack，真实检验栈补偿）→ 加载 → 反射调用 → 断言还原。栈补偿不足会在加载时 `VerifyError`。

| 维度 | 结果 | 证据（测试方法）|
| --- | :---: | --- |
| Base64 单参（XOR）明文消失 + 还原 | ✅ | `base64LegacyXorRoundTrip` |
| Base64 三参（AES）+ maxStack 补偿 | ✅ | `base64AesRoundTrip` |
| Base64 三参（自定义密钥 XOR）| ✅ | `base64XorCustomKeyRoundTrip` |
| bytes 单参（XOR，byte[] 构造栈补偿）| ✅ | `bytesLegacyXorRoundTrip` |
| bytes 三参（AES，byte[] 构造栈补偿）| ✅ | `bytesAesRoundTrip` |
| Unicode/emoji（补充平面字符）ASM 往返 | ✅ | `unicodeEmojiRoundTrip` |
| 每串随机密钥：相同明文两处密文不同 + 各自还原 | ✅ | `randomKeyPerStringDistinctCipher` |
| 自定义算法（FQCN 反射）构建期加密 + 运行时还原 | ✅ | `customAlgorithmByFqcn` |
| `minLength` 阈值：空串/短串跳过、达标加密 | ✅ | `minLengthBoundary` |
| 超长串（超 maxUtf8）跳过、保持明文 | ✅ | `oversizedStringSkipped` |
| 字符串拼接常量安全加密并重组 | ✅ | `concatenationNotBroken` |
| 包过滤：白名单/黑名单/运行时类排除/重叠 exclude 优先 | ✅ | `packageFilter` |
| 映射写入多线程并发不丢行 | ✅ | `mappingWriterConcurrent` |

### 三、语言 × 产物类型（真实反编译）

| 维度 | 结果 | 证据 |
| --- | :---: | --- |
| Java @ APK（Application）| ✅ | `sample/app` release DEX：`JavaSecrets` 4 明文计数=0；debug=1 |
| Kotlin @ APK（Application）| ✅ | `sample/app` release DEX：`KotlinSecrets` 明文计数=0 + decrypt 调用 |
| Java @ AAR（Library）| ✅ | `sample/lib` `classes.jar` `javap -c LibJavaSecrets`：`ldc 密文` + `decrypt`；明文=0 |
| Kotlin @ AAR（Library）| ✅ | `sample/lib` `classes.jar` `javap -c LibKotlinSecrets`：同上 |

### 四、AGP / Gradle / JDK 工具链

| 维度 | 结果 | 证据 |
| --- | :---: | --- |
| **AGP 9.2.1 + Gradle 9.6 + JDK 21 + compileSdk 36** | ✅ 实测 | `sample/`（主样例）真实 APK+AAR 反编译 |
| **AGP 8.7.3 + Gradle 8.13 + JDK 21 + compileSdk 35** | ✅ 实测 | `sample-agp8/` 真实 APK：release 明文=0 + `decrypt` 调用；debug 明文=1 |
| AGP 7.2 ~ 8.6 | ⚠️ 未逐版实测 | API 依据：插件仅用 `AsmClassVisitorFactory`/`onVariants`/`InstrumentationScope`/`FramesComputationMode`，均自 **AGP 7.2** 起稳定；理论兼容，未在本环境逐版构建 |
| JDK 17（下限）| ⚠️ 未实测 | 产物 targetCompatibility=17，源码 ≤17；本环境以 **JDK 21** 构建/测试，17 理论可用未实测 |

> **`./gradlew test` 汇总：24 / 24 通过（0 失败 0 错误）** —— `stringfog` 11 + `stringfog-gradle-plugin` 13。

---

## 版本支持表

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| AGP | **8.7.3 与 9.2.1（双档实测）** | 使用 `AsmClassVisitorFactory`（AGP **7.2+** 提供该 API）；7.2~8.6 理论兼容但未逐版实测 |
| Gradle | **8.13 与 9.6.0（随 AGP 双档实测）** | 主构建 wrapper 锁定 9.6.0；`sample-agp8` 锁定 8.13 |
| Kotlin | 插件随 Gradle `kotlin-dsl` 内嵌（Gradle 9.6 → Kotlin 2.0.x）；sample 用 AGP 内置 Kotlin | 插件源码 100% Kotlin |
| JDK | 构建/测试 **JDK 21（实测）**；产物字节码 **Java 17**（下限，未单独实测 17）| 低版本字节码向上兼容 |
| ASM | **9.9** | 由 AGP 内置提供（插件 `compileOnly`）|
| minSdk | **26+** | 运行时使用 `java.util.Base64`（API 26 起可用）；sample 取 28 |

---

## 工程结构

```
StringFogPro/
├── stringfog/                     # 运行时 + 算法库（纯 Java，零依赖，JitPack 发布）
│   └── src/main/java/com/vapro/vae/stringfog/
│       ├── IStringFog.java        # 加解密统一契约（encrypt/decrypt/默认 shouldFog）
│       ├── IKeyGenerator.java     # 每串密钥生成器契约
│       ├── RandomKeyGenerator.java# 随机每串密钥实现（字母数字，UTF-8 稳定往返）
│       ├── XorFog.java            # 默认 XOR 循环密钥（兼容 v1.1.0）
│       ├── AesFog.java            # AES-128/CBC/PKCS5（随机 IV 前置，静态 SecureRandom）
│       └── StringFogRuntime.java  # 运行时解密调度器（Base64/bytes × 单参/三参 = 四入口 + 注册表）
├── stringfog-gradle-plugin/       # AGP 插桩插件（100% Kotlin，JitPack 发布）
│   └── src/main/kotlin/com/vapro/vae/stringfog/plugin/
│       ├── StringFogPlugin.kt     # onVariants release 注册（application/library）+ 配置校验
│       ├── StringFogExtension.kt  # stringfog { } DSL
│       ├── StringFogParams.kt     # InstrumentationParameters（可缓存 @Input）
│       ├── StringFogFactory.kt    # AsmClassVisitorFactory（委托 PackageFilter + 配置缓存）
│       └── core/                  # 纯 ASM 变换核心（不依赖 AGP，可独立单测）
│           ├── FogConfig.kt / FogConfigResolver.kt   # 配置解析（算法/密钥/bytes/每串密钥/映射）
│           ├── PackageFilter.kt                       # 类过滤纯逻辑（可单测）
│           ├── MappingWriter.kt                       # 明文→密文映射输出（线程/进程安全）
│           ├── StringFogClassVisitor.kt               # 捕获类名 + 包装方法访问器
│           └── StringFogMethodVisitor.kt              # LDC 加密替换（四形态发射 + 栈补偿 + 边界防护）
├── sample/                        # 演示多模块（:app application APK + :lib library AAR）
└── sample-agp8/                   # 多 AGP 适配第二档（AGP 8.7.3 + Gradle 8.13）独立构建
```

---

## 设计说明与已知边界

- **为什么运行时是 Java 而不是 Kotlin？** 运行时 JAR 会被打进每个消费方 APK，目标是**零依赖、极小、加载顺序无关**。用 Kotlin 会强制引入 `kotlin-stdlib`（约 1.5MB+），破坏「零依赖纯 JAR」定位（POM 实测零 dependency）。加密工作在 ASM 字节码层，与源语言无关，Kotlin 业务类同样被加密（见上文证据）。
- **密钥并非密码学机密。** 密钥（含每串随机密钥）随插桩字节码嵌入 APK（原版亦然）。字符串雾化的目标是**抵御 `strings`/grep 明文扫描与静态提取、抬高逆向成本**，而非提供不可破解的加密。每串随机密钥的价值是**抗统计聚类 + 无共享密钥可 grep**，不改变「密钥可从字节码取得」这一前提。
- **编译期常量（`const val` / `static final String`）不被加密。** 它们以 class 文件的 `ConstantValue` 属性内联，非方法体 `LDC` 指令，ASM 插桩不覆盖；请对敏感串使用方法返回值或非 const 字段（sample 已示范）。
- **非常量拼接不误伤。** 仅改 `LDC` String 常量（编译期真常量，解密后返回同值，拼接照常）；Java 9+ 的 `invokedynamic makeConcatWithConstants` 常量在 BSM 引导参数中、非 LDC，不触碰（`concatenationNotBroken` 验证）。
- **超长串跳过。** 超过阈值（Base64 模式 45000 / bytes 模式 8000 UTF-8 字节）的串保持明文，避免常量池 65535 上限 / 方法体越限生成非法 class（`oversizedStringSkipped` 验证）。远超任何真实密钥/URL 长度。
- **帧模式 `COPY_FRAMES`。** 各插桩形态净栈中性（净 push 1 ref），故 `StackMapTable` 不变；仅操作数栈峰值升高，由 `MethodVisitor.visitMaxs` 按各形态额外栈深精确补偿（单参 +0 / Base64 三参 +2 / bytes +3），补偿不足会 `VerifyError`——由 `ClassWriter(0)` 集成测试真实拦截。
- **bytes 模式体积。** 见[升级章诚实说明](#相对原版-stringfog-的升级)：短串近似持平，净收益随串长/串数增长；首要价值是抹除 Base64 文本特征。
- **映射文件。** 追加写 + JVM 锁 + 文件锁（跨 worker 进程安全）；仅诊断用途，非构建正确性关键路径。增量构建下未重插桩的类不重复写，**完整映射以一次 `clean` 全量构建为准**。
- **作用域 `PROJECT`。** 仅加密本模块源码，不加密依赖（避免二次加密 / 加密 AndroidX）。

---

## Roadmap（尚未实现，诚实标注）

以下为**暂未实现/未完全实现**的能力，明确列出不谎称已完成：

- [ ] **`plugins {}` 免接线声明式（Gradle Plugin Portal marker）** —— 当前 JitPack 下走 buildscript classpath（方式 A）或 `resolutionStrategy` 映射（方式 B，已实测可解析）。**完全免配置**的 `plugins { id("com.va.stringfog") }` 需把插件 marker 发布到 Gradle Plugin Portal，**需外部发布账号**（本项目暂无），故诚实标注未做。
- [ ] **AGP 7.2 ~ 8.6 逐版实测** —— API 层理论兼容（`AsmClassVisitorFactory` 自 7.2 稳定），已实测 8.7.3 与 9.2.1 两档；中间版本未逐个真实构建。
- [ ] **JDK 17 下限实测** —— 产物面向 Java 17，本环境以 JDK 21 构建/测试；JDK 17 未单独跑。
- [ ] **R8/混淆共存实测** —— sample 关闭 R8 以聚焦插桩效果；与 R8 shrink/optimize 同时开启的端到端验证未做（插桩发生在 R8 前，理论无冲突）。

> 已在 v2.1.0 **补齐**的原 Roadmap 项：每串随机密钥（`IKeyGenerator`）、bytes 免 Base64 模式、mapping 映射文件、AAR 库端到端反编译证据——均附实现位与测试/反编译证据（见上）。

---

## 更新日志

### v2.1.0（生产级打磨）
- **补齐能力**：每串随机密钥（`IKeyGenerator` / `RandomKeyGenerator`）、bytes 免 Base64 模式（`byte[]` 字面量 → `fill-array-data`）、构建期明文→密文映射文件（`MappingWriter`，线程/进程安全）、AAR 库端到端反编译证据（`sample/lib`）。
- **运行时四入口**：Base64/bytes × 单参/三参 的 `decrypt` 重载，与构建期四种发射形态一一对应。
- **代码精修**：`shouldFog` 下沉为接口默认实现；ASM 边界防护（超长串跳过、拼接常量不误伤）；魔数/描述符集中常量化；`AesFog` 静态 `SecureRandom`；类过滤纯逻辑抽出 `PackageFilter`（可单测）；线程安全审计（`ConcurrentHashMap` 注册表 + 无状态算法）。
- **配置健壮性**：`minLength`/`algorithm`/`randomKeyLength` 校验早失败；`fogPackages ∩ excludePackages` 重叠告警；`aes` 默认密钥告警。
- **适配矩阵**：24/24 单测/集成通过；AGP 8.7.3 + Gradle 8.13 与 AGP 9.2.1 + Gradle 9.6 双档真实构建；Java/Kotlin × APK/AAR 真实反编译证据。
- **向后兼容**：默认路径逐字节兼容 v1.1.0；v2.0.0 历史 tag 保留。

### v2.0.0（超集升级）
- 多模块化：`stringfog`（运行时+算法）+ `stringfog-gradle-plugin`（AGP 插件）+ `sample`（演示）。
- 统一 `IStringFog` 契约；内置 **AES-128/CBC**（回归原版 v3.0.0 删除的 AES）；算法自主扩展（构建期反射 + 运行时 `register`）。
- 细粒度配置：`algorithm` / `key` / `fogPackages` / `excludePackages` / `minLength`。
- 运行时保持**零依赖纯 Java JAR**；默认路径逐字节兼容 v1.1.0。

### v1.1.0
- 单模块纯 Java 运行时库：`StringFogRuntime.decrypt(String)`（XOR + Base64）。

---

## 致谢与 License

本项目基于 [MegatronKing/StringFog](https://github.com/MegatronKing/StringFog)（Apache-2.0）思想改写并做现代化超集升级，向原作者致谢。

```
Copyright 2017 MegatronKing (original StringFog)
Copyright 2026 StringFogPro contributors (VirtualApp adaptation & superset upgrade)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

详见 [LICENSE](LICENSE)。

---

## Star 趋势

[![Star History Chart](https://api.star-history.com/svg?repos=Pangu-Immortal/StringFogPro&type=Date)](https://star-history.com/#Pangu-Immortal/StringFogPro&Date)

---

<a name="english"></a>

## English

**StringFogPro** is a Gradle plugin that automatically encrypts Java/Kotlin string literals in your APK/AAR at build time, replacing plaintext with `ciphertext + runtime-decrypt call` — decompilers only see garbage, so hardcoded endpoints, keys and tokens no longer sit in the clear.

It is a **modernized superset** of the classic [StringFog](https://github.com/MegatronKing/StringFog) (Apache-2.0):

- **AGP 8.7 & 9.2 both verified** with modern instrumentation (`AsmClassVisitorFactory` + `onVariants`), Gradle 8.13 & 9.6.
- **Built-in AES-128/CBC** (original removed AES in v3.0.0) alongside the default XOR+Base64.
- **Per-string random keys** (`IKeyGenerator`): identical plaintext yields different ciphertext, defeating statistical clustering — no grep-able shared key.
- **Bytes mode** (no Base64): ciphertext emitted as a raw `byte[]` literal (d8 folds it into `fill-array-data`), erasing the recognizable Base64 signature.
- **Build-time mapping file**: optional `plaintext → ciphertext` audit map.
- **Pluggable algorithms** via a single `IStringFog` contract (build-time encrypt & runtime decrypt share one impl).
- **Fine-grained config**: `fogPackages` include / `excludePackages` exclude / `minLength` threshold / global kill-switch, with validation & conflict warnings.
- **Zero-dependency pure-Java runtime** (no kotlin-stdlib; POM has zero dependencies — verified).
- **Backward compatible** with v1.1.0 byte-for-byte on the default path.

**24/24 unit/integration tests pass; real APK/AAR decompiled evidence; AGP 8.7.3 + 9.2.1 both really built.**

### Quick start

```kotlin
// settings.gradle.kts → add JitPack to pluginManagement & dependencyResolutionManagement
maven { url = uri("https://jitpack.io") }

// root build.gradle.kts — recommended path on JitPack: buildscript classpath
buildscript {
    dependencies {
        classpath("com.github.Pangu-Immortal.StringFogPro:stringfog-gradle-plugin:v2.1.0")
    }
}

// app/build.gradle.kts
apply(plugin = "com.va.stringfog")
dependencies {
    implementation("com.github.Pangu-Immortal.StringFogPro:stringfog:v2.1.0")
}
```

For the declarative `plugins { id("com.va.stringfog") }` form on JitPack, add a `resolutionStrategy { eachPlugin { … useModule("…:stringfog-gradle-plugin:…") } }` mapping in `pluginManagement` (see the Chinese Quick-start, method B). A frictionless marker requires publishing to the Gradle Plugin Portal (Roadmap).

Run `./gradlew assembleRelease` — release strings are encrypted automatically; debug stays untouched.

See the Chinese sections above for the full feature/code/test matrix, real decompiled before/after evidence (XOR / AES / per-string key / bytes / AAR), configuration reference, the adaptation test matrix, version support table, and an honest Roadmap.

Licensed under Apache-2.0. Original StringFog © MegatronKing.
