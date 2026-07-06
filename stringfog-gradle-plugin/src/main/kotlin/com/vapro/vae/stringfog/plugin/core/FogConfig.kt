package com.vapro.vae.stringfog.plugin.core

import com.vapro.vae.stringfog.IKeyGenerator
import com.vapro.vae.stringfog.IStringFog

/**
 * ===============================================================================
 * 功能：构建期字符串加密配置（核心 ASM 层与 AGP 层共用的不可变策略参数）。
 * 函数简介：承载「用哪个算法实现、用什么密钥、以何种插桩形态发射、边界阈值、映射输出」，
 *   由 AGP 工厂按 DSL 解析后构造，传给核心 ASM 访问器执行插桩。
 *
 * 关键字段：
 *   - fog          算法实现（构建期加密，与运行时同一 IStringFog 契约）。
 *   - keyBytes     固定密钥字节（keyGenerator==null 时使用；默认路径为 DEFAULT_KEY，
 *                  可配置路径为 DSL key 的 UTF-8 字节）。
 *   - keyLiteral   三参路径写入常量池的密钥字面量（legacy=true 或每串密钥模式下忽略）。
 *   - legacy       true=发射单参 decrypt（默认 XOR+DEFAULT_KEY，兼容 v1.1.0）；
 *                  false=发射三参 decrypt（AES/自定义/自定义密钥/每串密钥）。
 *   - algorithmId  三参路径写入常量池的算法 id（legacy=true 时忽略）。
 *   - minLength    最小加密串长阈值（短于此长度的字符串不加密）。
 *   - maxUtf8Bytes 最大加密串 UTF-8 字节阈值（超长串跳过，防常量池/方法体越限，见 shouldFog）。
 *   - bytesMode    true=密文以原始 byte[] 字面量发射（免 Base64）；false=Base64 文本发射。
 *   - keyGenerator 非 null=每串独立随机密钥（强制三参，逐串生成 keyLiteral）；null=固定密钥。
 *   - mappingWriter 非 null=构建期输出「明文→密文」映射（审计/排查）；null=不输出。
 * ===============================================================================
 */
data class FogConfig(
    val fog: IStringFog,
    val keyBytes: ByteArray,
    val legacy: Boolean,
    val keyLiteral: String?,
    val algorithmId: String,
    val minLength: Int,
    val maxUtf8Bytes: Int,
    val bytesMode: Boolean,
    val keyGenerator: IKeyGenerator?,
    val mappingWriter: MappingWriter?
) {
    // data class 含 ByteArray/引用型字段：显式覆写 equals/hashCode，规避数组引用比较告警与不一致。
    // 值语义字段参与相等；fog/keyGenerator/mappingWriter 按引用同一性比较（同配置解析出的同一实例）。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FogConfig) return false
        return legacy == other.legacy &&
            keyLiteral == other.keyLiteral &&
            algorithmId == other.algorithmId &&
            minLength == other.minLength &&
            maxUtf8Bytes == other.maxUtf8Bytes &&
            bytesMode == other.bytesMode &&
            keyBytes.contentEquals(other.keyBytes) &&
            fog === other.fog &&
            keyGenerator === other.keyGenerator &&
            mappingWriter === other.mappingWriter
    }

    override fun hashCode(): Int {
        var result = fog.hashCode()
        result = 31 * result + keyBytes.contentHashCode()
        result = 31 * result + legacy.hashCode()
        result = 31 * result + (keyLiteral?.hashCode() ?: 0)
        result = 31 * result + algorithmId.hashCode()
        result = 31 * result + minLength
        result = 31 * result + maxUtf8Bytes
        result = 31 * result + bytesMode.hashCode()
        result = 31 * result + (keyGenerator?.hashCode() ?: 0)
        result = 31 * result + (mappingWriter?.hashCode() ?: 0)
        return result
    }
}
