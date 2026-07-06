package com.vapro.vae.stringfog;

/**
 * ===============================================================================
 * 功能：XOR 循环密钥加解密实现（StringFogPro 默认算法，向后兼容 v1.1.0）。
 * 函数简介：明文/密文字节与密钥字节做循环 XOR；XOR 自对称，故 encrypt 与 decrypt 同算。
 *
 * 兼容性契约（修改前必读）：
 *   1. 本实现 + {@link StringFogRuntime#DEFAULT_KEY} 必须与 v1.1.0 运行时逐字节等价，
 *      算法为 out[i] = data[i] ^ key[i % key.length]，保证历史密文与主工程零配置迁移可解密。
 *   2. 任意长度密钥均可（循环取模），16 字节 DEFAULT_KEY 走此路径即 i % 16。
 * ===============================================================================
 */
public final class XorFog implements IStringFog {

    /**
     * 循环 XOR 变换（加密）。
     *
     * @param data 明文字节
     * @param key  密钥字节（任意非空长度）
     * @return 密文字节，长度与 data 相同
     */
    @Override
    public byte[] encrypt(byte[] data, byte[] key) {
        return xor(data, key);
    }

    /**
     * 循环 XOR 变换（解密）。XOR 自对称，与 encrypt 完全同算。
     *
     * @param data 密文字节
     * @param key  密钥字节，须与加密一致
     * @return 明文字节
     */
    @Override
    public byte[] decrypt(byte[] data, byte[] key) {
        return xor(data, key);
    }

    /** 空串不加密，其余一律加密（最小串长阈值由插件层叠加）。 */
    @Override
    public boolean shouldFog(String value) {
        return value != null && !value.isEmpty();
    }

    /**
     * 循环 XOR 核心：out[i] = data[i] ^ key[i % key.length]。
     * <p>关键逻辑：密钥长度为 0 时直接返回原字节，避免除零。</p>
     */
    private static byte[] xor(byte[] data, byte[] key) {
        if (data == null || data.length == 0) {
            return data;
        }
        if (key == null || key.length == 0) {
            // 关键逻辑：无密钥则不变换（防御性，正常流程密钥必非空）
            return data.clone();
        }
        int klen = key.length;
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            // 关键逻辑：逐字节循环 XOR，与 v1.1.0 StringFogRuntime 逐字节等价
            out[i] = (byte) (data[i] ^ key[i % klen]);
        }
        return out;
    }
}
