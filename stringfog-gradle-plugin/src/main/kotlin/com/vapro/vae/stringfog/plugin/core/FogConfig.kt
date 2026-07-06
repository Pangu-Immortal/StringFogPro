package com.vapro.vae.stringfog.plugin.core

import com.vapro.vae.stringfog.IStringFog

/**
 * ===============================================================================
 * 功能：构建期字符串加密配置（核心 ASM 层与 AGP 层共用的不可变参数）。
 * 函数简介：承载「用哪个算法实现、用什么密钥、最小串长阈值、以何种插桩形态发射」，
 *   由 AGP 工厂按 DSL 解析后构造，传给核心 ASM 访问器执行插桩。
 *
 * 关键字段：
 *   - fog        算法实现（构建期加密，与运行时同一 IStringFog 契约）。
 *   - keyBytes   加密密钥字节（默认路径为 DEFAULT_KEY，可配置路径为 DSL key 的 UTF-8 字节）。
 *   - legacy     true=发射单参 decrypt(String)（默认 XOR+DEFAULT_KEY，栈中性，兼容 v1.1.0）；
 *                false=发射三参 decrypt(String,key,algorithm)（AES/自定义/自定义密钥）。
 *   - keyLiteral 三参路径写入常量池的密钥字面量（legacy=true 时为 null）。
 *   - algorithmId 三参路径写入常量池的算法 id（legacy=true 时忽略）。
 *   - minLength  最小加密串长阈值（短于此长度的字符串不加密）。
 * ===============================================================================
 */
data class FogConfig(
    val fog: IStringFog,
    val keyBytes: ByteArray,
    val legacy: Boolean,
    val keyLiteral: String?,
    val algorithmId: String,
    val minLength: Int
) {
    // data class 含 ByteArray 字段：显式覆写 equals/hashCode，规避数组引用比较告警与不一致。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FogConfig) return false
        return legacy == other.legacy &&
            keyLiteral == other.keyLiteral &&
            algorithmId == other.algorithmId &&
            minLength == other.minLength &&
            keyBytes.contentEquals(other.keyBytes) &&
            fog === other.fog
    }

    override fun hashCode(): Int {
        var result = fog.hashCode()
        result = 31 * result + keyBytes.contentHashCode()
        result = 31 * result + legacy.hashCode()
        result = 31 * result + (keyLiteral?.hashCode() ?: 0)
        result = 31 * result + algorithmId.hashCode()
        result = 31 * result + minLength
        return result
    }
}
