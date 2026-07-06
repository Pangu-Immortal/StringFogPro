package com.vapro.vae.stringfog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ===============================================================================
 * 功能：StringFogPro 运行时 + 算法单元测试。
 * 覆盖：
 *   1. XorFog / AesFog 字节级往返对称（含边界：空/超长/Unicode/emoji/含 Base64 字符）。
 *   2. StringFogRuntime 四条入口往返：Base64 单参、Base64 三参、bytes 单参、bytes 三参。
 *   3. v1.1.0 向后兼容——DEFAULT_KEY 的 XOR+Base64 密文能被单参 decrypt 还原。
 *   4. 自定义算法运行时注册后可解密。
 *   5. RandomKeyGenerator 每串随机密钥：长度/字符集/差异性/往返。
 *   6. IStringFog.shouldFog 默认实现。
 * ===============================================================================
 */
class StringFogRuntimeTest {

    /** 复刻 v1.1.0 逐字节加密逻辑（out[i] = data[i] ^ KEY[i%16] → Base64），用于交叉验证兼容性。 */
    private static final byte[] V110_KEY = {
        0x7A, 0x39, 0x4E, 0x21, 0x6B, 0x58, 0x33, 0x70,
        0x4B, 0x41, 0x52, 0x6E, 0x5A, 0x71, 0x68, 0x33
    };

    /** 边界字符串集合：空/超长/纯中文/emoji/含 Base64 字符/混合。 */
    private static final String[] BOUNDARY_STRINGS = {
        "a",
        "ab",
        "https://api.example.com/v1/secret?token=abc123",
        "分身定位与设备指纹的中文明文串",
        "emoji 混合 😀🔐🚀 与 ASCII mix",
        "Base64ish/+=chars AAAA1234==zz//++",
        "包含\n换行\t制表\r回车的多行串",
        repeat("超长串", 4000) // ~ 12000 UTF-8 字节，验证长串（未触发插件层 maxUtf8 阈值，运行时算法无长度限制）
    };

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static String v110Encrypt(String plain) {
        byte[] raw = plain.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = (byte) ((raw[i] & 0xFF) ^ (V110_KEY[i % V110_KEY.length] & 0xFF));
        }
        return Base64.getEncoder().encodeToString(out);
    }

    // ============================== 算法字节级往返 ==============================

    @Test
    @DisplayName("XorFog 字节级往返对称（含边界字符串）")
    void xorRoundTripBoundary() {
        XorFog fog = new XorFog();
        byte[] key = "any-length-key-字节".getBytes(StandardCharsets.UTF_8);
        for (String s : BOUNDARY_STRINGS) {
            byte[] data = s.getBytes(StandardCharsets.UTF_8);
            byte[] enc = fog.encrypt(data, key);
            assertArrayEquals(data, fog.decrypt(enc, key), "XOR 往返必须字节相等：" + preview(s));
        }
    }

    @Test
    @DisplayName("AesFog 字节级往返对称 + 随机 IV 使密文每次不同（含边界字符串）")
    void aesRoundTripBoundary() {
        AesFog fog = new AesFog();
        byte[] key = "my-secret-中文".getBytes(StandardCharsets.UTF_8);
        for (String s : BOUNDARY_STRINGS) {
            byte[] data = s.getBytes(StandardCharsets.UTF_8);
            byte[] enc1 = fog.encrypt(data, key);
            byte[] enc2 = fog.encrypt(data, key);
            assertArrayEquals(data, fog.decrypt(enc1, key), "AES 往返必须字节相等：" + preview(s));
            assertArrayEquals(data, fog.decrypt(enc2, key), "AES 往返必须字节相等：" + preview(s));
            assertNotEquals(Base64.getEncoder().encodeToString(enc1),
                    Base64.getEncoder().encodeToString(enc2),
                    "随机 IV 应使同明文两次加密密文不同：" + preview(s));
        }
    }

    // ============================== StringFogRuntime 四条入口 ==============================

    @Test
    @DisplayName("入口①：Base64 单参默认路径往返（xor + DEFAULT_KEY，含边界）")
    void runtimeBase64LegacyRoundTrip() {
        for (String plain : BOUNDARY_STRINGS) {
            byte[] enc = new XorFog().encrypt(plain.getBytes(StandardCharsets.UTF_8), StringFogRuntime.DEFAULT_KEY);
            String cipher = Base64.getEncoder().encodeToString(enc);
            assertEquals(plain, StringFogRuntime.decrypt(cipher), "Base64 单参 decrypt 必须还原：" + preview(plain));
        }
    }

    @Test
    @DisplayName("入口②：Base64 三参路径往返（aes + 自定义密钥，含边界）")
    void runtimeBase64AesRoundTrip() {
        String key = "release-key-2026";
        for (String plain : BOUNDARY_STRINGS) {
            byte[] enc = new AesFog().encrypt(plain.getBytes(StandardCharsets.UTF_8),
                    key.getBytes(StandardCharsets.UTF_8));
            String cipher = Base64.getEncoder().encodeToString(enc);
            assertEquals(plain, StringFogRuntime.decrypt(cipher, key, StringFogRuntime.ALGORITHM_AES),
                    "Base64 三参 AES decrypt 必须还原：" + preview(plain));
        }
    }

    @Test
    @DisplayName("入口③：bytes 单参默认路径往返（xor + DEFAULT_KEY，免 Base64，含边界）")
    void runtimeBytesLegacyRoundTrip() {
        for (String plain : BOUNDARY_STRINGS) {
            byte[] enc = new XorFog().encrypt(plain.getBytes(StandardCharsets.UTF_8), StringFogRuntime.DEFAULT_KEY);
            assertEquals(plain, StringFogRuntime.decrypt(enc), "bytes 单参 decrypt 必须还原：" + preview(plain));
        }
    }

    @Test
    @DisplayName("入口④：bytes 三参路径往返（aes/xor + 自定义密钥，免 Base64，含边界）")
    void runtimeBytesMultiRoundTrip() {
        String key = "bytes-mode-key-中";
        for (String plain : BOUNDARY_STRINGS) {
            byte[] encAes = new AesFog().encrypt(plain.getBytes(StandardCharsets.UTF_8),
                    key.getBytes(StandardCharsets.UTF_8));
            assertEquals(plain, StringFogRuntime.decrypt(encAes, key, StringFogRuntime.ALGORITHM_AES),
                    "bytes 三参 AES decrypt 必须还原：" + preview(plain));
            byte[] encXor = new XorFog().encrypt(plain.getBytes(StandardCharsets.UTF_8),
                    key.getBytes(StandardCharsets.UTF_8));
            assertEquals(plain, StringFogRuntime.decrypt(encXor, key, StringFogRuntime.ALGORITHM_XOR),
                    "bytes 三参 XOR decrypt 必须还原：" + preview(plain));
        }
    }

    // ============================== 向后兼容 ==============================

    @Test
    @DisplayName("v1.1.0 向后兼容——旧逻辑密文可被新单参 decrypt 还原")
    void backwardCompatWithV110() {
        String plain = "This is a secret string! 中文";
        String v110Cipher = v110Encrypt(plain);
        assertEquals(plain, StringFogRuntime.decrypt(v110Cipher),
                "v1.1.0 逐字节 XOR+Base64 密文必须被 v2 单参 decrypt 逐字节还原");
    }

    // ============================== 自定义算法 ==============================

    @Test
    @DisplayName("自定义算法运行时注册后可解密（Base64 + bytes 两入口）")
    void customAlgorithmRegister() {
        // 自定义算法：字节取反（~b），构建期/运行时同算
        IStringFog notFog = new IStringFog() {
            @Override public byte[] encrypt(byte[] data, byte[] key) { return not(data); }
            @Override public byte[] decrypt(byte[] data, byte[] key) { return not(data); }
            private byte[] not(byte[] d) {
                byte[] o = new byte[d.length];
                for (int i = 0; i < d.length; i++) o[i] = (byte) (~d[i]);
                return o;
            }
        };
        StringFogRuntime.register("not", notFog);
        String plain = "custom not algorithm 自定义算法";
        byte[] enc = notFog.encrypt(plain.getBytes(StandardCharsets.UTF_8), new byte[0]);
        assertEquals(plain, StringFogRuntime.decrypt(Base64.getEncoder().encodeToString(enc), "", "not"));
        assertEquals(plain, StringFogRuntime.decrypt(enc, "", "not"));
    }

    // ============================== 每串随机密钥 ==============================

    @Test
    @DisplayName("RandomKeyGenerator：长度正确 / 仅字母数字 / 每串不同 / 往返成功")
    void randomKeyGenerator() {
        RandomKeyGenerator gen = new RandomKeyGenerator(16);
        Set<String> keys = new HashSet<>();
        String plain = "相同明文每串密钥应不同 same-plaintext";
        for (int i = 0; i < 200; i++) {
            String k = gen.generateKey(plain);
            assertEquals(16, k.length(), "密钥长度应为 16");
            assertTrue(k.matches("[A-Za-z0-9]+"), "密钥应仅含字母数字：" + k);
            keys.add(k);
            // 每串密钥往返：用生成的密钥加密再以同密钥解密（模拟构建期→运行时三参路径）
            byte[] enc = new XorFog().encrypt(plain.getBytes(StandardCharsets.UTF_8),
                    k.getBytes(StandardCharsets.UTF_8));
            assertEquals(plain, StringFogRuntime.decrypt(Base64.getEncoder().encodeToString(enc), k,
                    StringFogRuntime.ALGORITHM_XOR), "每串密钥三参往返必须还原");
        }
        assertTrue(keys.size() > 190, "200 次随机密钥应几乎全不相同，实际去重后=" + keys.size());
    }

    @Test
    @DisplayName("RandomKeyGenerator 非正长度构造应抛异常")
    void randomKeyGeneratorInvalidLength() {
        try {
            new RandomKeyGenerator(0);
            org.junit.jupiter.api.Assertions.fail("长度 0 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ============================== 防御性 / 默认判定 ==============================

    @Test
    @DisplayName("空串/未注册算法防御性回传 + shouldFog 默认实现")
    void defensiveFallback() {
        assertEquals("", StringFogRuntime.decrypt(""));
        assertEquals("rawcipher", StringFogRuntime.decrypt("rawcipher", "k", "unknown-algo"));
        assertEquals("", StringFogRuntime.decrypt(new byte[0]));
        assertEquals("", StringFogRuntime.decrypt(new byte[]{1, 2, 3}, "k", "unknown-algo"));
        // IStringFog.shouldFog 默认实现：过滤 null/空串
        assertTrue(new XorFog().shouldFog("x"));
        assertFalse(new XorFog().shouldFog(""));
        assertFalse(new XorFog().shouldFog(null));
        assertTrue(new AesFog().shouldFog("y"));
    }

    /** 日志/断言消息用的短预览（避免超长串刷屏）。 */
    private static String preview(String s) {
        String oneLine = s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "…(" + s.length() + " chars)";
    }
}
