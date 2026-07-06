package com.vapro.agp8.sample;

/**
 * ===============================================================================
 * 功能：含明文字符串的演示类（验证 StringFog 在 AGP 8.x 下的 Java 插桩）。
 * 说明：敏感串以方法体字面量（LDC 指令）形式返回——release 构建期被 StringFog 插桩为
 *   「密文 + StringFogRuntime.decrypt(...)」；反编译 release DEX 将看不到该明文。
 *   刻意用方法返回值而非 static final 常量（后者会以 ConstantValue 属性内联，非 LDC）。
 * ===============================================================================
 */
public final class Secret {

    private Secret() {
    }

    /** 敏感令牌（明文，用于验证 release 加密 / debug 明文对照）。 */
    public static String token() {
        return "sk-AGP8-PLAINTEXT-verify-0987654321";
    }
}
