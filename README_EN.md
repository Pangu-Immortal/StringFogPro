<div align="center">

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:a8c0ff,100:3f2b96&height=150&section=header&text=StringFogPro&fontSize=40&fontColor=ffffff&animation=fadeIn&desc=Build-time%20Java/Kotlin%20string%20encryption%20for%20AGP%208.7%20%26%209.2&descSize=14&descAlignY=58" width="100%"/>

<img src="https://readme-typing-svg.demolab.com?font=Fira+Code&weight=600&size=18&pause=1000&color=6C7A96&center=true&vCenter=true&width=680&lines=Encrypt+Java+%26+Kotlin+string+literals+at+build+time;XOR+%2F+AES+%2F+custom+algorithm+%2B+per-string+random+key;Zero-dependency+pure-Java+runtime%2C+24%2F24+tests+passing" alt="typing" />

<br/>

<img src="https://count.getloli.com/get/@StringFogPro?theme=moebooru" alt="visits" />

<br/><br/>

![License](https://img.shields.io/badge/License-Apache%202.0-607D8B?style=flat-square)
![Release](https://img.shields.io/badge/Release-v2.1.0-5C6BC0?style=flat-square)
![AGP](https://img.shields.io/badge/AGP-8.7%20%7C%209.2-26A69A?style=flat-square)
![Gradle](https://img.shields.io/badge/Gradle-8.13%20%7C%209.6-78909C?style=flat-square)
![JDK](https://img.shields.io/badge/JDK-17%2B-8B7EC8?style=flat-square)
![Tests](https://img.shields.io/badge/tests-24%2F24_passing-26A69A?style=flat-square)
[![Stars](https://img.shields.io/github/stars/Pangu-Immortal/StringFogPro?style=flat-square&color=5C6BC0)](https://github.com/Pangu-Immortal/StringFogPro/stargazers)

**If it helps, a Star means a lot ⭐ · 如果对你有帮助,点个 Star 支持一下**

[简体中文](README.md) | English

</div>

---

## TL;DR

**StringFogPro** is a **Gradle string-encryption plugin** that automatically encrypts Java/Kotlin string literals in your APK/AAR at build time — replacing plaintext with `ciphertext + runtime-decrypt call`, so decompilers only see garbage and hardcoded endpoints, keys and tokens no longer sit in the clear. It is a **modernized superset** of the classic [StringFog](https://github.com/MegatronKing/StringFog) (Apache-2.0): modern instrumentation (`AsmClassVisitorFactory` + `onVariants`) with **AGP 8.7 & 9.2 both verified**, **built-in AES** (original removed it in v3.0.0), a choice of **XOR / AES / custom algorithms**, plus new **per-string random keys**, **bytes (no-Base64) mode**, and a **plaintext→ciphertext mapping file** — all with fine-grained package include/exclude and a min-length threshold. The runtime is a **zero-dependency pure-Java JAR**; one `./gradlew assembleRelease` does it all with zero business-code intrusion. **24/24 tests pass + real APK/AAR decompiled evidence + AGP 8.7/9.2 both really built.** Ideal for Android APK/AAR/library string obfuscation, key protection and anti-reverse hardening.

## Table of Contents

- [Core Features](#core-features)
- [Quick Start](#quick-start)
- [Configuration Reference](#configuration-reference)
- [Upgrades over Original StringFog](#upgrades-over-original-stringfog)
- [Real Decompiled Evidence](#real-decompiled-evidence)
- [Custom Algorithms](#custom-algorithms)
- [Adaptation Test Matrix](#adaptation-test-matrix)
- [Version Support](#version-support)
- [Project Structure](#project-structure)
- [Design Notes & Known Boundaries](#design-notes--known-boundaries)
- [Roadmap (honest)](#roadmap-honest)
- [Changelog](#changelog)
- [Credits & License](#credits--license)

## Core Features

| Feature | Description |
| --- | --- |
| **Java / Kotlin** | Works at the ASM bytecode layer, language-agnostic — both are encrypted identically (real decompiled proof included) |
| **APK + AAR/JAR** | Application and Library variants share one ASM transform core |
| **Three algorithms** | Built-in **XOR+Base64 (default)** and **AES-128/CBC**; register any custom `IStringFog` |
| **Per-string random keys** | Each string gets its own random key; identical plaintext yields different ciphertext, defeating statistical clustering |
| **Bytes mode (no Base64)** | Ciphertext emitted as a raw `byte[]` literal (d8 folds into `fill-array-data`), erasing the Base64 signature |
| **Mapping file** | Optional `class#method · algorithm · plaintext→ciphertext` audit map |
| **Fine-grained config** | Kill-switch + package allow/deny lists + `minLength` threshold + validation/conflict warnings |
| **Fully automated** | `onVariants` auto-registration, just apply the plugin; release-only, debug stays plaintext |
| **Zero-dependency runtime** | Pure-Java JAR, JDK-only, zero POM dependencies, no kotlin-stdlib |
| **Backward compatible** | Zero-config default path is byte-for-byte identical to v1.1.0 |

## Quick Start

StringFogPro publishes two coordinates on JitPack: the plugin `stringfog-gradle-plugin` and the runtime `stringfog`.

### Step 1 — Declare the JitPack repository

`settings.gradle.kts`:

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

### Step 2 — Add the plugin (recommended path A, most stable on JitPack)

Root `build.gradle.kts` with buildscript classpath:

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

> **Path B (declarative `plugins {}`)**: JitPack serves the module coordinate but not the plugin marker, so add a `resolutionStrategy` mapping in `pluginManagement`:
> ```kotlin
> pluginManagement {
>     resolutionStrategy {
>         eachPlugin {
>             if (requested.id.id == "com.va.stringfog") {
>                 useModule("com.github.Pangu-Immortal.StringFogPro:stringfog-gradle-plugin:${requested.version}")
>             }
>         }
>     }
> }
> ```
> Then `plugins { id("com.va.stringfog") version "v2.1.0" }` works (the module coordinate is verified resolvable).

### Step 3 — Apply the plugin + add the runtime + configure

`app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    // or with path A: apply(plugin = "com.va.stringfog")
}

dependencies {
    // runtime decryptor (zero-dependency pure-Java JAR)
    implementation("com.github.Pangu-Immortal.StringFogPro:stringfog:v2.1.0")
}

// optional — omit for zero-config default XOR (byte-for-byte compatible with v1.1.0)
stringfog {
    enabled.set(true)
    algorithm.set("xor")                 // "xor" | "aes" | fully-qualified IStringFog class name
    // key.set("your-release-key")       // unset → xor uses DEFAULT_KEY, aes uses built-in default
    minLength.set(1)
    // bytesMode.set(true)               // emit ciphertext as byte[], no Base64
    // randomKeyPerString.set(true)      // per-string random key
    // mappingEnabled.set(true)          // write mapping to build/outputs/stringfog/
    // fogPackages.add("com.your.app")            // only encrypt these packages (empty = all)
    // excludePackages.add("com.your.app.model")  // exclude these (higher priority)
}
```

**Done.** Run `./gradlew assembleRelease` — release strings are encrypted automatically; debug is untouched.

> Temporary global off: `./gradlew assembleRelease -Pva.stringfog.enabled=false`

## Configuration Reference

`stringfog { }` (`com.vapro.vae.stringfog.plugin.StringFogExtension`):

| Option | Type | Default | Description |
| --- | --- | --- | --- |
| `enabled` | `Boolean` | `true` | Master switch; also `-Pva.stringfog.enabled=false` globally |
| `algorithm` | `String` | `"xor"` | `xor` / `aes` / fully-qualified custom `IStringFog` |
| `key` | `String` | built-in | Key; unset → xor uses `DEFAULT_KEY` (v1.1.0 compat), aes uses built-in default |
| `fogPackages` | `List<String>` | `[]` | Only encrypt classes under these prefixes (empty = all) |
| `excludePackages` | `List<String>` | `[]` | Exclude these prefixes (higher priority than `fogPackages`) |
| `minLength` | `Int` | `1` | Min length to encrypt; shorter stays plaintext |
| `bytesMode` | `Boolean` | `false` | Emit ciphertext as a raw `byte[]` literal (no Base64) |
| `randomKeyPerString` | `Boolean` | `false` | Independent random key per string (anti-statistical) |
| `randomKeyLength` | `Int` | `16` | Random key length in chars (only when `randomKeyPerString`) |
| `mappingEnabled` | `Boolean` | `false` | Write mapping to `build/outputs/stringfog/stringfog-mapping-<variant>.txt` |

**Config robustness**: negative `minLength` / blank `algorithm` / non-positive `randomKeyLength` → **fail fast**; overlapping `fogPackages ∩ excludePackages`, or aes/custom without key → **warn, non-blocking**.

## Upgrades over Original StringFog

| Aspect | Original StringFog (5.x) | StringFogPro v2.1.0 | Real |
| --- | --- | --- | :---: |
| AGP baseline | AGP 8.x / Gradle 8 | **AGP 8.7 & 9.2 both verified** | ✅ |
| AES algorithm | **removed** since v3.0.0 | **brought back** (AES-128/CBC/PKCS5, random IV) | ✅ |
| Exclude packages | **removed** since v1.4.0 | **re-provided** `excludePackages` | ✅ |
| Min-length threshold | none | new `minLength` | ✅ |
| Per-string random key | ✅ | `RandomKeyGenerator` implemented | ✅ |
| Bytes (no Base64) | ✅ | byte[] literal / `fill-array-data` implemented | ✅ |
| Mapping file | ✅ | build-time plaintext→ciphertext implemented | ✅ |
| Config style | mostly Groovy DSL | typed **Kotlin DSL** + validation | ✅ |
| Runtime deps | needs algo lib | **zero-dependency pure-Java JAR** | ✅ |

> **Honest note**: the only real gap to the original is the friction-free declarative `plugins {}` resolution — that requires publishing the plugin marker to the Gradle Plugin Portal (needs an external publishing account, not available for this project). On JitPack, buildscript classpath or a `resolutionStrategy` mapping works (see [Quick Start](#quick-start)).
>
> On bytes-mode "size saving": for this repo's 6 short-string sample the whole-package DEX diff is only +8 bytes (negligible). Bytes mode's primary value is **erasing the Base64 text signature + skipping runtime Base64 decode**, not saving size on short strings.

## Real Decompiled Evidence

All from this repo's `sample/` module **v2.1.0 real build artifacts** via `dexdump` / `javap` (`build-tools 36.0.0`), not fabricated. Debug is not instrumented (release-only), so debug DEX = "before", release DEX = "after".

**① Java after (release DEX, default XOR, single-arg path)**

```smali
# JavaSecrets.apiToken() —— release DEX
const-string v0, "CVJjayoOcl0bDRMnFCUtay4UfxNYbAZGfHlrXjsTC1cfXw=="
invoke-static {v0}, Lcom/vapro/vae/stringfog/StringFogRuntime;.decrypt:(Ljava/lang/String;)Ljava/lang/String;
# plaintext "sk-JAVA-PLAINTEXT-..." count in release DEX = 0 (gone); debug = 1
```

**② AES mode (`-Psf.algo=aes -Psf.key=my-release-key-2026`, three-arg path)**

```smali
const-string v0, "my-release-key-2026"
const-string v1, "aes"
const-string v2, "DkySB3hkRyzW6kHRx6VGwjISWgTY+L+oaRDZ1qGGh7e/R0V9IJoX9Ny/xEOoRt5ik7jOjFJWwwOhz8hcEr+FJQ=="
invoke-static {v2, v0, v1}, L…/StringFogRuntime;.decrypt:(3×String)String
```

**③ Per-string random key (`-Psf.randomKey=true`)** — one random 16-char key per string (6 strings → 6 distinct keys):

```smali
const-string v0, "xni1fX647FTX3nn9"       # dedicated random key for this string
const-string v1, "xor"
const-string v2, "CwVEeycOdxlnChURfTorYSxDWANVbAMCAH5taFIMDV0dCA=="
invoke-static {v2, v0, v1}, L…/StringFogRuntime;.decrypt:(3×String)String
```

**④ Bytes mode (`-Psf.bytes=true`)** — no Base64 text, ciphertext stored as `fill-array-data`:

```smali
new-array v0, v0, [B
fill-array-data v0, 0000000c
invoke-static {v0}, L…/StringFogRuntime;.decrypt:([B)Ljava/lang/String;
```

**⑤ AAR library end-to-end (`sample/lib` → `classes.jar` → `javap -c`)** — library strings encrypted too:

```text
# LibJavaSecrets.libApiToken() —— AAR classes.jar
0: ldc           #35    // String CVJjbSIaHjoKFxNDCj0pejRtC3k/dVIROWwzDDkVDVVLC30VXm4=
2: invokestatic  #33    // Method .../StringFogRuntime.decrypt:(String)String
# 4 library plaintexts in classes.jar all grep count = 0
```

> **Reproduce**: root `./gradlew publishToMavenLocal` → `./gradlew -p sample :app:assembleDebug :app:assembleRelease :lib:assembleRelease` (add `-Psf.*` per mode) → compare with `dexdump -d` (APK) / `javap -c` (AAR).

## Custom Algorithms

Implement `IStringFog` (the single contract for build-time encrypt and runtime decrypt); name it via DSL at build time and register it under the same id at runtime.

```java
// 1) implement (visible to both buildscript classpath and the App)
package com.your.app.crypto;
import com.vapro.vae.stringfog.IStringFog;

public final class MyFog implements IStringFog {
    @Override public byte[] encrypt(byte[] data, byte[] key) { return transform(data, key); }
    @Override public byte[] decrypt(byte[] data, byte[] key) { return transform(data, key); }
    private byte[] transform(byte[] d, byte[] k) { /* ... */ return d; }
}
```

```kotlin
// 2) name it at build time (needs a public no-arg constructor, reflection-constructed)
stringfog { algorithm.set("com.your.app.crypto.MyFog") }
```

```java
// 3) register at runtime (early, e.g. Application.onCreate)
StringFogRuntime.register("com.your.app.crypto.MyFog", new com.your.app.crypto.MyFog());
```

Built-in `xor` / `aes` are registered by default — no manual register needed.

## Adaptation Test Matrix

> All **reproducible real evidence**: `./gradlew test` (24 unit/integration), real APK/AAR decompilation, AGP 8.7 & 9.2 both really built. **Claim = test; untested items are honestly flagged.**

**`./gradlew test` total: 24 / 24 pass (0 failures, 0 errors)** — `stringfog` 11 + `stringfog-gradle-plugin` 13.

| Aspect | Result | Evidence |
| --- | :---: | --- |
| **AGP 9.2.1 + Gradle 9.6 + JDK 21 + compileSdk 36** | ✅ verified | `sample/` real APK+AAR decompilation |
| **AGP 8.7.3 + Gradle 8.13 + JDK 21 + compileSdk 35** | ✅ verified | `sample-agp8/` real APK: release plaintext=0, debug=1 |
| Java @ APK / Kotlin @ APK | ✅ | `sample/app` release DEX plaintext count=0 |
| Java @ AAR / Kotlin @ AAR | ✅ | `sample/lib` `classes.jar` javap, plaintext=0 |
| XOR / AES / per-string key / bytes round-trip | ✅ | `StringFogRuntimeTest` + `StringFogTransformTest` |
| v1.1.0 byte-for-byte backward compat | ✅ | `backwardCompatWithV110` |
| AGP 7.2 ~ 8.6 | ⚠️ not per-version tested | API stable since AGP 7.2, theoretically compatible |
| JDK 17 (lower bound) | ⚠️ not tested | artifact target=17, this env builds with JDK 21 |

## Version Support

| Component | Version | Notes |
| --- | --- | --- |
| AGP | **8.7.3 & 9.2.1 (both verified)** | `AsmClassVisitorFactory` (AGP 7.2+); 7.2~8.6 theoretical, not per-version tested |
| Gradle | **8.13 & 9.6.0** | main build locks 9.6.0; `sample-agp8` locks 8.13 |
| Kotlin | embedded via Gradle `kotlin-dsl` | plugin source is 100% Kotlin |
| JDK | build/test **JDK 21**; artifact bytecode **Java 17 (lower bound)** | lower bytecode is forward-compatible |
| ASM | **9.9** | provided by AGP |
| minSdk | **26+** | runtime uses `java.util.Base64` (API 26+); sample uses 28 |

## Project Structure

```
StringFogPro/
├── stringfog/                     # runtime + algorithms (pure Java, zero deps, published on JitPack)
│   └── .../stringfog/
│       ├── IStringFog.java        # unified encrypt/decrypt contract
│       ├── IKeyGenerator.java     # per-string key generator contract
│       ├── RandomKeyGenerator.java# random per-string key impl
│       ├── XorFog.java            # default cyclic XOR (v1.1.0 compatible)
│       ├── AesFog.java            # AES-128/CBC/PKCS5 (random IV prefixed)
│       └── StringFogRuntime.java  # runtime decrypt dispatcher (4 entries + registry)
├── stringfog-gradle-plugin/       # AGP instrumentation plugin (100% Kotlin, published on JitPack)
│   └── .../plugin/
│       ├── StringFogPlugin.kt     # onVariants release registration + config validation
│       ├── StringFogExtension.kt  # stringfog { } DSL
│       ├── StringFogFactory.kt    # AsmClassVisitorFactory
│       └── core/                  # pure ASM transform core (independently unit-testable)
│           ├── FogConfigResolver.kt
│           ├── PackageFilter.kt
│           ├── MappingWriter.kt
│           └── StringFogMethodVisitor.kt
├── sample/                        # demo multi-module (:app APK + :lib AAR)
└── sample-agp8/                   # second AGP tier (AGP 8.7.3 + Gradle 8.13)
```

## Design Notes & Known Boundaries

- **Why is the runtime Java, not Kotlin?** The runtime JAR ships inside every consumer APK; the goal is **zero-dependency and tiny**. Kotlin would force `kotlin-stdlib` (~1.5MB+), breaking the zero-dependency stance. Encryption happens at the ASM bytecode layer, so Kotlin business classes are encrypted just the same.
- **The key is not a cryptographic secret.** Keys (including per-string random keys) are embedded in the APK with the bytecode. The goal is to **defeat `strings`/grep plaintext scanning and raise the reversing cost**, not to provide unbreakable encryption.
- **Compile-time constants (`const val` / `static final String`) are not encrypted.** They inline as a `ConstantValue` attribute, not a method-body `LDC`; use method return values or non-const fields for sensitive strings.
- **Non-constant concatenation is safe.** Only `LDC` String constants are rewritten; Java 9+ `invokedynamic makeConcatWithConstants` constants live in BSM args, not LDC, and are untouched.
- **Oversized strings are skipped.** Beyond the threshold (Base64 45000 / bytes 8000 UTF-8 bytes) stays plaintext, avoiding the 65535 constant-pool limit — far beyond any real key/URL length.
- **Scope `PROJECT`.** Only this module's source is encrypted, not dependencies (avoids double-encryption / encrypting AndroidX).

## Roadmap (honest)

- [ ] **Friction-free declarative `plugins {}` (Gradle Plugin Portal marker)** — needs an external publishing account, not available; currently via buildscript classpath or `resolutionStrategy` mapping.
- [ ] **AGP 7.2 ~ 8.6 per-version testing** — API-level compatible, 8.7.3 & 9.2.1 verified.
- [ ] **JDK 17 lower-bound testing** — artifact targets Java 17, this env builds/tests with JDK 21.
- [ ] **R8/obfuscation coexistence** — sample disables R8 to focus on instrumentation; instrumentation runs before R8, no conflict in theory.

> Already delivered in v2.1.0 from the old Roadmap: per-string random keys, bytes (no-Base64) mode, mapping file, AAR library end-to-end decompiled evidence — all with implementation locations and test/decompiled evidence.

## Changelog

### v2.1.0 (production polish)
- **New**: per-string random keys, bytes (no-Base64) mode, plaintext→ciphertext mapping file, AAR library end-to-end decompiled evidence.
- **Four runtime entries**: Base64/bytes × single/three-arg `decrypt` overloads.
- **Refinements**: `shouldFog` as interface default; ASM boundary guards; static `SecureRandom` in `AesFog`; testable `PackageFilter`; thread-safety audit.
- **Config robustness**: fail-fast validation; overlap warnings.
- **Matrix**: 24/24 pass; AGP 8.7.3+Gradle 8.13 and AGP 9.2.1+Gradle 9.6 both built; Java/Kotlin × APK/AAR real decompilation.

### v2.0.0 (superset upgrade)
- Multi-module; unified `IStringFog` contract; built-in AES-128/CBC; pluggable algorithms; fine-grained config.

### v1.1.0
- Single-module pure-Java runtime: `StringFogRuntime.decrypt(String)` (XOR + Base64).

## Credits & License

This project reworks and modernizes the ideas of [MegatronKing/StringFog](https://github.com/MegatronKing/StringFog) (Apache-2.0) into a superset. Thanks to the original author.

Licensed under **Apache-2.0** — **commercial use allowed**. See [LICENSE](LICENSE).

---

<div align="center">

### Star History

<a href="https://star-history.com/#Pangu-Immortal/StringFogPro&Date"><img src="https://img.shields.io/badge/⭐_Star_History-View_Live_Chart-5C6BC0?style=for-the-badge" alt="Star History"/></a>

<br/><br/>

**About the Author**

Primary focus: large-model algorithms / AI / on-device (Agentic · LangGraph · A2A · MCP · ADK · GraphRAG · on-device offline multimodal · automotive · world models). ROM / reverse engineering is a technical hobby, not a side business.

Open to collaboration · 📮 **yugu88@126.com** · GitHub [@Pangu-Immortal](https://github.com/Pangu-Immortal)

<br/>

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:3f2b96,100:a8c0ff&height=100&section=footer" width="100%"/>

</div>
