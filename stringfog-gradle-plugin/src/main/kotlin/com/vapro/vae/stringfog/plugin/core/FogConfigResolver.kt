package com.vapro.vae.stringfog.plugin.core

import com.vapro.vae.stringfog.AesFog
import com.vapro.vae.stringfog.IKeyGenerator
import com.vapro.vae.stringfog.IStringFog
import com.vapro.vae.stringfog.RandomKeyGenerator
import com.vapro.vae.stringfog.StringFogRuntime
import com.vapro.vae.stringfog.XorFog

/**
 * ===============================================================================
 * 功能：DSL 配置 → 构建期 FogConfig 解析器（算法/密钥/发射形态的唯一裁决处）。
 * 函数简介：把 DSL 的 algorithm/key/minLength/bytesMode/每串密钥 解析为核心 ASM 层可直接使用的
 *   FogConfig，决定用哪个 IStringFog 实现、加密密钥、发射单参(legacy)还是三参、Base64 还是 bytes。
 *
 * 裁决规则：
 *   1. algorithm=xor 且未设自定义 key 且未开每串密钥 → legacy 单参 + DEFAULT_KEY（逐字节兼容 v1.1.0）。
 *   2. algorithm=xor 且设了自定义 key → 三参 + key.UTF8。
 *   3. algorithm=aes → 三参 + AesFog；未设 key 时用内置默认 AES 密钥。
 *   4. algorithm=其它（全限定类名）→ 反射构造自定义 IStringFog，三参，algorithmId=该类名；
 *      运行时须以同一类名 register 对应实现。
 *   5. 每串随机密钥（randomKeyPerString=true）→ 任何算法均强制三参，逐串由 RandomKeyGenerator 产密钥，
 *      固定 key 被忽略（相同明文各处密文不同，抗统计分析）。
 *   6. bytesMode 与上述正交——只改传输编码（Base64 文本 ↔ 原始 byte[]），不改算法/密钥裁决。
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
     * Base64 文本模式下最大加密串 UTF-8 字节阈值。
     * 依据：class 文件 CONSTANT_Utf8 上限 65535 字节；Base64 膨胀约 4/3，取 45000 使密文 Base64
     * 稳落在上限内（45000*4/3 ≈ 60000 < 65535），超此长度的串跳过加密（保持明文），防生成非法 class。
     */
    private const val MAX_UTF8_BASE64_MODE: Int = 45000

    /**
     * bytes 模式下最大加密串 UTF-8 字节阈值。
     * 依据：byte[] 字面量由逐字节 BASTORE 序列构造（每字节约 6 字节字节码），单方法体上限 65535 字节；
     * 取 8000 使 byte[] 构造字节码稳落在上限内（8000*6 ≈ 48000 < 65535），远超任何真实密钥/URL 长度。
     */
    private const val MAX_UTF8_BYTES_MODE: Int = 8000

    /**
     * 解析 DSL → FogConfig。
     *
     * @param algorithm          DSL algorithm（xor/aes/自定义全限定类名）
     * @param key                DSL key；等于哨兵或空串视为「未设置」
     * @param minLength          DSL 最小加密串长阈值（必须 >= 0）
     * @param bytesMode          true=密文以原始 byte[] 发射（免 Base64）
     * @param randomKeyPerString true=每串独立随机密钥（强制三参）
     * @param randomKeyLength    每串随机密钥长度（字符数，仅 randomKeyPerString 时有效）
     * @param mappingWriter      非 null=输出「明文→密文」映射；null=不输出
     * @param classLoader        反射加载自定义算法实现所用类加载器（构建期插件类加载器）
     * @return 供核心 ASM 层使用的不可变 FogConfig
     */
    fun resolve(
        algorithm: String,
        key: String,
        minLength: Int,
        bytesMode: Boolean,
        randomKeyPerString: Boolean,
        randomKeyLength: Int,
        mappingWriter: MappingWriter?,
        classLoader: ClassLoader
    ): FogConfig {
        // 结构校验：minLength 不得为负（DSL 层已校验，此处二次防御）
        require(minLength >= 0) { "StringFog: minLength 不得为负，当前=$minLength" }

        val userSetKey = key != DEFAULT_KEY_SENTINEL && key.isNotEmpty()
        val maxUtf8 = if (bytesMode) MAX_UTF8_BYTES_MODE else MAX_UTF8_BASE64_MODE

        // 选算法实现 + id + 该算法「未设 key 时」的内置默认密钥（xor 走 DEFAULT_KEY 字节，无字符串默认）
        val fog: IStringFog
        val algorithmId: String
        val builtinKey: String?
        when (algorithm) {
            "xor" -> {
                fog = XorFog(); algorithmId = "xor"; builtinKey = null
            }
            "aes" -> {
                fog = AesFog(); algorithmId = "aes"; builtinKey = BUILTIN_AES_KEY
            }
            else -> {
                fog = loadCustom(algorithm, classLoader); algorithmId = algorithm; builtinKey = BUILTIN_CUSTOM_KEY
            }
        }

        // 每串密钥生成器（非 null 即强制三参）
        val keyGenerator: IKeyGenerator? = if (randomKeyPerString) RandomKeyGenerator(randomKeyLength) else null

        // legacy 单参：仅当 xor + 未设 key + 未开每串密钥（唯一逐字节兼容 v1.1.0 的路径）
        val legacy = algorithm == "xor" && !userSetKey && !randomKeyPerString

        // 固定密钥字面量（三参路径写入常量池；legacy / 每串密钥模式为 null）
        val keyLiteral: String? = when {
            legacy -> null
            randomKeyPerString -> null
            userSetKey -> key
            else -> builtinKey // aes/custom 未设 key → 内置默认（此分支 builtinKey 必非 null）
        }

        // 固定密钥字节（keyGenerator==null 时用；legacy 用 DEFAULT_KEY，每串密钥模式忽略）
        val keyBytes: ByteArray = when {
            legacy -> StringFogRuntime.defaultKey()
            randomKeyPerString -> ByteArray(0) // 逐串生成，占位不被读取
            else -> (keyLiteral ?: "").toByteArray(Charsets.UTF_8)
        }

        return FogConfig(
            fog = fog,
            keyBytes = keyBytes,
            legacy = legacy,
            keyLiteral = keyLiteral,
            algorithmId = algorithmId,
            minLength = minLength,
            maxUtf8Bytes = maxUtf8,
            bytesMode = bytesMode,
            keyGenerator = keyGenerator,
            mappingWriter = mappingWriter
        )
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
