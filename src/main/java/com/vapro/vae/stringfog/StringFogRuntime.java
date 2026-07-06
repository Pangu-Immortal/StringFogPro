package com.vapro.vae.stringfog;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * ===============================================================================
 * 功能：StringFog 运行时字符串解密器。
 * 函数简介：提供静态 decrypt(String)，将 release 构建期由 AGP AsmClassVisitorFactory
 *   加密的字符串字面量（XOR + Base64）还原为原始字符串。
 *
 * 关键约束（修改本类前必读）：
 *   1. 本类被 StringFogFactory.isInstrumentable 显式排除，禁止被插桩加密；
 *      否则 decrypt 内部字符串再次被加密 → 运行时 decrypt 调 decrypt 死循环。
 *   2. KEY 必须与 app/build.gradle.kts、lib/build.gradle.kts 内 StringFogMethodVisitor
 *      的 companion KEY 完全一致（三处同步，改一处必须改三处）。
 *   3. 仅依赖 JDK（java.util.Base64 + StandardCharsets），不依赖 Android 框架 API，
 *      保证类加载顺序无关——即便极早期 mirror/reflection 路径触发解密也能正常工作。
 *   4. minSdk=28 ≥ 26，java.util.Base64 在全版本可用。
 * ===============================================================================
 */
public final class StringFogRuntime {

    /** 私有构造：纯静态工具类，禁止实例化。 */
    private StringFogRuntime() {
    }

    /**
     * XOR 解密密钥（16 字节）。
     * 必须与 app/build.gradle.kts、lib/build.gradle.kts 中 StringFogMethodVisitor.KEY 保持完全一致。
     */
    private static final byte[] KEY = {
        0x7A, 0x39, 0x4E, 0x21, 0x6B, 0x58, 0x33, 0x70,
        0x4B, 0x41, 0x52, 0x6E, 0x5A, 0x71, 0x68, 0x33
    };

    /**
     * 解密 release 构建期加密的字符串。
     * <p>关键逻辑：Base64 解码 → 与 KEY 循环 XOR → 按 UTF-8 构造原始字符串。</p>
     *
     * @param encrypted Base64 编码的 XOR 加密串（由构建期 StringFogMethodVisitor 写入常量池）
     * @return 原始字符串；入参为 null/空或解密异常时原样返回，保证单条字符串失败不致崩溃
     */
    public static String decrypt(String encrypted) {
        // 空串/null 直接返回：构建期对空串不加密，运行时也不会收到空密文
        if (encrypted == null || encrypted.isEmpty()) {
            return encrypted;
        }
        try {
            // 关键逻辑：Base64 解码得到 XOR 后的字节流
            byte[] enc = Base64.getDecoder().decode(encrypted);
            byte[] out = new byte[enc.length];
            int klen = KEY.length;
            // 关键逻辑：循环 XOR 还原原始字节
            for (int i = 0; i < enc.length; i++) {
                out[i] = (byte) (enc[i] ^ KEY[i % klen]);
            }
            // 关键逻辑：按 UTF-8 还原字符串（与构建期 toByteArray(UTF_8) 对称）
            return new String(out, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // 兜底：解密失败返回密文，避免单条字符串异常导致整个类初始化/方法调用崩溃
            return encrypted;
        }
    }
}
