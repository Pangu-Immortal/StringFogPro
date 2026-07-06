package com.vapro.vae.stringfog.plugin.core

import java.util.Base64
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * ===============================================================================
 * 功能：StringFog 核心方法访问器（纯 ASM，LDC 字符串字面量加密替换）。
 * 函数简介：拦截 visitLdcInsn，对满足条件的 String 常量：构建期用 IStringFog 加密 →
 *   Base64 编码 → 写入常量池，并插入 INVOKESTATIC StringFogRuntime.decrypt 调用，
 *   使运行时还原明文。加密与运行时解密走同一 IStringFog 契约，算法一致。
 *
 * 两种发射形态（与 StringFogRuntime 两条 decrypt 入口一一对应）：
 *   1. legacy（默认 XOR + DEFAULT_KEY）：
 *        原始 LDC "明文"（push 1 ref）
 *        替换 LDC "密文" + INVOKESTATIC decrypt(String)String（pop1 push1）→ 净 push 1 ref
 *      栈效应与原 LDC 完全一致，COPY_FRAMES 安全，且逐字节兼容 v1.1.0。
 *   2. 可配置（AES / 自定义算法 / 自定义密钥）：
 *        LDC "密文" + LDC "密钥" + LDC "算法id" + INVOKESTATIC decrypt(String,String,String)String
 *      过程栈峰值比原 LDC 高 +2，故在 visitMaxs 补偿 +2 栈深，保证在 COPY_FRAMES（不重算 maxStack）
 *      模式下不越界；净栈效应仍为 push 1 ref，下游帧不变。
 * ===============================================================================
 */
class StringFogMethodVisitor(
    api: Int,
    mv: MethodVisitor,
    private val config: FogConfig
) : MethodVisitor(api, mv) {

    /** 本方法内是否发生过可配置（三参）插桩——决定是否在 visitMaxs 补偿栈深。 */
    private var multiArgEmitted = false

    override fun visitLdcInsn(value: Any?) {
        if (value is String && shouldFog(value)) {
            // 关键逻辑：构建期加密——明文 UTF-8 字节 → IStringFog.encrypt → Base64 编码
            val cipher = Base64.getEncoder()
                .encodeToString(config.fog.encrypt(value.toByteArray(Charsets.UTF_8), config.keyBytes))
            if (config.legacy) {
                // 单参形态：栈中性，兼容 v1.1.0
                super.visitLdcInsn(cipher)
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    RUNTIME_OWNER,
                    "decrypt",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    false
                )
            } else {
                // 三参形态：密文 + 密钥字面量 + 算法 id
                super.visitLdcInsn(cipher)
                super.visitLdcInsn(config.keyLiteral)
                super.visitLdcInsn(config.algorithmId)
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    RUNTIME_OWNER,
                    "decrypt",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                    false
                )
                multiArgEmitted = true
            }
        } else {
            // 非字符串或不满足加密条件（空串/短于阈值/shouldFog=false）：原样透传
            super.visitLdcInsn(value)
        }
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        // 关键逻辑：三参插桩过程栈峰值 +2；COPY_FRAMES 模式不重算 maxStack，故此处显式补偿，防越界
        val bump = if (multiArgEmitted) 2 else 0
        super.visitMaxs(maxStack + bump, maxLocals)
    }

    /** 是否加密：非空 + 满足最小串长阈值 + 算法 shouldFog 判定。 */
    private fun shouldFog(value: String): Boolean {
        return value.isNotEmpty() && value.length >= config.minLength && config.fog.shouldFog(value)
    }

    companion object {
        /** 运行时解密器内部名（INVOKESTATIC 目标 owner）。 */
        private const val RUNTIME_OWNER = "com/vapro/vae/stringfog/StringFogRuntime"
    }
}
