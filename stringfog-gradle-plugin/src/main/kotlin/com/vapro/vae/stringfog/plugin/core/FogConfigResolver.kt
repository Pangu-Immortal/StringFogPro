package com.vapro.vae.stringfog.plugin.core

import com.vapro.vae.stringfog.AesFog
import com.vapro.vae.stringfog.IStringFog
import com.vapro.vae.stringfog.StringFogRuntime
import com.vapro.vae.stringfog.XorFog

/**
 * ===============================================================================
 * 功能：DSL 配置 → 构建期 FogConfig 解析器（算法/密钥/发射形态的唯一裁决处）。
 * 函数简介：把 DSL 的 algorithm/key/minLength 解析为核心 ASM 层可直接使用的 FogConfig，
 *   决定用哪个 IStringFog 实现、加密密钥字节、以及发射单参(legacy)还是三参插桩。
 *
 * 裁决规则：
 *   1. algorithm=xor 且未设自定义 key → legacy 单参路径 + DEFAULT_KEY（逐字节兼容 v1.1.0）。
 *   2. algorithm=xor 且设了自定义 key → 三参路径 + key.UTF8 字节。
 *   3. algorithm=aes → 三参路径 + AesFog；未设 key 时用内置默认 AES 密钥。
 *   4. algorithm=其它（全限定类名）→ 反射构造自定义 IStringFog，三参路径，algorithmId=该类名；
 *      运行时须以同一类名 register 对应实现。
 * ===============================================================================
 */
object FogConfigResolver {

    /** 默认算法 id（DSL 缺省值）。 */
    const val DEFAULT_ALGORITHM: String = "xor"

    /** 密钥哨兵：DSL 未显式设置 key 时的占位值，触发「默认密钥 + legacy」路径。 */
    const val DEFAULT_KEY_SENTINEL: String = "__STRINGFOG_DEFAULT_KEY__"

    /** aes 未指定 key 时的内置默认密钥（非机密，仅提供开箱即用体验）。 */
    private const val BUILTIN_AES_KEY: String = "stringfog-pro-aes-default-key"

    /** 自定义算法未指定 key 时的内置默认密钥。 */
    private const val BUILTIN_CUSTOM_KEY: String = "stringfog-pro-custom-default-key"

    /**
     * 解析 DSL → FogConfig。
     *
     * @param algorithm DSL algorithm（xor/aes/自定义全限定类名）
     * @param key       DSL key；等于哨兵或空串视为「未设置」
     * @param minLength DSL 最小加密串长阈值
     * @param classLoader 反射加载自定义算法实现所用类加载器（构建期插件类加载器）
     * @return 供核心 ASM 层使用的不可变 FogConfig
     */
    fun resolve(
        algorithm: String,
        key: String,
        minLength: Int,
        classLoader: ClassLoader
    ): FogConfig {
        val userSetKey = key != DEFAULT_KEY_SENTINEL && key.isNotEmpty()
        return when (algorithm) {
            "xor" -> if (!userSetKey) {
                // 规则1：默认 XOR + DEFAULT_KEY → legacy 单参，逐字节兼容 v1.1.0
                FogConfig(
                    fog = XorFog(),
                    keyBytes = StringFogRuntime.defaultKey(),
                    legacy = true,
                    keyLiteral = null,
                    algorithmId = "xor",
                    minLength = minLength
                )
            } else {
                // 规则2：自定义密钥 XOR → 三参
                FogConfig(
                    fog = XorFog(),
                    keyBytes = key.toByteArray(Charsets.UTF_8),
                    legacy = false,
                    keyLiteral = key,
                    algorithmId = "xor",
                    minLength = minLength
                )
            }

            "aes" -> {
                // 规则3：AES → 三参；未设 key 用内置默认 AES 密钥
                val effectiveKey = if (userSetKey) key else BUILTIN_AES_KEY
                FogConfig(
                    fog = AesFog(),
                    keyBytes = effectiveKey.toByteArray(Charsets.UTF_8),
                    legacy = false,
                    keyLiteral = effectiveKey,
                    algorithmId = "aes",
                    minLength = minLength
                )
            }

            else -> {
                // 规则4：自定义算法（全限定类名）→ 反射构造，三参，algorithmId=类名
                val impl = loadCustom(algorithm, classLoader)
                val effectiveKey = if (userSetKey) key else BUILTIN_CUSTOM_KEY
                FogConfig(
                    fog = impl,
                    keyBytes = effectiveKey.toByteArray(Charsets.UTF_8),
                    legacy = false,
                    keyLiteral = effectiveKey,
                    algorithmId = algorithm,
                    minLength = minLength
                )
            }
        }
    }

    /**
     * 反射构造自定义 IStringFog 实现（要求 public 无参构造）。
     * <p>关键逻辑：失败时抛出带类名的清晰异常，指引用户把实现类加入 buildscript classpath。</p>
     */
    private fun loadCustom(fqcn: String, classLoader: ClassLoader): IStringFog {
        try {
            val clazz = Class.forName(fqcn, true, classLoader)
            val instance = clazz.getDeclaredConstructor().newInstance()
            return instance as? IStringFog
                ?: throw IllegalArgumentException("StringFog: algorithm=$fqcn 未实现 IStringFog 接口")
        } catch (e: ClassNotFoundException) {
            throw IllegalArgumentException(
                "StringFog: 找不到自定义算法实现 $fqcn，请确认已加入 buildscript classpath", e
            )
        } catch (e: ReflectiveOperationException) {
            throw IllegalArgumentException(
                "StringFog: 自定义算法 $fqcn 构造失败，需提供 public 无参构造函数", e
            )
        }
    }
}
