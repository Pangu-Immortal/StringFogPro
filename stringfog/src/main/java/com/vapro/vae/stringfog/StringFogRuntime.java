package com.vapro.vae.stringfog;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ===============================================================================
 * 功能：StringFog 运行时解密调度器（单一运行时入口）。
 * 函数简介：release 构建期由 ASM 插桩把明文字符串替换为「密文 + INVOKESTATIC 本类 decrypt」，
 *   本类在运行时把密文（Base64 编码的算法密文）还原为原始字符串。
 *
 * 两条解密入口（与构建期插桩一一对应）：
 *   1. {@link #decrypt(String)}          —— 零配置/默认路径：XOR + DEFAULT_KEY + Base64，
 *      与 v1.1.0 运行时逐字节等价，保证历史密文与主工程零配置迁移可解密（栈中性，单参插桩）。
 *   2. {@link #decrypt(String, String, String)} —— 可配置路径：按 algorithm 选择算法、
 *      按 key 提供密钥（AES / 自定义 / 自定义密钥的 XOR 走此入口，三参插桩）。
 *
 * 关键约束（修改本类前必读）：
 *   1. 本类及内置算法实现被插件 isInstrumentable 显式排除，禁止被二次插桩，否则 decrypt
 *      内部字符串再次加密 → 运行时 decrypt 调 decrypt 死循环。
 *   2. DEFAULT_KEY 必须与构建期默认密钥完全一致（XorFog 走此密钥即等价 v1.1.0）。
 *   3. 仅依赖 JDK（Base64/charset/crypto），不依赖 Android 框架 API，保证类加载顺序无关。
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

    /** 默认算法实现（供 {@link #decrypt(String)} 单参路径复用，避免重复实例化）。 */
    private static final IStringFog DEFAULT_FOG = new XorFog();

    /**
     * 算法注册表：algorithm id → 实现。
     * 内置 xor / aes；自定义算法可通过 {@link #register(String, IStringFog)} 在运行时接入。
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
     * <p>关键逻辑：构建期用 DSL algorithm=全限定类名 以同一实现加密，运行时须以相同 id 注册以解密。</p>
     *
     * @param algorithm 算法 id（须与构建期插桩写入的 algorithm 字面量一致）
     * @param impl      算法实现，须与构建期使用的实现同算
     */
    public static void register(String algorithm, IStringFog impl) {
        if (algorithm != null && impl != null) {
            REGISTRY.put(algorithm, impl);
        }
    }

    /**
     * 单参解密（零配置/默认路径，向后兼容 v1.1.0 与主工程）。
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
            byte[] out = DEFAULT_FOG.decrypt(enc, DEFAULT_KEY);
            return new String(out, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // 兜底：解密失败返回密文，避免单条字符串异常导致类初始化/方法调用崩溃
            return encrypted;
        }
    }

    /**
     * 三参解密（可配置路径：AES / 自定义算法 / 自定义密钥的 XOR）。
     * <p>关键逻辑：按 algorithm 取注册实现 → Base64 解码 → 以 key 的 UTF-8 字节解密 → UTF-8 构造字符串。</p>
     *
     * @param encrypted Base64 编码的算法密文
     * @param key       密钥字符串（其 UTF-8 字节即算法密钥，与构建期一致）
     * @param algorithm 算法 id（xor / aes / 自定义）
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
            byte[] keyBytes = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
            byte[] out = fog.decrypt(enc, keyBytes);
            return new String(out, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return encrypted;
        }
    }
}
