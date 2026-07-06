package com.vapro.vae.stringfog;

import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ===============================================================================
 * 功能：AES-128/CBC/PKCS5Padding 加解密实现（StringFogPro 强度增强算法）。
 * 函数简介：以随机 IV 加密，密文形态为「16 字节 IV || 密文体」，解密时前 16 字节取回 IV。
 *
 * 设计要点：
 *   1. 密钥归一化——用户密钥字节经 SHA-256 取前 16 字节得 AES-128 密钥，
 *      故 DSL 中 key 可为任意长度字符串；构建期与运行时归一化算法完全一致。
 *   2. 随机 IV——每次加密使用 SecureRandom 生成 16 字节 IV 并前置到密文，
 *      使同一明文每次构建密文不同（比 XOR 更抗模式分析），解密自描述无需外带 IV。
 *   3. 仅依赖 JDK javax.crypto——纯 Java，无 Android 依赖。
 * ===============================================================================
 */
public final class AesFog implements IStringFog {

    /** AES 分组/IV 长度（字节），亦即 AES-128 密钥长度。 */
    private static final int BLOCK = 16;
    /** AES 变换名。 */
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    /** 密钥归一化摘要算法（取其输出前 BLOCK 字节为 AES-128 密钥）。 */
    private static final String KEY_DIGEST = "SHA-256";
    /** 强随机 IV 源；SecureRandom 线程安全，构建期多线程可并发复用（避免逐次 new 的开销）。 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * AES 加密：输出「IV(16B) || 密文体」。
     *
     * @param data 明文字节
     * @param key  用户密钥字节（任意长度，内部归一化到 16 字节）
     * @return IV 前置的密文字节
     */
    @Override
    public byte[] encrypt(byte[] data, byte[] key) {
        try {
            // 关键逻辑：随机 IV，保证同明文多次加密结果不同
            byte[] iv = new byte[BLOCK];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(key), new IvParameterSpec(iv));
            byte[] body = cipher.doFinal(data);
            // 关键逻辑：IV 前置拼接，形成自描述密文
            byte[] out = new byte[BLOCK + body.length];
            System.arraycopy(iv, 0, out, 0, BLOCK);
            System.arraycopy(body, 0, out, BLOCK, body.length);
            return out;
        } catch (Exception e) {
            // 加密期异常直接抛出（构建期应尽早暴露配置/环境问题）
            throw new IllegalStateException("AesFog encrypt failed", e);
        }
    }

    /**
     * AES 解密：从「IV(16B) || 密文体」取回 IV 后解密。
     *
     * @param data IV 前置的密文字节
     * @param key  用户密钥字节，须与加密一致
     * @return 明文字节
     */
    @Override
    public byte[] decrypt(byte[] data, byte[] key) {
        try {
            if (data == null || data.length < BLOCK) {
                // 关键逻辑：长度不足以容纳 IV，视为非法密文
                return data;
            }
            // 关键逻辑：前 16 字节取回加密时的随机 IV
            byte[] iv = new byte[BLOCK];
            System.arraycopy(data, 0, iv, 0, BLOCK);
            int bodyLen = data.length - BLOCK;
            byte[] body = new byte[bodyLen];
            System.arraycopy(data, BLOCK, body, 0, bodyLen);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(key), new IvParameterSpec(iv));
            return cipher.doFinal(body);
        } catch (Exception e) {
            // 运行时解密失败：交由上层 StringFogRuntime 兜底（返回密文），此处直接抛出
            throw new IllegalStateException("AesFog decrypt failed", e);
        }
    }

    // shouldFog 采用 IStringFog 默认实现（过滤 null/空串）；最小串长阈值由插件层叠加，无需覆写。

    /**
     * 密钥归一化：SHA-256(key) 取前 16 字节构成 AES-128 密钥。
     * <p>关键逻辑：使任意长度用户密钥都能得到合法 128 位密钥，构建期/运行时归一化一致。</p>
     */
    private static SecretKeySpec secretKey(byte[] key) throws Exception {
        MessageDigest md = MessageDigest.getInstance(KEY_DIGEST);
        byte[] digest = md.digest(key == null ? new byte[0] : key);
        byte[] aes128 = new byte[BLOCK];
        System.arraycopy(digest, 0, aes128, 0, BLOCK);
        return new SecretKeySpec(aes128, "AES");
    }
}
