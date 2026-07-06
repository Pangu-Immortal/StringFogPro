package com.vapro.vae.stringfog;

import java.security.SecureRandom;

/**
 * ===============================================================================
 * 功能：随机每串密钥生成器（{@link IKeyGenerator} 的默认实现，构建期使用）。
 * 函数简介：每次调用产出一个固定长度的随机字母数字密钥字符串；与明文内容无关，
 *   保证「每串独立随机密钥」——相同明文各处密文互不相同。
 *
 * 设计要点：
 *   1. 仅产出可打印 ASCII 字母数字（[A-Za-z0-9]）——保证密钥字符串经 UTF-8 稳定往返，
 *      构建期与运行时以相同字节还原密钥（规避随机字节可能落入非法码点/代理对的问题）。
 *   2. SecureRandom 强随机——单一 static 实例，SecureRandom 本身线程安全，可并发复用。
 *   3. 长度可配——默认 16 字符（约 95 bit 熵，足够使密文不可预测且不 grep 出共享密钥）。
 * ===============================================================================
 */
public final class RandomKeyGenerator implements IKeyGenerator {

    /** 密钥字符集：可打印 ASCII 字母数字，保证 UTF-8 稳定往返（每字符恰 1 字节）。 */
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    /** 默认密钥长度（字符数）。 */
    public static final int DEFAULT_KEY_LENGTH = 16;

    /** 单一强随机源；SecureRandom 线程安全，构建期多线程可并发复用。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 本实例产出的密钥长度。 */
    private final int keyLength;

    /** 以默认长度（16）构造。 */
    public RandomKeyGenerator() {
        this(DEFAULT_KEY_LENGTH);
    }

    /**
     * 以指定长度构造。
     *
     * @param keyLength 密钥字符数，必须为正
     */
    public RandomKeyGenerator(int keyLength) {
        if (keyLength <= 0) {
            // 关键逻辑：非正长度是配置错误，构建期尽早暴露
            throw new IllegalArgumentException("keyLength 必须为正，当前=" + keyLength);
        }
        this.keyLength = keyLength;
    }

    /**
     * 生成随机密钥（忽略 value，每次独立随机）。
     *
     * @param value 待加密明文（本实现忽略；仅为契约保留）
     * @return 长度为 keyLength 的随机字母数字密钥
     */
    @Override
    public String generateKey(String value) {
        StringBuilder sb = new StringBuilder(keyLength);
        for (int i = 0; i < keyLength; i++) {
            // 关键逻辑：逐字符从字母表按强随机下标取样
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
