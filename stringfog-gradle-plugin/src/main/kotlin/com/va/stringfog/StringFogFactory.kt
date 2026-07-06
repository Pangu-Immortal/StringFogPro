package com.va.stringfog

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * ===============================================================================
 * 功能：StringFog ASM 工厂 + 类/方法访问器。
 * 函数简介：构建期扫描 release 类文件，将 LDC 加载的字符串字面量替换为
 *   「加密串 + INVOKESTATIC StringFogRuntime.decrypt」，实现字符串混淆加密。
 *
 * 关键约束（修改前必读）：
 *   1. KEY 必须与 lib/src/main/java/com/vapro/vae/stringfog/StringFogRuntime.java 的 KEY 完全一致。
 *   2. isInstrumentable 排除：NativeEngine/NativeMethods（JNI 签名不可插桩）、
 *      mirror.*（反射字段/方法名需保持字面量）、StringFogRuntime 自身（防 decrypt 递归）。
 *   3. 栈语义不变（原 LDC push 1 ref → LDC+INVOKESTATIC 净 push 1 ref），故可用 COPY_FRAMES，
 *      规避 VA hidden-API 类型在 COMPUTE_FRAMES 下不可解析的风险。
 * ===============================================================================
 */

/**
 * AGP AsmClassVisitorFactory 工厂实现。
 * abstract：AGP ObjectFactory 需装饰注入 parameters / instrumentationContext 两个抽象属性。
 */
abstract class StringFogFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {

    /** 创建类访问器：包装下游 ClassVisitor，拦截每个方法。 */
    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor = StringFogClassVisitor(nextClassVisitor)

    /**
     * 类过滤：返回 true 才插桩。
     * 关键逻辑：按全限定名（点形式）排除 JNI 类、反射 mirror 包、解密运行时自身。
     */
    override fun isInstrumentable(classData: ClassData): Boolean {
        val n = classData.className
        return !(
            n.startsWith("com.vapro.vae.client.NativeEngine") ||
                n.startsWith("com.vapro.vae.client.natives.NativeMethods") ||
                n.startsWith("mirror.") ||
                n.startsWith("com.vapro.vae.stringfog.StringFogRuntime")
        )
    }
}

/** 类级访问器：拦截每个方法的 visitMethod，包装出 StringFogMethodVisitor。 */
private class StringFogClassVisitor(cv: ClassVisitor) : ClassVisitor(Opcodes.ASM9, cv) {
    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String?>?
    ): MethodVisitor? {
        // 关键逻辑：下游若返回 null（抽象/无方法体等场景）则直接透传，避免 NPE
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
        return StringFogMethodVisitor(mv)
    }
}

/**
 * 方法级访问器：拦截 LDC 字符串字面量。
 * 原始：LDC "明文"（push 1 ref）
 * 替换：LDC "密文" + INVOKESTATIC StringFogRuntime.decrypt (pop 1, push 1) → 净 push 1 ref
 */
private class StringFogMethodVisitor(mv: MethodVisitor) : MethodVisitor(Opcodes.ASM9, mv) {

    override fun visitLdcInsn(value: Any?) {
        if (value is String && value.isNotEmpty()) {
            // 关键逻辑：空串不加密（无意义且避免常量池膨胀）
            super.visitLdcInsn(encrypt(value))
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "com/vapro/vae/stringfog/StringFogRuntime",
                "decrypt",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false
            )
        } else {
            // 非字符串（int/float/long/double/class 常量）原样透传
            super.visitLdcInsn(value)
        }
    }

    /** XOR + Base64 加密（构建期）。KEY 必须与 StringFogRuntime.java 同步。 */
    private fun encrypt(plain: String): String {
        val raw = plain.toByteArray(Charsets.UTF_8)
        val out = ByteArray(raw.size)
        var k = 0
        for (i in raw.indices) {
            out[i] = ((raw[i].toInt() and 0xFF) xor (KEY[k].toInt() and 0xFF)).toByte()
            k = (k + 1) % KEY.size
        }
        // 关键逻辑：Base64 编码为纯 ASCII，规避 class 文件 modified-UTF-8 对不可见字符的处理差异
        return java.util.Base64.getEncoder().encodeToString(out)
    }

    companion object {
        // XOR 密钥（16 字节）。必须与 lib/.../stringfog/StringFogRuntime.java 的 KEY 完全一致（三处同步：本文件 + 运行时）。
        private val KEY = byteArrayOf(
            0x7A, 0x39, 0x4E, 0x21, 0x6B, 0x58, 0x33, 0x70,
            0x4B, 0x41, 0x52, 0x6E, 0x5A, 0x71, 0x68, 0x33
        )
    }
}
