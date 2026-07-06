package com.vapro.vae.stringfog;

/**
 * ===============================================================================
 * 功能：StringFog 加解密算法统一接口（构建期与运行时共用的唯一契约）。
 * 函数简介：定义字节级 encrypt / decrypt 以及 shouldFog 判定；构建期插桩把明文字符串
 *   经本接口 encrypt 后写入常量池，运行时经同一实现的 decrypt 还原，二者算法保持一致。
 *
 * 设计要点：
 *   1. 字节级契约——只负责 byte[] ⇄ byte[] 变换，Base64 传输编码由上层
 *      （构建期 MethodVisitor / 运行时 StringFogRuntime）统一处理，接口与编码解耦。
 *   2. 自主扩展——用户实现本接口即可接入自定义算法，通过 DSL algorithm=全限定类名
 *      在构建期加载，通过 StringFogRuntime.register 在运行时注册，两端走同一实现。
 *   3. 无 Android 依赖——纯 JDK 接口，保证运行时库为纯 Java JAR，JitPack 纯 JVM 可建。
 * ===============================================================================
 */
public interface IStringFog {

    /**
     * 加密：明文字节 → 密文字节（构建期调用）。
     *
     * @param data 明文字节（通常为字符串的 UTF-8 编码）
     * @param key  密钥字节（算法内部自行归一化到所需长度）
     * @return 密文字节；实现须保证与 {@link #decrypt(byte[], byte[])} 严格对称
     */
    byte[] encrypt(byte[] data, byte[] key);

    /**
     * 解密：密文字节 → 明文字节（运行时调用）。
     *
     * @param data 密文字节（与 encrypt 输出一致，Base64 解码后传入）
     * @param key  密钥字节，须与加密时完全一致
     * @return 明文字节；与 encrypt 输入严格对称
     */
    byte[] decrypt(byte[] data, byte[] key);

    /**
     * 判定某个字符串字面量是否值得加密。
     * <p>关键逻辑：过滤空串等无意义内容，避免常量池膨胀；最小串长阈值由插件层叠加控制。</p>
     *
     * @param value 待判定的原始字符串字面量
     * @return true 表示应加密，false 表示原样保留
     */
    boolean shouldFog(String value);
}
