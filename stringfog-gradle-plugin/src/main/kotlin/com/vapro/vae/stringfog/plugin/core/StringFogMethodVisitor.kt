package com.vapro.vae.stringfog.plugin.core

import java.util.Base64
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * ===============================================================================
 * 功能：StringFog 核心方法访问器（纯 ASM，LDC 字符串字面量加密替换）。
 * 函数简介：拦截 visitLdcInsn，对满足条件的 String 常量：构建期用 IStringFog 加密 →
 *   （Base64 文本 或 原始 byte[]）写入方法体，并插入 INVOKESTATIC StringFogRuntime.decrypt，
 *   使运行时还原明文。加密与运行时解密走同一 IStringFog 契约，算法一致。
 *
 * 四种发射形态（传输编码 × 密钥形态，与 StringFogRuntime 四条 decrypt 入口一一对应）：
 *   ┌────────────┬─────────────────────────────────┬──────────────────────────────────────────────┐
 *   │            │ legacy 单参（默认 XOR+DEFAULT_KEY）│ 三参（AES/自定义/自定义密钥/每串密钥）          │
 *   ├────────────┼─────────────────────────────────┼──────────────────────────────────────────────┤
 *   │ Base64 文本 │ LDC 密文;INVOKE decrypt(String)   │ LDC 密文,密钥,算法;INVOKE decrypt(3×String)     │
 *   │ 原始 bytes  │ <build byte[]>;INVOKE decrypt([B) │ <build byte[]>,LDC 密钥,算法;INVOKE decrypt([B..)│
 *   └────────────┴─────────────────────────────────┴──────────────────────────────────────────────┘
 *   每种形态净栈效应均为 push 1 ref（与原 LDC 一致），故 COPY_FRAMES 下 StackMapTable 不变；
 *   仅操作数栈峰值升高，由 visitMaxs 按各形态的额外栈深精确补偿（见 EXTRA_* 常量）。
 *
 * 边界防护（防误伤 / 防生成非法 class）：
 *   1. 空串 / 短于 minLength / 超过 maxUtf8Bytes 的串——跳过加密，原样透传（保证 class 合法、语义不变）。
 *   2. 非常量拼接不误伤——本器只改 LDC String 常量（编译期真常量，加密后 decrypt 返回同值，拼接照常）；
 *      invokedynamic makeConcatWithConstants 的常量在 BSM 引导参数中，非 LDC，本器不触碰，故无误伤。
 *   3. 不重复加密——单趟插桩内 super.visitLdcInsn 直接写下游，不回环本器；运行时解密器类由上层
 *      isInstrumentable 排除，AGP 每次插桩的是全新明文编译产物，不会累积二次加密。
 * ===============================================================================
 */
class StringFogMethodVisitor(
    api: Int,
    mv: MethodVisitor,
    private val config: FogConfig,
    private val className: String,
    private val methodName: String
) : MethodVisitor(api, mv) {

    /** 本方法内所有插桩形态所需的最大额外栈深——用于 visitMaxs 一次性补偿。 */
    private var maxExtraStack = 0

    override fun visitLdcInsn(value: Any?) {
        if (value !is String) {
            super.visitLdcInsn(value)
            return
        }
        val utf8 = value.toByteArray(Charsets.UTF_8)
        if (!shouldFog(value, utf8.size)) {
            // 空串 / 短串 / 超长串 / 算法判否：原样透传
            super.visitLdcInsn(value)
            return
        }

        // 逐串确定密钥：每串密钥模式下由生成器产出该串专属密钥；否则用固定密钥
        val keyGen = config.keyGenerator
        val keyLiteral: String?
        val keyBytes: ByteArray
        if (keyGen != null) {
            val generated = keyGen.generateKey(value)
            keyLiteral = generated
            keyBytes = generated.toByteArray(Charsets.UTF_8)
        } else {
            keyLiteral = config.keyLiteral
            keyBytes = config.keyBytes
        }

        // 构建期加密：明文 UTF-8 → IStringFog.encrypt → 密文字节
        val cipherBytes = config.fog.encrypt(utf8, keyBytes)
        val cipherBase64 = Base64.getEncoder().encodeToString(cipherBytes)

        // 记录映射（审计/排查）：密文统一以 Base64 呈现，便于人读，与 bytes 模式无关
        config.mappingWriter?.record(className, methodName, value, cipherBase64, config.algorithmId)

        if (config.bytesMode) {
            emitBytesMode(cipherBytes, keyLiteral)
        } else {
            emitBase64Mode(cipherBase64, keyLiteral)
        }
    }

    /** Base64 文本形态发射（legacy 单参 或 三参）。 */
    private fun emitBase64Mode(cipherBase64: String, keyLiteral: String?) {
        super.visitLdcInsn(cipherBase64)
        if (config.legacy) {
            invokeDecrypt(DESC_1ARG_STRING)
            trackExtra(EXTRA_BASE64_LEGACY)
        } else {
            super.visitLdcInsn(keyLiteral)
            super.visitLdcInsn(config.algorithmId)
            invokeDecrypt(DESC_3ARG_STRING)
            trackExtra(EXTRA_BASE64_MULTI)
        }
    }

    /** bytes 形态发射（原始 byte[] 字面量 + legacy 单参 或 三参），免 Base64。 */
    private fun emitBytesMode(cipherBytes: ByteArray, keyLiteral: String?) {
        emitByteArrayLiteral(cipherBytes)
        if (config.legacy) {
            invokeDecrypt(DESC_1ARG_BYTES)
            trackExtra(EXTRA_BYTES_LEGACY)
        } else {
            super.visitLdcInsn(keyLiteral)
            super.visitLdcInsn(config.algorithmId)
            invokeDecrypt(DESC_3ARG_BYTES)
            trackExtra(EXTRA_BYTES_MULTI)
        }
    }

    /** 发射 INVOKESTATIC StringFogRuntime.decrypt(<desc>)。 */
    private fun invokeDecrypt(descriptor: String) {
        super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_OWNER, METHOD_DECRYPT, descriptor, false)
    }

    /**
     * 以字节码构造一个 byte[] 字面量并留在栈顶（bytes 模式）。
     * 关键逻辑：pushLen → NEWARRAY byte → 逐元素 (DUP, pushIndex, pushByte, BASTORE)；
     *   构造期操作数栈峰值 = 基线 +4（arrayref,arrayref,index,value），完成后净 +1（arrayref）。
     */
    private fun emitByteArrayLiteral(bytes: ByteArray) {
        pushInt(bytes.size)
        super.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE)
        for (i in bytes.indices) {
            super.visitInsn(Opcodes.DUP)
            pushInt(i)
            // bytes[i] 为 -128..127（Byte），BIPUSH 直取；BASTORE 截断为 byte，位一致
            super.visitIntInsn(Opcodes.BIPUSH, bytes[i].toInt())
            super.visitInsn(Opcodes.BASTORE)
        }
    }

    /** 压入一个 int 常量，按值域选最短指令（ICONST/BIPUSH/SIPUSH/LDC）。 */
    private fun pushInt(v: Int) {
        when {
            v == -1 -> super.visitInsn(Opcodes.ICONST_M1)
            v in 0..5 -> super.visitInsn(Opcodes.ICONST_0 + v)
            v in Byte.MIN_VALUE.toInt()..Byte.MAX_VALUE.toInt() -> super.visitIntInsn(Opcodes.BIPUSH, v)
            v in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt() -> super.visitIntInsn(Opcodes.SIPUSH, v)
            else -> super.visitLdcInsn(v)
        }
    }

    /** 累计本方法所需最大额外栈深。 */
    private fun trackExtra(extra: Int) {
        if (extra > maxExtraStack) maxExtraStack = extra
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        // 关键逻辑：COPY_FRAMES 不重算 maxStack，故按本方法出现过的最大额外栈深显式补偿，防越界
        super.visitMaxs(maxStack + maxExtraStack, maxLocals)
    }

    /** 是否加密：非空 + 满足 minLength（字符数）+ 不超 maxUtf8Bytes（防越限）+ 算法 shouldFog 判定。 */
    private fun shouldFog(value: String, utf8Size: Int): Boolean {
        if (value.isEmpty()) return false
        if (value.length < config.minLength) return false
        if (utf8Size > config.maxUtf8Bytes) return false
        return config.fog.shouldFog(value)
    }

    companion object {
        /** 运行时解密器内部名（INVOKESTATIC 目标 owner）。 */
        private const val RUNTIME_OWNER = "com/vapro/vae/stringfog/StringFogRuntime"

        /** 解密方法名。 */
        private const val METHOD_DECRYPT = "decrypt"

        /** decrypt(String)String —— Base64 legacy 单参。 */
        private const val DESC_1ARG_STRING = "(Ljava/lang/String;)Ljava/lang/String;"

        /** decrypt(String,String,String)String —— Base64 三参。 */
        private const val DESC_3ARG_STRING =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"

        /** decrypt(byte[])String —— bytes legacy 单参。 */
        private const val DESC_1ARG_BYTES = "([B)Ljava/lang/String;"

        /** decrypt(byte[],String,String)String —— bytes 三参。 */
        private const val DESC_3ARG_BYTES = "([BLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;"

        // 各形态相对原 LDC（峰值 +1）的额外栈深（详见类头栈分析）：
        /** Base64 legacy：LDC 密文→INVOKE，峰值 +1，额外 0。 */
        private const val EXTRA_BASE64_LEGACY = 0
        /** Base64 三参：LDC 密文,密钥,算法（峰值 +3），额外 2。 */
        private const val EXTRA_BASE64_MULTI = 2
        /** bytes legacy：byte[] 构造峰值 +4，额外 3。 */
        private const val EXTRA_BYTES_LEGACY = 3
        /** bytes 三参：byte[] 构造峰值 +4 主导（>三参 +3），额外 3。 */
        private const val EXTRA_BYTES_MULTI = 3
    }
}
