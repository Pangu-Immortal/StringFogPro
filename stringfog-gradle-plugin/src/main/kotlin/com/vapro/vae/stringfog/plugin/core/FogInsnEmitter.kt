package com.vapro.vae.stringfog.plugin.core

import java.util.Base64
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * ===============================================================================
 * 功能：StringFog 加密指令序列发射器（三处改写路径共用的单一真相源）。
 * 函数简介：把一个明文字符串构造为「运行期还原、留 1 个 String 在栈顶」的加密指令序列（净栈 +1），
 *   供以下三处复用同一发射逻辑，保证加密形态与运行时 decrypt 入口严格一致：
 *     1. 方法体 LDC 字面量加密（{@link StringFogMethodVisitor#transformLdcConstants}）。
 *     2. invokedynamic makeConcatWithConstants 拼接去糖里的字面量块（同类的 buildConcatDesugar）。
 *     3. 字段 ConstantValue 加密——static final String 常量字段剥离明文 ConstantValue 属性后，
 *        在 <clinit> 里注入「密文 + decrypt + PUTSTATIC」（{@link StringFogClassVisitor}）。
 *
 * 为何独立成类：v2.2.0 之前加密发射内联在 StringFogMethodVisitor，字段 ConstantValue 改写需在
 *   ClassVisitor 层复用同一套四形态发射，故抽出为无状态发射器（仅持有 FogConfig），className/location
 *   逐次传入，杜绝按类构造的生命周期耦合（ClassVisitor 在 visit() 才拿到类名）。
 *
 * 四种发射形态（传输编码 × 密钥形态，与 StringFogRuntime 四条 decrypt 入口一一对应）：
 *   Base64 legacy：LDC 密文 → decrypt(String)
 *   Base64 三参 ：LDC 密文,密钥,算法 → decrypt(String,String,String)
 *   bytes  legacy：<build byte[]> → decrypt(byte[])
 *   bytes  三参 ：<build byte[]>,LDC 密钥,算法 → decrypt(byte[],String,String)
 * ===============================================================================
 */
class FogInsnEmitter(private val config: FogConfig) {

    /**
     * 把一个明文字符串构造为「留 1 个 String 在栈顶」的加密指令序列（净栈 +1，与原 LDC 一致）。
     * 返回 null 表示该串不加密（空 / 短于 minLength / 超 maxUtf8Bytes / 算法判否）——调用方回退为原文。
     *
     * @param value     明文字符串
     * @param className 所在类全限定名（点分，仅供映射记录定位）
     * @param location  改写位置标识（方法名 / "<clinit>" / 字段名，仅供映射记录定位）
     */
    fun buildEncryptInsns(value: String, className: String, location: String): InsnList? {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        if (!shouldFog(value, utf8.size)) return null

        // 逐串确定密钥：每串密钥模式下由生成器产出该串专属密钥；否则用固定密钥
        val keyLiteral: String?
        val keyBytes: ByteArray
        val keyGen = config.keyGenerator
        if (keyGen != null) {
            val generated = keyGen.generateKey(value)
            keyLiteral = generated
            keyBytes = generated.toByteArray(Charsets.UTF_8)
        } else {
            keyLiteral = config.keyLiteral
            keyBytes = config.keyBytes
        }

        // 构建期加密：明文 UTF-8 → IStringFog.encrypt → 密文字节；Base64 仅用于映射人读与 Base64 形态发射
        val cipherBytes = config.fog.encrypt(utf8, keyBytes)
        val cipherBase64 = Base64.getEncoder().encodeToString(cipherBytes)
        config.mappingWriter?.record(className, location, value, cipherBase64, config.algorithmId)

        val out = InsnList()
        if (config.bytesMode) {
            emitByteArrayLiteral(out, cipherBytes)
            if (config.legacy) {
                invokeDecrypt(out, DESC_1ARG_BYTES)
            } else {
                out.add(LdcInsnNode(requireNotNull(keyLiteral) { "三参路径 keyLiteral 不应为空" }))
                out.add(LdcInsnNode(config.algorithmId))
                invokeDecrypt(out, DESC_3ARG_BYTES)
            }
        } else {
            out.add(LdcInsnNode(cipherBase64))
            if (config.legacy) {
                invokeDecrypt(out, DESC_1ARG_STRING)
            } else {
                out.add(LdcInsnNode(requireNotNull(keyLiteral) { "三参路径 keyLiteral 不应为空" }))
                out.add(LdcInsnNode(config.algorithmId))
                invokeDecrypt(out, DESC_3ARG_STRING)
            }
        }
        return out
    }

    /** 达到加密门槛（非空 + ≥minLength + 不超 maxUtf8Bytes + 算法判定）——供拼接块/字段门槛预判复用。 */
    fun qualifiesForFog(value: String): Boolean =
        shouldFog(value, value.toByteArray(Charsets.UTF_8).size)

    /** 发射 INVOKESTATIC StringFogRuntime.decrypt(<desc>)。 */
    private fun invokeDecrypt(out: InsnList, descriptor: String) {
        out.add(MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_OWNER, METHOD_DECRYPT, descriptor, false))
    }

    /**
     * 以字节码构造一个 byte[] 字面量并留在栈顶（bytes 模式）。
     * 关键逻辑：pushLen → NEWARRAY byte → 逐元素 (DUP, pushIndex, BIPUSH byte, BASTORE)；
     *   密文字节为 -128..127（Byte），BIPUSH 直取，BASTORE 截断为 byte，位一致。
     */
    private fun emitByteArrayLiteral(out: InsnList, bytes: ByteArray) {
        pushInt(out, bytes.size)
        out.add(IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE))
        for (i in bytes.indices) {
            out.add(InsnNode(Opcodes.DUP))
            pushInt(out, i)
            out.add(IntInsnNode(Opcodes.BIPUSH, bytes[i].toInt()))
            out.add(InsnNode(Opcodes.BASTORE))
        }
    }

    /** 压入一个 int 常量，按值域选最短指令（ICONST/BIPUSH/SIPUSH/LDC）。 */
    private fun pushInt(out: InsnList, v: Int) {
        when {
            v == -1 -> out.add(InsnNode(Opcodes.ICONST_M1))
            v in 0..5 -> out.add(InsnNode(Opcodes.ICONST_0 + v))
            v in Byte.MIN_VALUE.toInt()..Byte.MAX_VALUE.toInt() -> out.add(IntInsnNode(Opcodes.BIPUSH, v))
            v in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt() -> out.add(IntInsnNode(Opcodes.SIPUSH, v))
            else -> out.add(LdcInsnNode(v))
        }
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
    }
}
