package com.vapro.vae.stringfog.sample.lib;

/**
 * ===============================================================================
 * 功能：库侧（AAR）含明文字符串的 Java 演示类（证明 Library 变体同样被加密）。
 * 说明：敏感串以方法体字面量（LDC）形式存在——release 构建期被 StringFog 插桩为
 *   「密文 + StringFogRuntime.decrypt(...)」；反编译 AAR 的 classes.jar（javap -c）看不到明文。
 * ===============================================================================
 */
public final class LibJavaSecrets {

    private LibJavaSecrets() {
    }

    /** 库内后端基址（明文）。 */
    public static String libApiBaseUrl() {
        return "https://lib.stringfogpro-demo.example.com/aar/java";
    }

    /** 库内访问令牌（明文）。 */
    public static String libApiToken() {
        return "sk-LIB-JAVA-PLAINTEXT-aar-abcdef123456";
    }

    /** 库内密钥（明文，含中文）。 */
    public static String libSecret() {
        return "库_AAR_超级机密_Secret!";
    }
}
