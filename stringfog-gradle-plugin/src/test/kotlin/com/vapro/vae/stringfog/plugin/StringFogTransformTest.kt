package com.vapro.vae.stringfog.plugin

import com.vapro.vae.stringfog.plugin.core.FogConfigResolver
import com.vapro.vae.stringfog.plugin.core.StringFogClassVisitor
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * ===============================================================================
 * 功能：StringFog 核心 ASM 插桩集成测试（脱离 AGP，端到端验证加密-解密往返）。
 * 方法：ASM 生成含明文 String 的类 → 核心访问器插桩 → 断言明文从常量池消失 →
 *   自定义类加载器加载插桩后字节 → 反射调用方法 → 断言返回值 == 原始明文（运行时解密成功）。
 *
 * 覆盖：legacy 单参路径（默认 XOR）、三参路径（AES）、三参路径 maxStack 补偿正确性
 *   （加载不抛 VerifyError 即证明栈深足够）、包过滤 minLength。
 * ===============================================================================
 */
class StringFogTransformTest {

    /** 内存类加载器：加载插桩后的字节，其内 INVOKESTATIC StringFogRuntime 由测试 classpath 提供。 */
    private class BytesClassLoader : ClassLoader(StringFogTransformTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    /**
     * ASM 生成一个类：public static String value() { return PLAINTEXT; }
     * @return 原始字节（含明文常量）
     */
    private fun generateClass(internalName: String, plaintext: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        // 默认构造
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        // public static String value() { return "<plaintext>"; }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(plaintext)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * 用核心访问器插桩。ClassWriter(0)：不自动重算 maxStack，据插桩发射的 visitMaxs 值写入，
     * 从而真实检验 MethodVisitor 对三参路径的 +2 栈深补偿（补偿不足则加载即 VerifyError）。
     */
    private fun transform(original: ByteArray, algorithm: String, key: String): ByteArray {
        val config = FogConfigResolver.resolve(
            algorithm = algorithm,
            key = key,
            minLength = 1,
            classLoader = javaClass.classLoader
        )
        val cr = ClassReader(original)
        val cw = ClassWriter(0)
        cr.accept(StringFogClassVisitor(StringFogClassVisitor.ASM_API, cw, config), 0)
        return cw.toByteArray()
    }

    private fun containsPlaintext(bytes: ByteArray, plaintext: String): Boolean {
        val needle = plaintext.toByteArray(StandardCharsets.UTF_8)
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun invokeValue(internalName: String, bytes: ByteArray): String {
        val clazz = BytesClassLoader().define(internalName.replace('/', '.'), bytes)
        return clazz.getMethod("value").invoke(null) as String
    }

    @Test
    @DisplayName("legacy 单参路径：明文消失 + 运行时还原（默认 XOR）")
    fun legacyXorRoundTrip() {
        val name = "gen/LegacySecret"
        val plaintext = "https://api.example.com/secret?token=PLAINTEXT-XOR-中文"
        val original = generateClass(name, plaintext)
        assertTrue(containsPlaintext(original, plaintext), "原始字节应含明文")

        val fogged = transform(original, "xor", FogConfigResolver.DEFAULT_KEY_SENTINEL)
        assertFalse(containsPlaintext(fogged, plaintext), "插桩后明文必须从常量池消失")
        assertEquals(plaintext, invokeValue(name, fogged), "运行时 decrypt 必须还原明文")
    }

    @Test
    @DisplayName("三参路径：明文消失 + 运行时还原 + maxStack 补偿正确（AES）")
    fun aesRoundTrip() {
        val name = "gen/AesSecret"
        val plaintext = "AES 加密的后端密钥 sk-abcdef-0123456789"
        val original = generateClass(name, plaintext)
        assertTrue(containsPlaintext(original, plaintext))

        val fogged = transform(original, "aes", "my-release-key-2026")
        assertFalse(containsPlaintext(fogged, plaintext), "AES 插桩后明文必须消失")
        // 加载并调用——若 +2 栈深补偿不足会在此抛 VerifyError
        assertEquals(plaintext, invokeValue(name, fogged), "AES 运行时 decrypt 必须还原明文")
    }

    @Test
    @DisplayName("三参路径：自定义密钥 XOR 往返")
    fun xorCustomKeyRoundTrip() {
        val name = "gen/CustomKeySecret"
        val plaintext = "custom key xor 路径明文"
        val original = generateClass(name, plaintext)
        val fogged = transform(original, "xor", "hello-custom-key")
        assertFalse(containsPlaintext(fogged, plaintext))
        assertEquals(plaintext, invokeValue(name, fogged))
    }

    @Test
    @DisplayName("minLength 阈值：短于阈值的串不加密")
    fun minLengthSkips() {
        val name = "gen/ShortSecret"
        val plaintext = "ab"
        val original = generateClass(name, plaintext)
        val config = FogConfigResolver.resolve("xor", FogConfigResolver.DEFAULT_KEY_SENTINEL, 5, javaClass.classLoader)
        val cr = ClassReader(original)
        val cw = ClassWriter(0)
        cr.accept(StringFogClassVisitor(StringFogClassVisitor.ASM_API, cw, config), 0)
        val fogged = cw.toByteArray()
        // 阈值 5，串长 2 → 不加密 → 明文仍在
        assertTrue(containsPlaintext(fogged, plaintext), "短于 minLength 的串应保持明文")
        assertEquals(plaintext, invokeValue(name, fogged))
    }
}
