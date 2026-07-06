package com.vapro.vae.stringfog.plugin

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * ===============================================================================
 * 功能：StringFog 插件 DSL 扩展（细粒度可选加密配置）。
 * 函数简介：在 build.gradle.kts 中以 stringfog { } 块配置加密行为，插件据此解析构建期插桩参数。
 *
 * 配置项：
 *   - enabled            总开关（默认 true）；亦可用 -Pva.stringfog.enabled=false 全局关闭。
 *   - algorithm          算法：xor(默认)/aes/自定义 IStringFog 全限定类名。
 *   - key                密钥字符串；未设时 xor 走 DEFAULT_KEY（兼容 v1.1.0），aes 走内置默认密钥。
 *   - fogPackages        仅加密这些包前缀下的类（为空=全部加密）。
 *   - excludePackages    排除这些包前缀下的类（优先级高于 fogPackages）。
 *   - minLength          最小加密串长阈值（短于此长度的字符串不加密，默认 1）。
 *   - bytesMode          true=密文以原始 byte[] 字面量发射（免 Base64，改变静态特征），默认 false。
 *   - randomKeyPerString true=每个字符串独立随机密钥（相同明文各处密文不同，抗统计分析），默认 false。
 *   - randomKeyLength    每串随机密钥长度（字符数，仅 randomKeyPerString 生效），默认 16。
 *   - mappingEnabled     true=构建期输出「明文→密文」映射文件（审计/排查），默认 false。
 * ===============================================================================
 */
abstract class StringFogExtension {

    /** 总开关：默认 true。 */
    abstract val enabled: Property<Boolean>

    /** 算法 id：xor / aes / 自定义 IStringFog 全限定类名。默认 xor。 */
    abstract val algorithm: Property<String>

    /** 密钥字符串：未设置时按算法回落到默认密钥。 */
    abstract val key: Property<String>

    /** 仅加密的包前缀白名单（为空=全部）。 */
    abstract val fogPackages: ListProperty<String>

    /** 排除加密的包前缀黑名单（优先于白名单）。 */
    abstract val excludePackages: ListProperty<String>

    /** 最小加密串长阈值：默认 1（即空串外一律加密）。 */
    abstract val minLength: Property<Int>

    /** bytes 模式：密文以原始 byte[] 字面量发射（免 Base64）。默认 false。 */
    abstract val bytesMode: Property<Boolean>

    /** 每串随机密钥：每个字符串独立随机密钥（强制三参路径）。默认 false。 */
    abstract val randomKeyPerString: Property<Boolean>

    /** 每串随机密钥长度（字符数）：默认 16。 */
    abstract val randomKeyLength: Property<Int>

    /** 映射输出：构建期输出「明文→密文」映射文件到 build/outputs/stringfog/。默认 false。 */
    abstract val mappingEnabled: Property<Boolean>
}
