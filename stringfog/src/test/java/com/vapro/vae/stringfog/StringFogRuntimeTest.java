package com.vapro.vae.stringfog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ===============================================================================
 * 功能：StringFogPro 运行时 + 算法单元测试。
 * 覆盖：
 *   1. XorFog / AesFog 字节级往返对称。
 *   2. StringFogRuntime 单参（默认 XOR）与三参（AES/自定义密钥）双入口往返。
 *   3. v1.1.0 向后兼容——DEFAULT_KEY 的 XOR+Base64 密文能被单参 decrypt 还原。
 *   4. 自定义算法运行时注册后可解密。
 * ===============================================================================
 */
class StringFogRuntimeTest {

    /** 复刻 v1.1.0 逐字节加密逻辑（out[i] = data[i] ^ KEY[i%16] → Base64），用于交叉验证兼容性。 */
    private static final byte[] V110_KEY = {
        0x7A, 0x39, 0x4E, 0x21, 0x6B, 0x58, 0x33, 0x70,
        0x4B, 0x41, 0x52, 0x6E, 0x5A, 0x71, 0x68, 0x33
    };

    private static String v110Encrypt(String plain) {
        byte[] raw = plain.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = (byte) ((raw[i] & 0xFF) ^ (V110_KEY[i % V110_KEY.length] & 0xFF));
        }
        return Base64.getEncoder().encodeToString(out);
    }

    @Test
    @DisplayName("XorFog 字节级往返对称")
    void xorRoundTrip() {
        XorFog fog = new XorFog();
        byte[] data = "分身定位 GPS spoof 中文混合 String".getBytes(StandardCharsets.UTF_8);
        byte[] key = "any-length-key-字节".getBytes(StandardCharsets.UTF_8);
        byte[] enc = fog.encrypt(data, key);
        byte[] dec = fog.decrypt(enc, key);
        assertArrayEquals(data, dec, "XOR 往返必须字节相等");
        assertNotEquals(new String(data, StandardCharsets.UTF_8), new String(enc, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("AesFog 字节级往返对称 + 随机 IV 使密文每次不同")
    void aesRoundTripAndRandomIv() {
        AesFog fog = new AesFog();
        byte[] data = "AES 强度增强 payload 12345".getBytes(StandardCharsets.UTF_8);
        byte[] key = "my-secret".getBytes(StandardCharsets.UTF_8);
        byte[] enc1 = fog.encrypt(data, key);
        byte[] enc2 = fog.encrypt(data, key);
        assertArrayEquals(data, fog.decrypt(enc1, key), "AES 往返必须字节相等");
        assertArrayEquals(data, fog.decrypt(enc2, key), "AES 往返必须字节相等");
        assertNotEquals(Base64.getEncoder().encodeToString(enc1),
                Base64.getEncoder().encodeToString(enc2),
                "随机 IV 应使同明文两次加密密文不同");
    }

    @Test
    @DisplayName("StringFogRuntime 单参默认路径往返（xor + DEFAULT_KEY）")
    void runtimeLegacyRoundTrip() {
        String plain = "com.tencent.mm 明文包名 https://example.com/api";
        // 用运行时默认算法加密（模拟构建期默认路径）
        byte[] enc = new XorFog().encrypt(plain.getBytes(StandardCharsets.UTF_8), StringFogRuntime.DEFAULT_KEY);
        String cipher = Base64.getEncoder().encodeToString(enc);
        assertEquals(plain, StringFogRuntime.decrypt(cipher), "单参 decrypt 必须还原明文");
    }

    @Test
    @DisplayName("v1.1.0 向后兼容——旧逻辑密文可被新单参 decrypt 还原")
    void backwardCompatWithV110() {
        String plain = "This is a secret string! 中文";
        String v110Cipher = v110Encrypt(plain);
        assertEquals(plain, StringFogRuntime.decrypt(v110Cipher),
                "v1.1.0 逐字节 XOR+Base64 密文必须被 v2 单参 decrypt 逐字节还原");
    }

    @Test
    @DisplayName("StringFogRuntime 三参路径往返（aes + 自定义密钥）")
    void runtimeAesRoundTrip() {
        String plain = "backend token = abc123-中文";
        String key = "release-key-2026";
        byte[] enc = new AesFog().encrypt(plain.getBytes(StandardCharsets.UTF_8),
                key.getBytes(StandardCharsets.UTF_8));
        String cipher = Base64.getEncoder().encodeToString(enc);
        assertEquals(plain, StringFogRuntime.decrypt(cipher, key, StringFogRuntime.ALGORITHM_AES));
    }

    @Test
    @DisplayName("StringFogRuntime 三参路径往返（xor + 自定义密钥）")
    void runtimeXorCustomKeyRoundTrip() {
        String plain = "custom xor key path 测试";
        String key = "hello-key";
        byte[] enc = new XorFog().encrypt(plain.getBytes(StandardCharsets.UTF_8),
                key.getBytes(StandardCharsets.UTF_8));
        String cipher = Base64.getEncoder().encodeToString(enc);
        assertEquals(plain, StringFogRuntime.decrypt(cipher, key, StringFogRuntime.ALGORITHM_XOR));
    }

    @Test
    @DisplayName("自定义算法运行时注册后可解密")
    void customAlgorithmRegister() {
        // 自定义算法：字节取反（~b），构建期/运行时同算
        IStringFog notFog = new IStringFog() {
            @Override public byte[] encrypt(byte[] data, byte[] key) { return not(data); }
            @Override public byte[] decrypt(byte[] data, byte[] key) { return not(data); }
            @Override public boolean shouldFog(String value) { return value != null && !value.isEmpty(); }
            private byte[] not(byte[] d) {
                byte[] o = new byte[d.length];
                for (int i = 0; i < d.length; i++) o[i] = (byte) (~d[i]);
                return o;
            }
        };
        StringFogRuntime.register("not", notFog);
        String plain = "custom not algorithm";
        byte[] enc = notFog.encrypt(plain.getBytes(StandardCharsets.UTF_8), new byte[0]);
        String cipher = Base64.getEncoder().encodeToString(enc);
        assertEquals(plain, StringFogRuntime.decrypt(cipher, "", "not"));
    }

    @Test
    @DisplayName("空串/未注册算法防御性回传")
    void defensiveFallback() {
        assertEquals("", StringFogRuntime.decrypt(""));
        assertEquals("rawcipher", StringFogRuntime.decrypt("rawcipher", "k", "unknown-algo"));
        assertTrue(new XorFog().shouldFog("x"));
        assertEquals(false, new XorFog().shouldFog(""));
    }
}
