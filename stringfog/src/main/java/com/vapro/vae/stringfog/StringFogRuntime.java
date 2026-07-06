package com.vapro.vae.stringfog;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ===============================================================================
 * 功能：StringFog 运行时解密调度器（单一运行时入口）。
 * 函数简介：release 构建期由 ASM 插桩把明文字符串替换为「密文 + INVOKESTATIC 本类 decrypt」，
 *   本类在运行时把密文还原为原始字符串。密文有两种传输形态、两种密钥形态，组合出四条入口。
 *
 * 四条解密入口（与构建期插桩一一对应，descriptor 精确匹配）：
 *   传输编码 × 密钥形态：
 *   ┌────────────┬───────────────────────────────┬──────────────────────────────────────────┐
 *   │            │ 默认路径（XOR + DEFAULT_KEY）   │ 可配置路径（AES/自定义/自定义密钥/每串密钥）│
 *   ├────────────┼───────────────────────────────┼──────────────────────────────────────────┤
 *   │ Base64 文本 │ {@link #decrypt(String)}      │ {@link #decrypt(String,String,String)}    │
 *   │ 原始 bytes  │ {@link #decrypt(byte[])}      │ {@link #decrypt(byte[],String,String)}    │
 *   └────────────┴───────────────────────────────┴──────────────────────────────────────────┘
 *   - 默认路径逐字节兼容 v1.1.0（单参 String），密文为 Base64 文本，栈中性。
 *   - bytes 模式免 Base64 解码，密文以原始 byte[] 存于字节码（不以可识别 Base64 文本出现，
 *     改变静态特征；byte[] 字面量由构建期以 BASTORE 序列构造）。
 *
 * 关键约束（修改本类前必读）：
 *   1. 本类及内置算法实现被插件 isInstrumentable 显式排除，禁止被二次插桩，否则 decrypt
 *      内部字符串再次加密 → 运行时 decrypt 调 decrypt 死循环。
 *   2. DEFAULT_KEY 必须与构建期默认密钥完全一致（XorFog 走此密钥即等价 v1.1.0）。
 *   3. 仅依赖 JDK（Base64/charset/crypto），不依赖 Android 框架 API，保证类加载顺序无关。
 *   4. 线程安全——REGISTRY 为 ConcurrentHashMap；DEFAULT_FOG/内置算法均无可变字段可并发复用；
 *      所有 decrypt 均为无状态纯函数，可跨线程并发调用。
 * ===============================================================================
 */
public final class StringFogRuntime {

    /** 私有构造：纯静态工具类，禁止实例化。 */
    private StringFogRuntime() {
    }

    /**
     * 默认 XOR 密钥（16 字节）。
     * 必须与构建期默认密钥完全一致；XorFog + 本密钥即与 v1.1.0 逐字节等价。
     */
    static final byte[] DEFAULT_KEY = {
        0x7A, 0x39, 0x4E, 0x21, 0x6B, 0x58, 0x33, 0x70,
        0x4B, 0x41, 0x52, 0x6E, 0x5A, 0x71, 0x68, 0x33
    };

    /** 默认算法 id（零配置与 DSL 缺省值）。 */
    public static final String ALGORITHM_XOR = "xor";
    /** AES 算法 id。 */
    public static final String ALGORITHM_AES = "aes";

    /** 默认算法实现（供默认路径复用，避免重复实例化）；XorFog 无状态，可并发复用。 */
    private static final IStringFog DEFAULT_FOG = new XorFog();

    /**
     * 算法注册表：algorithm id → 实现。
     * 内置 xor / aes；自定义算法可通过 {@link #register(String, IStringFog)} 在运行时接入。
     * ConcurrentHashMap 保证注册/查询的线程安全（构建期同一算法在运行时首帧前 register 即可）。
     */
    private static final Map<String, IStringFog> REGISTRY = new ConcurrentHashMap<>();

    static {
        REGISTRY.put(ALGORITHM_XOR, new XorFog());
        REGISTRY.put(ALGORITHM_AES, new AesFog());
    }

    /**
     * 返回默认 XOR 密钥的副本（构建期插件复用，避免 KEY 常量在插件侧重复声明导致漂移）。
     * <p>说明：本 KEY 并非密码学意义上的机密——它本就随插桩字节码嵌入 APK，
     * 暴露只读副本不降低安全性，换取「密钥单一真相源」。</p>
     *
     * @return DEFAULT_KEY 的克隆（调用方修改不影响内部常量）
     */
    public static byte[] defaultKey() {
        return DEFAULT_KEY.clone();
    }

    /**
     * 注册自定义算法实现（运行时扩展入口）。
     * <p>关键逻辑：构建期用 DSL algorithm=全限定类名 以同一实现加密，运行时须以相同 id 注册以解密。
     * 内置 id（xor/aes）不建议覆盖；自定义算法的 id 为其 FQCN，天然不与内置冲突。</p>
     *
     * @param algorithm 算法 id（须与构建期插桩写入的 algorithm 字面量一致），null 时静默忽略
     * @param impl      算法实现，须与构建期使用的实现同算，null 时静默忽略
     */
    public static void register(String algorithm, IStringFog impl) {
        if (algorithm != null && impl != null) {
            REGISTRY.put(algorithm, impl);
        }
    }

    // ============================== Base64 文本入口 ==============================

    /**
     * 单参解密（默认路径，Base64 文本，向后兼容 v1.1.0 与主工程）。
     * <p>关键逻辑：Base64 解码 → XorFog + DEFAULT_KEY 还原 → UTF-8 构造字符串。</p>
     *
     * @param encrypted Base64 编码的 XOR 密文（构建期默认路径写入常量池）
     * @return 原始字符串；null/空或异常时原样返回，保证单条失败不致崩溃
     */
    public static String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return encrypted;
        }
        try {
            byte[] enc = Base64.getDecoder().decode(encrypted);
            return decodeUtf8(DEFAULT_FOG.decrypt(enc, DEFAULT_KEY));
        } catch (Throwable t) {
            // 兜底：解密失败返回密文，避免单条字符串异常导致类初始化/方法调用崩溃
            return encrypted;
        }
    }

    /**
     * 三参解密（可配置路径，Base64 文本：AES / 自定义算法 / 自定义密钥 / 每串随机密钥）。
     * <p>关键逻辑：按 algorithm 取注册实现 → Base64 解码 → 以 key 的 UTF-8 字节解密 → UTF-8 构造字符串。</p>
     *
     * @param encrypted Base64 编码的算法密文
     * @param key       密钥字符串（其 UTF-8 字节即算法密钥，与构建期一致；每串密钥模式下为该串专属密钥）
     * @param algorithm 算法 id（xor / aes / 自定义 FQCN）
     * @return 原始字符串；未注册算法或异常时原样返回密文
     */
    public static String decrypt(String encrypted, String key, String algorithm) {
        if (encrypted == null || encrypted.isEmpty()) {
            return encrypted;
        }
        try {
            IStringFog fog = REGISTRY.get(algorithm);
            if (fog == null) {
                // 防御性：未注册算法直接回传密文（提示调用方运行时未 register）
                return encrypted;
            }
            byte[] enc = Base64.getDecoder().decode(encrypted);
            return decodeUtf8(fog.decrypt(enc, keyBytes(key)));
        } catch (Throwable t) {
            return encrypted;
        }
    }

    // ============================== 原始 bytes 入口（bytes 模式） ==============================

    /**
     * 单参解密（默认路径，原始 bytes，免 Base64 解码）。
     * <p>关键逻辑：XorFog + DEFAULT_KEY 直接还原原始密文字节 → UTF-8 构造字符串。</p>
     *
     * @param encrypted 原始 XOR 密文字节（构建期以 byte[] 字面量写入方法体）
     * @return 原始字符串；null/空或异常时兜底返回空串
     */
    public static String decrypt(byte[] encrypted) {
        if (encrypted == null || encrypted.length == 0) {
            return "";
        }
        try {
            return decodeUtf8(DEFAULT_FOG.decrypt(encrypted, DEFAULT_KEY));
        } catch (Throwable t) {
            // 兜底：bytes 模式无法回退到「原文」，返回空串避免崩溃（配置正确时不会走到）
            return "";
        }
    }

    /**
     * 三参解密（可配置路径，原始 bytes：AES / 自定义算法 / 自定义密钥 / 每串随机密钥）。
     * <p>关键逻辑：按 algorithm 取注册实现 → 以 key 的 UTF-8 字节直接解密原始密文字节 → UTF-8 构造字符串。</p>
     *
     * @param encrypted 原始算法密文字节
     * @param key       密钥字符串（其 UTF-8 字节即算法密钥）
     * @param algorithm 算法 id（xor / aes / 自定义 FQCN）
     * @return 原始字符串；未注册算法或异常时兜底返回空串
     */
    public static String decrypt(byte[] encrypted, String key, String algorithm) {
        if (encrypted == null || encrypted.length == 0) {
            return "";
        }
        try {
            IStringFog fog = REGISTRY.get(algorithm);
            if (fog == null) {
                return "";
            }
            return decodeUtf8(fog.decrypt(encrypted, keyBytes(key)));
        } catch (Throwable t) {
            return "";
        }
    }

    // ============================== 内部工具 ==============================

    /** 密钥字符串 → UTF-8 字节（null 归一化为空字节数组，与构建期一致）。 */
    private static byte[] keyBytes(String key) {
        return key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }

    /** 明文字节 → UTF-8 字符串。 */
    private static String decodeUtf8(byte[] plain) {
        return new String(plain, StandardCharsets.UTF_8);
    }
}
