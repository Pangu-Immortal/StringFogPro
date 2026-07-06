package com.vapro.vae.stringfog.sample

/**
 * ===============================================================================
 * 功能：Kotlin 侧含明文字符串的演示类（证明 StringFog 支持 kotlin）。
 * 说明：StringFog 工作在 ASM 字节码层，与源语言无关——Kotlin 编译出的 class 与 Java 一样被插桩。
 *   所有敏感串以函数体字面量（LDC）形式存在，release 构建期被加密替换。
 *   （注：Kotlin const val 会被内联为 ConstantValue，非 LDC；故此处用函数返回值确保命中 LDC 插桩。）
 * ===============================================================================
 */
object KotlinSecrets {

    /** 后端基址（明文）。 */
    fun apiBaseUrl(): String = "https://api.stringfogpro-demo.example.com/v1/kotlin"

    /** 访问令牌（明文）。 */
    fun apiToken(): String = "sk-KOTLIN-PLAINTEXT-abcdef0123456789"

    /** 欢迎语（明文，含中文）。 */
    fun welcome(): String = "Kotlin 明文欢迎语_StringFogPro_你好"
}
