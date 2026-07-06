package com.vapro.vae.stringfog.sample;

/**
 * ===============================================================================
 * 功能：Java 侧含明文字符串的演示类（证明 StringFog 支持 java）。
 * 说明：所有敏感串以方法体字面量（LDC 指令）形式存在——release 构建期被 StringFog 插桩为
 *   「密文 + StringFogRuntime.decrypt(...)」；反编译 release DEX 将看不到这些明文。
 *   （注：Java static final String 常量会以 ConstantValue 属性内联，非 LDC，故此处统一用方法返回值。）
 * ===============================================================================
 */
public final class JavaSecrets {

    private JavaSecrets() {
    }

    /** 后端基址（明文）。 */
    public static String apiBaseUrl() {
        return "https://api.stringfogpro-demo.example.com/v1/java";
    }

    /** 访问令牌（明文）。 */
    public static String apiToken() {
        return "sk-JAVA-PLAINTEXT-1234567890abcdef";
    }

    /** 数据库口令（明文，含中文）。 */
    public static String dbPassword() {
        return "Java_超级机密_DB_Pass!";
    }
}
