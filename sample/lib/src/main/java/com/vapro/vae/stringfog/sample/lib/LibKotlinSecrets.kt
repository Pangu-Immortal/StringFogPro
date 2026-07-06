package com.vapro.vae.stringfog.sample.lib

/**
 * ===============================================================================
 * 功能：库侧（AAR）含明文字符串的 Kotlin 演示类（证明库 Kotlin 类同样被加密）。
 * 说明：StringFog 工作在 ASM 字节码层，与源语言无关——库 Kotlin 编译出的 class 与 Java 一样被插桩。
 *   敏感串以函数体字面量（LDC）形式存在，release 构建期被加密替换。
 * ===============================================================================
 */
object LibKotlinSecrets {

    /** 库内后端基址（明文）。 */
    fun libApiBaseUrl(): String = "https://lib.stringfogpro-demo.example.com/aar/kotlin"

    /** 库内访问令牌（明文）。 */
    fun libApiToken(): String = "sk-LIB-KOTLIN-PLAINTEXT-aar-987654321fedcba"

    /** 库内欢迎语（明文，含中文）。 */
    fun libWelcome(): String = "库 Kotlin 明文欢迎语_StringFogPro_AAR_你好"
}
