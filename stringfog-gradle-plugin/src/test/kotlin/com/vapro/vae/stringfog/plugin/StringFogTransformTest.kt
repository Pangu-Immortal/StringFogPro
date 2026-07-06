package com.vapro.vae.stringfog.plugin

import com.vapro.vae.stringfog.IStringFog
import com.vapro.vae.stringfog.StringFogRuntime
import com.vapro.vae.stringfog.plugin.core.FogConfig
import com.vapro.vae.stringfog.plugin.core.FogConfigResolver
import com.vapro.vae.stringfog.plugin.core.MappingWriter
import com.vapro.vae.stringfog.plugin.core.PackageFilter
import com.vapro.vae.stringfog.plugin.core.StringFogClassVisitor
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * ===============================================================================
 * 功能：StringFog 核心 ASM 插桩集成测试（脱离 AGP，端到端验证加密-解密往返与配置行为）。
 * 方法：ASM 生成含明文 String 的类 → 核心访问器插桩 → 断言明文从常量池消失 →
 *   自定义类加载器加载插桩后字节 → 反射调用方法 → 断言返回值 == 原始明文（运行时解密成功）。
 *   插桩用 ClassWriter(0)（不重算 maxStack），据 MethodVisitor 发射的 visitMaxs 写入，
 *   从而真实检验各形态的栈深补偿——补偿不足则加载即 VerifyError。
 *
 * 覆盖：Base64 单参/三参、bytes 单参/三参、每串随机密钥、算法切换、自定义密钥、
 *   自定义算法(FQCN 反射)、边界(空/短/超长/Unicode/emoji/拼接)、包过滤、映射输出。
 * ===============================================================================
 */
class StringFogTransformTest {

    /** 内存类加载器：加载插桩后的字节，其内 INVOKESTATIC StringFogRuntime 由测试 classpath 提供。 */
    private class BytesClassLoader : ClassLoader(StringFogTransformTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    /** 生成一个类：每个 (方法名→明文) 生成 public static String 方法返回该明文。 */
    private fun generateClass(internalName: String, methods: Map<String, String>): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
        }
        for ((method, plaintext) in methods) {
            cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, method, "()Ljava/lang/String;", null, null).apply {
                visitCode(); visitLdcInsn(plaintext); visitInsn(Opcodes.ARETURN); visitMaxs(0, 0); visitEnd()
            }
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun generateClass(internalName: String, plaintext: String): ByteArray =
        generateClass(internalName, mapOf("value" to plaintext))

    /**
     * 生成一个用 StringBuilder 拼接两个字面量常量的类（验证「非常量拼接不误伤」）：
     * public static String concat() { return new StringBuilder().append(A).append(B).toString(); }
     */
    private fun generateConcatClass(internalName: String, a: String, b: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "concat", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
            visitLdcInsn(a)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
            visitLdcInsn(b)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** 解析出的 config 直接插桩（ClassWriter(0) 真实检验栈深补偿）。 */
    private fun transform(original: ByteArray, config: FogConfig): ByteArray {
        val cr = ClassReader(original)
        val cw = ClassWriter(0)
        cr.accept(StringFogClassVisitor(StringFogClassVisitor.ASM_API, cw, config), 0)
        return cw.toByteArray()
    }

    /** 便捷解析：默认 Base64 文本、固定密钥、无每串密钥、无映射。 */
    private fun resolve(
        algorithm: String = "xor",
        key: String = FogConfigResolver.DEFAULT_KEY_SENTINEL,
        minLength: Int = 1,
        bytesMode: Boolean = false,
        randomKeyPerString: Boolean = false,
        mapping: MappingWriter? = null
    ): FogConfig = FogConfigResolver.resolve(
        algorithm = algorithm, key = key, minLength = minLength, bytesMode = bytesMode,
        randomKeyPerString = randomKeyPerString, randomKeyLength = 16,
        mappingWriter = mapping, classLoader = javaClass.classLoader
    )

    private fun containsPlaintext(bytes: ByteArray, plaintext: String): Boolean {
        val needle = plaintext.toByteArray(StandardCharsets.UTF_8)
        if (needle.isEmpty()) return false
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun invoke(internalName: String, bytes: ByteArray, method: String = "value"): String {
        val clazz = BytesClassLoader().define(internalName.replace('/', '.'), bytes)
        return clazz.getMethod(method).invoke(null) as String
    }

    // ============================== Base64 文本形态 ==============================

    @Test
    @DisplayName("Base64 legacy 单参：明文消失 + 运行时还原（默认 XOR）")
    fun base64LegacyXorRoundTrip() {
        val name = "gen/LegacySecret"
        // 说明：byte-search 测试用 BMP 明文（中文在标准 UTF-8 与 class 常量池 Modified-UTF-8 编码一致）；
        //   补充平面字符(emoji)在常量池是 6 字节 CESU-8，与标准 4 字节 UTF-8 不同，会干扰 byte-search，
        //   故 emoji 的 ASM 路径往返由 unicodeEmojiRoundTrip 单独以 invoke 断言（不做 byte-search）。
        val plaintext = "https://api.example.com/secret?token=PLAINTEXT-XOR-中文汉字"
        val original = generateClass(name, plaintext)
        assertTrue(containsPlaintext(original, plaintext), "原始字节应含明文")
        val fogged = transform(original, resolve())
        assertFalse(containsPlaintext(fogged, plaintext), "插桩后明文必须从常量池消失")
        assertEquals(plaintext, invoke(name, fogged), "运行时 decrypt 必须还原明文")
    }

    @Test
    @DisplayName("Unicode/emoji 明文经 ASM 路径往返（invoke 断言，覆盖补充平面字符）")
    fun unicodeEmojiRoundTrip() {
        val name = "gen/EmojiSecret"
        val plaintext = "混合明文 中文 😀🔐🚀 token=abc-中文-😀"
        // Base64 与 bytes 两条形态都应正确往返补充平面字符
        val base64Fogged = transform(generateClass(name, plaintext), resolve())
        assertEquals(plaintext, invoke(name, base64Fogged), "Base64 路径必须正确还原 emoji")
        val name2 = "gen/EmojiSecretBytes"
        val bytesFogged = transform(generateClass(name2, plaintext), resolve(bytesMode = true))
        assertEquals(plaintext, invoke(name2, bytesFogged), "bytes 路径必须正确还原 emoji")
    }

    @Test
    @DisplayName("Base64 三参：明文消失 + 还原 + maxStack 补偿正确（AES）")
    fun base64AesRoundTrip() {
        val name = "gen/AesSecret"
        val plaintext = "AES 加密的后端密钥 sk-abcdef-0123456789 😀"
        val original = generateClass(name, plaintext)
        val fogged = transform(original, resolve(algorithm = "aes", key = "my-release-key-2026"))
        assertFalse(containsPlaintext(fogged, plaintext), "AES 插桩后明文必须消失")
        assertEquals(plaintext, invoke(name, fogged), "AES 运行时 decrypt 必须还原（栈补偿不足会 VerifyError）")
    }

    @Test
    @DisplayName("Base64 三参：自定义密钥 XOR 往返")
    fun base64XorCustomKeyRoundTrip() {
        val name = "gen/CustomKeySecret"
        val plaintext = "custom key xor 路径明文"
        val fogged = transform(generateClass(name, plaintext), resolve(algorithm = "xor", key = "hello-custom-key"))
        assertFalse(containsPlaintext(fogged, plaintext))
        assertEquals(plaintext, invoke(name, fogged))
    }

    // ============================== bytes 形态（免 Base64） ==============================

    @Test
    @DisplayName("bytes legacy 单参：明文消失 + 还原 + 栈补偿正确（默认 XOR，byte[] 字面量）")
    fun bytesLegacyXorRoundTrip() {
        val name = "gen/BytesLegacy"
        val plaintext = "bytes 模式免 Base64 的明文 token=xyz-中文😀"
        val fogged = transform(generateClass(name, plaintext), resolve(bytesMode = true))
        assertFalse(containsPlaintext(fogged, plaintext), "bytes 模式插桩后明文必须消失")
        assertEquals(plaintext, invoke(name, fogged), "bytes 单参 decrypt 必须还原（byte[] 构造栈补偿不足会 VerifyError）")
    }

    @Test
    @DisplayName("bytes 三参：明文消失 + 还原 + 栈补偿正确（AES，byte[] 字面量）")
    fun bytesAesRoundTrip() {
        val name = "gen/BytesAes"
        val plaintext = "bytes+aes 后端密钥 sk-bytes-aes-9999 中文"
        val fogged = transform(generateClass(name, plaintext), resolve(algorithm = "aes", key = "k-2026", bytesMode = true))
        assertFalse(containsPlaintext(fogged, plaintext), "bytes+AES 插桩后明文必须消失")
        assertEquals(plaintext, invoke(name, fogged), "bytes+AES 运行时 decrypt 必须还原")
    }

    // ============================== 每串随机密钥 ==============================

    @Test
    @DisplayName("每串随机密钥：相同明文两处密文不同 + 各自还原 + 映射记录")
    fun randomKeyPerStringDistinctCipher() {
        val name = "gen/RandomKeySecret"
        val plaintext = "同一明文出现两处 duplicated-secret-中文"
        val mappingFile = Files.createTempFile("sf-map-random", ".txt").toFile()
        val config = resolve(randomKeyPerString = true, mapping = MappingWriter(mappingFile))
        val fogged = transform(generateClass(name, mapOf("v1" to plaintext, "v2" to plaintext)), config)
        assertFalse(containsPlaintext(fogged, plaintext), "每串密钥插桩后明文必须消失")
        // 两方法各自还原
        assertEquals(plaintext, invoke(name, fogged, "v1"))
        assertEquals(plaintext, invoke(name, fogged, "v2"))
        // 映射文件应含两条相同明文、不同密文的记录（每串密钥 → 密文不同）
        val lines = mappingFile.readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size, "应记录两条映射")
        val ciphers = lines.map { it.substringAfterLast("  ->  ") }
        assertNotEquals(ciphers[0], ciphers[1], "每串随机密钥应使相同明文密文不同")
    }

    // ============================== 自定义算法（FQCN 反射） ==============================

    @Test
    @DisplayName("自定义算法（全限定类名反射）：构建期加密 + 运行时注册后还原")
    fun customAlgorithmByFqcn() {
        val fqcn = "com.vapro.vae.stringfog.plugin.NotFog"
        // 运行时注册（构建期由 resolver 反射构造同类，运行时须以同 id 注册）
        StringFogRuntime.register(fqcn, NotFog())
        val name = "gen/CustomAlgoSecret"
        val plaintext = "custom fqcn algorithm 明文"
        val fogged = transform(generateClass(name, plaintext), resolve(algorithm = fqcn, key = "k"))
        assertFalse(containsPlaintext(fogged, plaintext))
        assertEquals(plaintext, invoke(name, fogged))
    }

    // ============================== 边界 ==============================

    @Test
    @DisplayName("边界：空串不加密 / 短于 minLength 不加密 / 正常串加密")
    fun minLengthBoundary() {
        val name = "gen/MinLen"
        val original = generateClass(name, mapOf("short" to "ab", "empty" to "", "ok" to "abcdef"))
        val fogged = transform(original, resolve(minLength = 5))
        // 阈值 5：短串"ab"、空串 → 保持明文；"abcdef" → 加密
        assertTrue(containsPlaintext(fogged, "ab"), "短于 minLength 的串应保持明文")
        assertFalse(containsPlaintext(fogged, "abcdef"), "达到 minLength 的串应被加密")
        assertEquals("ab", invoke(name, fogged, "short"))
        assertEquals("", invoke(name, fogged, "empty"))
        assertEquals("abcdef", invoke(name, fogged, "ok"))
    }

    @Test
    @DisplayName("边界：超长串（超 maxUtf8Bytes）跳过加密，保持明文（bytes 模式阈值 8000）")
    fun oversizedStringSkipped() {
        val name = "gen/Oversized"
        val plaintext = "X".repeat(9000) // > bytes 模式 8000 阈值
        val fogged = transform(generateClass(name, plaintext), resolve(bytesMode = true))
        assertTrue(containsPlaintext(fogged, plaintext), "超长串应跳过加密（防越限），保持明文")
        assertEquals(plaintext, invoke(name, fogged))
    }

    @Test
    @DisplayName("边界：字符串拼接的常量部分被安全加密并正确重组（非常量拼接不误伤）")
    fun concatenationNotBroken() {
        val name = "gen/Concat"
        val a = "https://host.example.com/"
        val b = "path?token=SECRET-中文😀"
        val fogged = transform(generateConcatClass(name, a, b), resolve())
        assertFalse(containsPlaintext(fogged, a), "拼接常量 A 应被加密")
        assertFalse(containsPlaintext(fogged, b), "拼接常量 B 应被加密")
        assertEquals(a + b, invoke(name, fogged, "concat"), "拼接结果必须正确重组")
    }

    // ============================== 包过滤（配置项 E2E 纯逻辑） ==============================

    @Test
    @DisplayName("包过滤：白名单只命中前缀 / 黑名单优先 / 运行时类永远排除")
    fun packageFilter() {
        // 运行时解密器/算法类永远排除
        for (rt in PackageFilter.RUNTIME_CLASSES) {
            assertFalse(PackageFilter.shouldInstrument(rt, emptyList(), emptyList()), "运行时类须排除：$rt")
        }
        // 空白名单=全部加密
        assertTrue(PackageFilter.shouldInstrument("com.app.Foo", emptyList(), emptyList()))
        // 白名单收窄
        assertTrue(PackageFilter.shouldInstrument("com.app.Foo", listOf("com.app"), emptyList()))
        assertFalse(PackageFilter.shouldInstrument("com.other.Bar", listOf("com.app"), emptyList()))
        // 黑名单排除
        assertFalse(PackageFilter.shouldInstrument("com.app.model.User", emptyList(), listOf("com.app.model")))
        assertTrue(PackageFilter.shouldInstrument("com.app.net.Api", emptyList(), listOf("com.app.model")))
        // 黑名单优先于白名单（重叠时 exclude 胜）
        assertFalse(
            PackageFilter.shouldInstrument("com.app.model.User", listOf("com.app"), listOf("com.app.model")),
            "include∩exclude 重叠时 exclude 优先"
        )
    }

    // ============================== 映射并发安全 ==============================

    @Test
    @DisplayName("MappingWriter 多线程并发追加不丢行不错乱")
    fun mappingWriterConcurrent() {
        val file = Files.createTempFile("sf-map-conc", ".txt").toFile()
        val writer = MappingWriter(file)
        val threads = 8
        val perThread = 50
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        for (t in 0 until threads) {
            pool.submit {
                try {
                    for (i in 0 until perThread) {
                        writer.record("com.app.C$t", "m$i", "plain-$t-$i", "cipher-$t-$i", "xor")
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS), "并发写应在超时内完成")
        pool.shutdown()
        val lines = file.readLines().filter { it.isNotBlank() }
        assertEquals(threads * perThread, lines.size, "并发追加不得丢行")
        // 每行结构完整（含 " -> " 分隔）
        assertTrue(lines.all { it.contains("  ->  ") }, "每行须结构完整")
    }
}

/**
 * 测试用自定义算法：字节取反（~b），构建期加密与运行时解密同算。
 * 顶层 public 类 + public 无参构造，供 FogConfigResolver 反射构造与运行时 register 复用。
 */
class NotFog : IStringFog {
    override fun encrypt(data: ByteArray, key: ByteArray): ByteArray = not(data)
    override fun decrypt(data: ByteArray, key: ByteArray): ByteArray = not(data)
    private fun not(d: ByteArray): ByteArray = ByteArray(d.size) { (d[it].toInt().inv()).toByte() }
}
