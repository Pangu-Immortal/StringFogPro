# StringFogPro

<div align="center">

![StringFogPro 访问计数](https://count.getloli.com/get/@StringFogPro?theme=rule34)

<p>
  <b>给字符串加上一层雾霭，使人难以窥视其真面目。<br/>
  如果对你有帮助，请点一个 <a href="https://github.com/Pangu-Immortal/StringFogPro/stargazers">Star</a> 支持一下！</b>
</p>

[![JitPack](https://jitpack.io/v/Pangu-Immortal/StringFogPro.svg)](https://jitpack.io/#Pangu-Immortal/StringFogPro)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/Release-v2.0.0-brightgreen.svg)](https://github.com/Pangu-Immortal/StringFogPro/releases)
[![AGP](https://img.shields.io/badge/AGP-9.2.1-green.svg)](https://developer.android.com/build)
[![Gradle](https://img.shields.io/badge/Gradle-9.6-02303A.svg)](https://gradle.org)
[![JDK](https://img.shields.io/badge/JDK-17%2B-orange.svg)](https://adoptium.net)
[![Platform](https://img.shields.io/badge/Platform-Android-brightgreen.svg)](https://developer.android.com)

**简体中文** | [English](#english)

</div>

> **TL;DR — 为什么选 StringFogPro？**
>
> StringFogPro 是一款自动加密 APK / AAR 中 Java·Kotlin 字符串字面量的 Gradle 插件，构建期把明文替换为「密文 + 运行时解密调用」，反编译只能看到乱码，让硬编码的接口地址、密钥、Token 不再裸奔。它在经典 [StringFog](https://github.com/MegatronKing/StringFog)（Apache-2.0）的基础上做了**现代化超集升级**：拥抱 **AGP 9.x** 现代插桩 API（`AsmClassVisitorFactory` + `onVariants`），**内置 AES**（原版 v3.0.0 已删除 AES），提供 **XOR / AES / 自主扩展算法**三选一，支持**按包 include/exclude + 最小串长阈值**细粒度配置，运行时是**零依赖纯 Java JAR**，一次 `./gradlew assembleRelease` 全自动完成，零侵入业务代码。

---

## 目录

- [核心特性](#核心特性)
- [相对原版 StringFog 的升级](#相对原版-stringfog-的升级)
- [功能对照表（本项目 vs 原版）](#功能对照表本项目-vs-原版)
- [加密前 / 加密后 / 运行时（真实反编译证据）](#加密前--加密后--运行时真实反编译证据)
- [快速接入](#快速接入)
- [配置项参考](#配置项参考)
- [算法自主扩展](#算法自主扩展)
- [版本支持表](#版本支持表)
- [工程结构](#工程结构)
- [设计说明与已知边界](#设计说明与已知边界)
- [Roadmap（尚未实现，诚实标注）](#roadmap尚未实现诚实标注)
- [更新日志](#更新日志)
- [致谢与 License](#致谢与-license)

---

## 核心特性

- **支持 Java / Kotlin** —— 工作在 ASM 字节码层，与源语言无关；Java 与 Kotlin 编译出的类被同样加密（本文附真实反编译证据）。
- **支持 APK 加密** —— `com.android.application`（Application 变体）release 构建自动插桩。
- **支持 AAR / JAR 库加密** —— `com.android.library`（Library 变体）走同一套 ASM 变换核心。
- **加解密算法自主扩展** —— 统一 `IStringFog` 契约，内置 **XOR + Base64（默认）** 与 **AES-128/CBC**，可注册任意自定义算法，构建期加密与运行时解密走同一实现。
- **细粒度可选配置** —— `enabled` 总开关 + `-Pva.stringfog.enabled=false` 全局关；按包 `fogPackages` 白名单 / `excludePackages` 黑名单；`minLength` 最小加密串长阈值。
- **完全 Gradle 自动化** —— `onVariants` 自动注册，仅需 apply 插件，零手工接线；仅 release 插桩，debug 保持明文便于调试。
- **零依赖纯 Java 运行时** —— 运行时 JAR 仅依赖 JDK（`Base64` / `javax.crypto`），不引入 kotlin-stdlib，POM 零依赖，可嵌入任意 App。
- **向后兼容** —— 零配置默认路径与 v1.1.0 逐字节等价（同一 XOR 密钥），历史工程可零成本迁移。

---

## 相对原版 StringFog 的升级

| 维度 | 原版 StringFog（最新 5.x） | StringFogPro v2.0.0 | 是否真实现 |
| --- | --- | --- | --- |
| AGP 基线 | AGP 8.x / Gradle 8 | **AGP 9.2.1 / Gradle 9.6**（`AsmClassVisitorFactory` + `onVariants`） | ✅ 已验证 |
| AES 算法 | v3.0.0 起**已删除** | **内置回归**（AES-128/CBC/PKCS5，随机 IV 前置） | ✅ 已验证 |
| 排除包 exclude | v1.4.0 起**已移除** | **重新提供** `excludePackages` 黑名单 | ✅ 已实现 |
| 最小串长阈值 | 无 | 新增 `minLength` | ✅ 已验证 |
| 配置形态 | Groovy DSL 为主 | 类型化 **Kotlin DSL**（`Property`/`ListProperty`） | ✅ 已实现 |
| 运行时依赖 | 需引对应算法库 | **零依赖纯 Java JAR**（POM 无任何 dependency） | ✅ 已验证 |
| 自定义算法 | `IStringFog` + `implementation` | `IStringFog` + 构建期反射 + 运行时 `register` | ✅ 已验证 |

> 诚实声明：原版在**每串随机密钥（`IKeyGenerator`）**、**bytes 模式（密文以原始 `byte[]` 存在，免 Base64）**、**mapping 映射文件**三项上仍领先，StringFogPro 暂以全局密钥 + Base64 实现，相关项列入 [Roadmap](#roadmap尚未实现诚实标注)，不谎称已完成。

---

## 功能对照表（本项目 vs 原版）

> 凡打 ✅ 均有对应已实现且验证过的代码，验证方式与代码位置一并列出。

| 能力 | 本项目 | 实现位置 | 验证方式 |
| --- | :---: | --- | --- |
| Java 字符串加密 | ✅ | `plugin/core/StringFogMethodVisitor.kt` | sample release DEX 反编译：明文消失（见下文） |
| Kotlin 字符串加密 | ✅ | 同上（字节码层语言无关） | `KotlinSecrets` release DEX 反编译：明文消失 |
| APK（Application 变体） | ✅ | `StringFogPlugin.kt` withId `com.android.application` | sample 为 application，release APK 已验证 |
| AAR / JAR（Library 变体） | ✅ | `StringFogPlugin.kt` withId `com.android.library` | 与 Application 共用 `onVariants` + 同一 ASM 核心（Application 端到端验证） |
| XOR + Base64（默认） | ✅ | `stringfog/XorFog.java` | 单元测试往返 + APK 反编译 + v1.1.0 兼容测试 |
| AES-128/CBC | ✅ | `stringfog/AesFog.java` | 单元测试往返 + AES 变体 APK 反编译 |
| 算法自主扩展 | ✅ | `stringfog/IStringFog.java`、`FogConfigResolver.kt`、`StringFogRuntime.register` | 单元测试：自定义 `not` 算法注册后解密成功 |
| 总开关 / kill-switch | ✅ | `StringFogPlugin.kt`（DSL `enabled` + `-Pva.stringfog.enabled`) | 代码路径 + release-only 生效 |
| fogPackages 白名单 | ✅ | `StringFogFactory.isInstrumentable` | 代码路径（`startsWith` 前缀过滤） |
| excludePackages 黑名单 | ✅ | `StringFogFactory.isInstrumentable` | 精确类名排除同源逻辑已被 sample 验证 |
| minLength 阈值 | ✅ | `StringFogMethodVisitor.shouldFog` | 单元测试：短于阈值的串保持明文 |
| 完全 Gradle 自动化 | ✅ | `onVariants` 注册 | sample 仅 apply 插件即全自动加密 |
| 向后兼容 v1.1.0 | ✅ | `StringFogRuntime.decrypt(String)` + `DEFAULT_KEY` | 单元测试：v1.1.0 逐字节密文被还原 |

---

## 加密前 / 加密后 / 运行时（真实反编译证据）

以下均来自本仓 `sample/` 模块**真实构建产物**的 `dexdump` 反编译（`build-tools 36.0.0`），非虚构。
debug 变体不插桩（release-only），故 debug DEX 即「加密前」，release DEX 即「加密后」。

**源码（`sample/.../JavaSecrets.java`）**

```java
public static String apiToken() {
    return "sk-JAVA-PLAINTEXT-1234567890abcdef";
}
```

**① 加密前（debug DEX）** —— 明文裸露在常量池：

```smali
# JavaSecrets.apiToken()Ljava/lang/String;
const-string v0, "sk-JAVA-PLAINTEXT-1234567890abcdef"
return-object v0
```

**② 加密后（release DEX，默认 XOR）** —— 明文被替换为密文 + 解密调用：

```smali
# JavaSecrets.apiToken()Ljava/lang/String;
const-string v0, "CVJjayoOcl0bDRMnFCUtay4UfxNYbAZGfHlrXjsTC1cfXw=="
invoke-static {v0}, Lcom/vapro/vae/stringfog/StringFogRuntime;.decrypt:(Ljava/lang/String;)Ljava/lang/String;
move-result-object v0
return-object v0
```

**③ 运行时** —— `StringFogRuntime.decrypt` 还原（Python 复算同一 XOR+Base64 佐证）：

```text
decrypt("CVJjayoOcl0bDRMnFCUtay4UfxNYbAZGfHlrXjsTC1cfXw==") => "sk-JAVA-PLAINTEXT-1234567890abcdef"
```

**Kotlin 同样被加密**（证明 java/kotlin 双支持）：

```smali
# KotlinSecrets.apiToken()Ljava/lang/String;  —— release DEX
const-string v0, "CVJjaiQMfzkFbAIiGzgmZz9hGgwKOlAULidiX2hCXAZMDnYY"
invoke-static {v0}, Lcom/vapro/vae/stringfog/StringFogRuntime;.decrypt:(...) => "sk-KOTLIN-PLAINTEXT-abcdef0123456789"
```

**AES 模式（三参路径）** —— `algorithm="aes"` 时，密钥与算法 id 随密文入栈：

```smali
# JavaSecrets.apiToken()  —— release DEX（algorithm=aes, key=my-release-key-2026）
const-string v0, "my-release-key-2026"
const-string v1, "aes"
const-string v2, "JoZuy4f/ntzFtMb4NehS+nSt6OQv/0/w5J14NP4+5uCnwUoN6zbsCfobCwNcsQMYVdWxyy1aCId1csKq89R/KQ=="
invoke-static {v2, v0, v1}, Lcom/vapro/vae/stringfog/StringFogRuntime;.decrypt:(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
```

> 复现：仓库根 `./gradlew publishToMavenLocal` → `./gradlew -p sample assembleDebug assembleRelease` →
> `dexdump -d`（或 `strings`）对比 debug/release DEX 即得上述证据。

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

### 第 2 步：buildscript classpath 引入插件

根 `build.gradle.kts`（或 `settings.gradle.kts` 的 `buildscript {}`）：

```kotlin
buildscript {
    repositories {
        maven { url = uri("https://jitpack.io") }
        google(); mavenCentral()
    }
    dependencies {
        classpath("com.github.Pangu-Immortal.StringFogPro:stringfog-gradle-plugin:v2.0.0")
    }
}
```

### 第 3 步：在 app / library 模块应用插件 + 引入运行时

`app/build.gradle.kts`：

```kotlin
plugins {
    id("com.android.application")
}
apply(plugin = "com.va.stringfog")

dependencies {
    // 运行时解密器（零依赖纯 Java JAR）
    implementation("com.github.Pangu-Immortal.StringFogPro:stringfog:v2.0.0")
}

// 可选配置；不写则零配置走默认 XOR（向后兼容 v1.1.0）
configure<com.vapro.vae.stringfog.plugin.StringFogExtension> {
    enabled.set(true)
    algorithm.set("xor")            // "xor" | "aes" | 自定义 IStringFog 全限定类名
    // key.set("your-release-key")  // 不设则 xor 用默认密钥、aes 用内置默认密钥
    minLength.set(1)
    // fogPackages.add("com.your.app")            // 只加密这些包（留空=全部）
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

---

## 算法自主扩展

实现 `IStringFog`（构建期加密与运行时解密的唯一契约），构建期用 DSL 指定其全限定类名、运行时以同名 id 注册即可。

**1）实现算法**（放在能被 buildscript classpath 与 App 同时看到的位置）：

```java
package com.your.app.crypto;

import com.vapro.vae.stringfog.IStringFog;

public final class MyFog implements IStringFog {
    @Override public byte[] encrypt(byte[] data, byte[] key) { /* 你的加密 */ return transform(data, key); }
    @Override public byte[] decrypt(byte[] data, byte[] key) { /* 你的解密 */ return transform(data, key); }
    @Override public boolean shouldFog(String value) { return value != null && !value.isEmpty(); }
    private byte[] transform(byte[] d, byte[] k) { /* ... */ return d; }
}
```

**2）构建期指定**（`stringfog {}`）：

```kotlin
configure<com.vapro.vae.stringfog.plugin.StringFogExtension> {
    algorithm.set("com.your.app.crypto.MyFog")  // 全限定类名，构建期反射构造（需 public 无参构造）
    key.set("your-key")
}
```
> 该实现类需在**插件的 buildscript classpath** 上可见（例如 `buildscript { dependencies { classpath(...) } }`）。

**3）运行时注册**（App 早期，如 `Application.onCreate`）：

```java
StringFogRuntime.register("com.your.app.crypto.MyFog", new com.your.app.crypto.MyFog());
```

内置 `xor` / `aes` 已默认注册，无需手动 register。

---

## 版本支持表

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| AGP | **9.2.1（验证基线）** | 使用 `AsmClassVisitorFactory`（AGP 7.2+ 提供该 API，8.x 理论兼容但未逐版验证） |
| Gradle | **9.6.0** | wrapper 锁定 |
| Kotlin | 插件随 Gradle `kotlin-dsl` 内嵌（Gradle 9.6 → Kotlin 2.0.x）；sample 用 AGP 9 内置 Kotlin | 插件源码 100% Kotlin |
| JDK | 构建/测试 **JDK 21**；产物字节码 **Java 17** | 低版本字节码向上兼容 |
| ASM | **9.9** | 由 AGP 内置提供（插件 `compileOnly`） |
| minSdk | **26+** | 运行时使用 `java.util.Base64`（API 26 起可用）；sample 取 28 |

---

## 工程结构

```
StringFogPro/
├── stringfog/                     # 运行时 + 算法库（纯 Java，零依赖，JitPack 发布）
│   └── src/main/java/com/vapro/vae/stringfog/
│       ├── IStringFog.java        # 加解密统一契约（encrypt/decrypt/shouldFog）
│       ├── XorFog.java            # 默认 XOR 循环密钥（兼容 v1.1.0）
│       ├── AesFog.java            # AES-128/CBC/PKCS5（随机 IV 前置）
│       └── StringFogRuntime.java  # 运行时解密调度器（单参兼容路径 + 三参可配置路径 + 算法注册表）
├── stringfog-gradle-plugin/       # AGP 9.x 加密插件（100% Kotlin，JitPack 发布）
│   └── src/main/kotlin/com/vapro/vae/stringfog/plugin/
│       ├── StringFogPlugin.kt     # onVariants release 注册（application/library 双兼容）
│       ├── StringFogExtension.kt  # stringfog { } DSL
│       ├── StringFogParams.kt     # InstrumentationParameters（可缓存 @Input）
│       ├── StringFogFactory.kt    # AsmClassVisitorFactory（类过滤 + 委托核心）
│       └── core/                  # 纯 ASM 变换核心（不依赖 AGP，可独立单测）
│           ├── FogConfig.kt / FogConfigResolver.kt
│           ├── StringFogClassVisitor.kt
│           └── StringFogMethodVisitor.kt  # LDC 字面量加密替换
└── sample/                        # 独立 Android 演示构建（含 Java+Kotlin 明文类，反编译佐证）
```

---

## 设计说明与已知边界

- **为什么运行时是 Java 而不是 Kotlin？** 运行时 JAR 会被打进每个消费方 APK，目标是**零依赖、极小、加载顺序无关**。用 Kotlin 会强制引入 `kotlin-stdlib`（约 1.5MB+），破坏「零依赖纯 JAR」定位。故运行时保持纯 Java（POM 实测零 dependency），而**插件源码 100% Kotlin**——且加密工作在 ASM 字节码层，与源语言无关，Kotlin 编写的业务类同样被加密（见上文 `KotlinSecrets` 证据）。
- **密钥并非密码学机密。** 密钥随插桩字节码嵌入 APK（原版亦然）。字符串雾化的目标是**抵御 `strings`/grep 明文扫描与静态提取**，抬高逆向成本，而非提供不可破解的加密。
- **编译期常量（`const val` / `static final String`）不被加密。** 它们以 class 文件的 `ConstantValue` 属性内联，非方法体 `LDC` 指令，ASM 插桩不覆盖；请对敏感串使用方法返回值或非 const 字段（sample 已示范）。
- **帧模式 `COPY_FRAMES`。** 单参路径栈中性；三参路径在 `visitMaxs` 显式补偿 +2 栈深，保证不越界（已由「加载+调用不抛 VerifyError」的集成测试证明）。
- **作用域 `PROJECT`。** 仅加密本模块源码，不加密依赖（避免二次加密 / 加密 AndroidX）。

---

## Roadmap（尚未实现，诚实标注）

以下为原版有、本项目**暂未实现**的能力，明确列出不谎称已完成：

- [ ] **每串随机密钥（`IKeyGenerator`）** —— 当前为模块级全局密钥；计划提供每字符串独立随机密钥以进一步抗模式分析。
- [ ] **bytes 模式** —— 当前密文统一 Base64 文本；计划支持密文以原始 `byte[]` 直接呈现于字节码（省 Base64 体积）。
- [ ] **mapping 映射文件** —— 计划输出加密映射表，便于排查。
- [ ] **AAR 库端到端反编译证据** —— Library 变体注册路径已实现并与 Application 共用核心，计划补充独立 AAR 反编译样例。
- [ ] **`plugins {}` 声明式插件解析** —— 当前 JitPack 下走 buildscript classpath + `apply`（稳定路径）；计划补 Gradle Plugin Portal 发布以支持 `plugins { id(...) }`。

---

## 更新日志

### v2.0.0（超集升级）
- 多模块化：`stringfog`（运行时+算法）+ `stringfog-gradle-plugin`（AGP 9.x 插件）+ `sample`（演示）。
- 统一 `IStringFog` 契约，构建期加密与运行时解密走同一实现。
- 内置 **AES-128/CBC**（随机 IV 前置），回归原版 v3.0.0 删除的 AES。
- 新增算法自主扩展（构建期反射 + 运行时 `register`）。
- 新增细粒度配置：`algorithm` / `key` / `fogPackages` / `excludePackages` / `minLength`。
- 运行时保持**零依赖纯 Java JAR**；默认路径逐字节兼容 v1.1.0。
- 完整单元/集成测试 + 真实 APK 反编译证据。

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

- **AGP 9.x** modern instrumentation (`AsmClassVisitorFactory` + `onVariants`), Gradle 9.6.
- **Built-in AES-128/CBC** (original removed AES in v3.0.0) alongside the default XOR+Base64.
- **Pluggable algorithms** via a single `IStringFog` contract (build-time encrypt & runtime decrypt share one impl).
- **Fine-grained config**: `fogPackages` include / `excludePackages` exclude / `minLength` threshold / global kill-switch.
- **Zero-dependency pure-Java runtime** (no kotlin-stdlib; POM has zero dependencies).
- **Backward compatible** with v1.1.0 byte-for-byte on the default path.

### Quick start

```kotlin
// settings.gradle.kts → add JitPack to pluginManagement & dependencyResolutionManagement
maven { url = uri("https://jitpack.io") }

// root build.gradle.kts
buildscript {
    dependencies {
        classpath("com.github.Pangu-Immortal.StringFogPro:stringfog-gradle-plugin:v2.0.0")
    }
}

// app/build.gradle.kts
apply(plugin = "com.va.stringfog")
dependencies {
    implementation("com.github.Pangu-Immortal.StringFogPro:stringfog:v2.0.0")
}
```

Run `./gradlew assembleRelease` — release strings are encrypted automatically; debug stays untouched.

See the Chinese sections above for the full feature matrix, real decompiled before/after evidence, configuration reference, algorithm extension guide, version support table, and an honest Roadmap of not-yet-implemented features.

Licensed under Apache-2.0. Original StringFog © MegatronKing.
