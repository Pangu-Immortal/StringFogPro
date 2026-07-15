package com.vapro.vae.stringfog.plugin.core

import java.util.Base64
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * ===============================================================================
 * 功能：StringFog 核心方法访问器（ASM 树 API，字符串字面量加密替换 + invokedynamic 拼接去糖）。
 * 函数简介：以 {@link MethodNode} 缓冲整段方法体，在 visitEnd 里对指令表做两类改写后 accept 回下游：
 *   1. LDC String 字面量  → 构建期加密密文 + INVOKESTATIC StringFogRuntime.decrypt(...)（运行期还原）。
 *   2. invokedynamic makeConcatWithConstants（Java 9+/Kotlin 的「"文本" + 变量」/字符串模板编译产物）
 *      → 去糖为等价的 StringBuilder 链，其中「字面量片段 /  折叠常量」按加密路径发射、
 *        动态参数按其精确类型 append，逐字节语义等价，只是字面量在运行期解密。
 *
 * 为何用树 API（相较旧版流式 MethodVisitor）：
 *   - 去糖需要把栈上 N 个动态参数「按逆序存入 N 个新局部变量」，再按 recipe 顺序取用。
 *     安全分配新局部槽必须知道方法真实 maxLocals——MethodNode 在 visitMaxs 后即持有该值，
 *     可取 maxLocals 作为新槽基址，杜绝踩踏原有活跃局部（流式前向单遍无法预知后续最大槽位）。
 *   - 指令表可读、可精准插桩，异常表/行号/局部变量表/注解由 MethodNode.accept 原样回放，不丢失。
 *
 * 帧与栈深：本器不再手工补偿 maxStack。去糖改变了指令数并新增局部，原 StackMapTable 需重算，
 *   故插件对被插桩类请求 COMPUTE_FRAMES（AGP 侧类路径感知 ClassWriter 重算帧 + maxStack/maxLocals）；
 *   单元测试用 ClassWriter(COMPUTE_MAXS) 重算（直线方法无分支、无需帧）。见 StringFogPlugin 帧模式说明。
 *
 * 拼接去糖的语义等价要点（逐字节一致）：
 *   - recipe 逐字符解析：''(TAG_ARG)=取下一个动态参数；''(TAG_CONST)=取下一个 bootstrap 常量；
 *     其余字符=结果字面量文本。连续「字面量字符 + 折叠常量」合并为一个字面量块（块间以动态参数分界）。
 *   - bootstrap 常量在构建期折叠为 String.valueOf(常量)（编译期常量拼接结果确定，折叠逐字节等价）；
 *     遇到无法安全折叠的常量类型（Type/Handle/ConstantDynamic 等，String 拼接不会出现）→ 整条放弃去糖（原样保留）。
 *   - 动态参数按 indy 描述符里的精确类型选 append 重载：boolean→(Z) char→(C) byte/short/int→(I)
 *     long→(J) float→(F) double→(D)；String→(Ljava/lang/String;)；其余引用/数组→(Ljava/lang/Object;)
 *     （StringBuilder.append(Object) 内部即 String.valueOf(Object)，与 String 拼接对引用操作数的语义一致）。
 *   - 仅当某字面量块达到加密门槛（≥minLength 且 shouldFog）才去糖；否则整条 indy 原样保留（零改动零风险）。
 *
 * 边界防护：空串 / 短于 minLength / 超 maxUtf8Bytes 的串跳过加密、原样保留；抽象/native 无方法体不缓冲。
 * ===============================================================================
 */
class StringFogMethodVisitor(
    api: Int,
    access: Int,
    name: String?,
    descriptor: String?,
    signature: String?,
    exceptions: Array<String?>?,
    /** 下游方法访问器（通常为 ClassWriter 的 MethodWriter），visitEnd 里 accept 回放改写后的方法体。 */
    private val nextVisitor: MethodVisitor?,
    private val config: FogConfig,
    private val className: String
) : MethodNode(api, access, name, descriptor, signature, exceptions) {

    /** 当前方法名（供映射记录定位）；缺省 "?" 防空。 */
    private val methodName: String = name ?: "?"

    /**
     * 方法体收集完毕：先改写指令表（LDC 加密 + invokedynamic 去糖），再 accept 回放至下游。
     * 关键逻辑：改写异常时仍保证 accept 回放（try/finally），避免半改写产出损坏类；下游为空则不回放。
     */
    override fun visitEnd() {
        try {
            transformLdcConstants()
            transformStringConcatIndy()
        } finally {
            nextVisitor?.let { accept(it) }
        }
    }

    // ============================== LDC 字面量加密 ==============================

    /** 遍历指令表，把满足条件的 LDC String 常量替换为「密文 + decrypt」序列（净栈效应不变：push 1 ref）。 */
    private fun transformLdcConstants() {
        // 先快照再改写：避免在 InsnList 迭代中增删导致迭代器错乱
        for (insn in instructions.toArray()) {
            if (insn is LdcInsnNode && insn.cst is String) {
                val value = insn.cst as String
                val encrypted = buildEncryptInsns(value) ?: continue
                instructions.insertBefore(insn, encrypted)
                instructions.remove(insn)
            }
        }
    }

    // ============================== invokedynamic 拼接去糖 ==============================

    /** 遍历指令表，把 StringConcatFactory.makeConcatWithConstants 的 invokedynamic 去糖为 StringBuilder 链。 */
    private fun transformStringConcatIndy() {
        for (insn in instructions.toArray()) {
            if (insn is InvokeDynamicInsnNode && isMakeConcatWithConstants(insn)) {
                val desugared = buildConcatDesugar(insn) ?: continue
                instructions.insertBefore(insn, desugared)
                instructions.remove(insn)
            }
        }
    }

    /** 判定 invokedynamic 是否为 StringConcatFactory.makeConcatWithConstants（recipe + 常量携带字面量的形态）。 */
    private fun isMakeConcatWithConstants(indy: InvokeDynamicInsnNode): Boolean {
        val bsm = indy.bsm ?: return false
        return bsm.owner == STRING_CONCAT_FACTORY && bsm.name == MAKE_CONCAT_WITH_CONSTANTS
    }

    /**
     * 构造某条 makeConcatWithConstants 的等价 StringBuilder 去糖序列（净栈效应：pop N 参数 → push 1 String）。
     * 返回 null 表示不去糖（无可加密字面量 / recipe 与描述符不匹配 / 常量不可安全折叠）——此时原 indy 原样保留。
     *
     * 关键逻辑：
     *   1. 解析 recipe 为有序块（字面量块 / 动态参数块）；'' 常量构建期折叠进相邻字面量块。
     *   2. 无任何字面量块达到加密门槛 → 放弃去糖（该 indy 本就无值得隐藏的明文）。
     *   3. 以 maxLocals 为基址为 N 个动态参数分配新局部槽（long/double 占 2 槽），逆序 STORE 落栈上参数。
     *   4. new StringBuilder，按块顺序 append：字面量块（达门槛→密文+decrypt；否则原文 LDC）、动态参数块
     *      （按精确类型 LOAD + append 重载）；末尾 toString 收束为结果 String。
     */
    private fun buildConcatDesugar(indy: InvokeDynamicInsnNode): InsnList? {
        val bsmArgs = indy.bsmArgs
        // recipe 必为第 0 个 bootstrap 参数且为 String；否则形态异常，安全放弃
        if (bsmArgs.isEmpty() || bsmArgs[0] !is String) return null
        val recipe = bsmArgs[0] as String
        val argTypes = Type.getArgumentTypes(indy.desc)

        // ---- 1. 解析 recipe 为有序块 ----
        val chunks = ArrayList<ConcatChunk>()
        val literal = StringBuilder()
        var argCursor = 0
        var constCursor = 1 // bsmArgs[1..] 为 '' 折叠常量，按出现顺序消费
        for (ch in recipe) {
            when (ch) {
                TAG_ARG -> {
                    flushLiteral(literal, chunks)
                    if (argCursor >= argTypes.size) return null // recipe 参数数超过描述符，异常放弃
                    chunks.add(ConcatChunk.Arg(argCursor))
                    argCursor++
                }
                TAG_CONST -> {
                    if (constCursor >= bsmArgs.size) return null // 常量数不足，异常放弃
                    val folded = foldConstantOrNull(bsmArgs[constCursor]) ?: return null // 不可安全折叠，放弃
                    literal.append(folded)
                    constCursor++
                }
                else -> literal.append(ch)
            }
        }
        flushLiteral(literal, chunks)

        // recipe 消费的动态参数数必须与描述符参数数完全一致，否则栈平衡被破坏，安全放弃
        if (argCursor != argTypes.size) return null

        // ---- 2. 门槛判定：无可加密字面量块则不去糖 ----
        val hasEncryptable = chunks.any { it is ConcatChunk.Literal && qualifiesForFog(it.text) }
        if (!hasEncryptable) return null

        // ---- 3. 为动态参数分配新局部槽并逆序 STORE ----
        val base = maxLocals
        val slots = IntArray(argTypes.size)
        var slotCursor = base
        for (i in argTypes.indices) {
            slots[i] = slotCursor
            slotCursor += argTypes[i].size // long/double 占 2 槽
        }
        if (slotCursor > maxLocals) maxLocals = slotCursor // 抬高 maxLocals（COMPUTE 帧模式亦会重算，双保险）

        val out = InsnList()
        // 栈顶为最后一个参数，故逆序 STORE：i = N-1 .. 0
        for (i in argTypes.indices.reversed()) {
            out.add(VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), slots[i]))
        }

        // ---- 4. new StringBuilder + 按块 append + toString ----
        out.add(TypeInsnNode(Opcodes.NEW, STRING_BUILDER))
        out.add(InsnNode(Opcodes.DUP))
        out.add(MethodInsnNode(Opcodes.INVOKESPECIAL, STRING_BUILDER, "<init>", "()V", false))
        for (chunk in chunks) {
            when (chunk) {
                is ConcatChunk.Literal -> {
                    val enc = buildEncryptInsns(chunk.text)
                    if (enc != null) {
                        out.add(enc) // 栈顶留 String（decrypt 返回值）
                    } else {
                        out.add(LdcInsnNode(chunk.text)) // 未达门槛/超长：原文 LDC
                    }
                    appendOfType(out, TYPE_STRING) // append(String)
                }
                is ConcatChunk.Arg -> {
                    val t = argTypes[chunk.index]
                    out.add(VarInsnNode(t.getOpcode(Opcodes.ILOAD), slots[chunk.index]))
                    appendOfType(out, t)
                }
            }
        }
        out.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "toString", "()Ljava/lang/String;", false))
        return out
    }

    /** 若字面量缓冲非空，收束为一个字面量块并清空缓冲。 */
    private fun flushLiteral(literal: StringBuilder, chunks: MutableList<ConcatChunk>) {
        if (literal.isNotEmpty()) {
            chunks.add(ConcatChunk.Literal(literal.toString()))
            literal.setLength(0)
        }
    }

    /**
     * 把 bootstrap 常量折叠为其构建期字符串形态（编译期常量参与拼接的结果确定，折叠逐字节等价）。
     * 仅折叠 String 与基本类型包装；其余类型（Type/Handle/ConstantDynamic）返回 null 以触发放弃去糖。
     */
    private fun foldConstantOrNull(c: Any?): String? = when (c) {
        is String -> c
        is Int, is Long, is Float, is Double, is Boolean, is Char, is Short, is Byte -> c.toString()
        else -> null
    }

    /** 为某类型发射 StringBuilder.append 重载（精确匹配基本类型，String 直取，其余引用走 Object 重载）。 */
    private fun appendOfType(out: InsnList, t: Type) {
        val desc = when (t.sort) {
            Type.BOOLEAN -> "(Z)L$STRING_BUILDER;"
            Type.CHAR -> "(C)L$STRING_BUILDER;"
            Type.BYTE, Type.SHORT, Type.INT -> "(I)L$STRING_BUILDER;"
            Type.LONG -> "(J)L$STRING_BUILDER;"
            Type.FLOAT -> "(F)L$STRING_BUILDER;"
            Type.DOUBLE -> "(D)L$STRING_BUILDER;"
            else ->
                if (t.descriptor == "L$STRING_TYPE;") "(L$STRING_TYPE;)L$STRING_BUILDER;"
                else "(L$OBJECT_TYPE;)L$STRING_BUILDER;"
        }
        out.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, STRING_BUILDER, "append", desc, false))
    }

    // ============================== 加密序列构造（LDC 与拼接块共用的单一真相源） ==============================

    /**
     * 把一个明文字符串构造为「留 1 个 String 在栈顶」的加密指令序列（净栈 +1，与原 LDC 一致）。
     * 返回 null 表示该串不加密（空 / 短于 minLength / 超 maxUtf8Bytes / 算法判否）——调用方回退为原文 LDC。
     *
     * 四种发射形态（传输编码 × 密钥形态，与 StringFogRuntime 四条 decrypt 入口一一对应）：
     *   Base64 legacy：LDC 密文 → decrypt(String)
     *   Base64 三参 ：LDC 密文,密钥,算法 → decrypt(String,String,String)
     *   bytes  legacy：<build byte[]> → decrypt(byte[])
     *   bytes  三参 ：<build byte[]>,LDC 密钥,算法 → decrypt(byte[],String,String)
     */
    private fun buildEncryptInsns(value: String): InsnList? {
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
        config.mappingWriter?.record(className, methodName, value, cipherBase64, config.algorithmId)

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

    /** 达到加密门槛（非空 + ≥minLength + 不超 maxUtf8Bytes + 算法判定）——供拼接块门槛预判复用。 */
    private fun qualifiesForFog(value: String): Boolean =
        shouldFog(value, value.toByteArray(Charsets.UTF_8).size)

    /** 是否加密：非空 + 满足 minLength（字符数）+ 不超 maxUtf8Bytes（防越限）+ 算法 shouldFog 判定。 */
    private fun shouldFog(value: String, utf8Size: Int): Boolean {
        if (value.isEmpty()) return false
        if (value.length < config.minLength) return false
        if (utf8Size > config.maxUtf8Bytes) return false
        return config.fog.shouldFog(value)
    }

    /** 拼接块类型：字面量块（合并连续字面量字符 + 折叠常量）或动态参数块（引用 indy 描述符第 index 个参数）。 */
    private sealed class ConcatChunk {
        class Literal(val text: String) : ConcatChunk()
        class Arg(val index: Int) : ConcatChunk()
    }

    companion object {
        /** 运行时解密器内部名（INVOKESTATIC 目标 owner）。 */
        private const val RUNTIME_OWNER = "com/vapro/vae/stringfog/StringFogRuntime"

        /** 解密方法名。 */
        private const val METHOD_DECRYPT = "decrypt"

        /** StringConcatFactory 内部名（invokedynamic bootstrap owner）。 */
        private const val STRING_CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory"

        /** 拼接 bootstrap 方法名（携带 recipe + 常量的形态）。 */
        private const val MAKE_CONCAT_WITH_CONSTANTS = "makeConcatWithConstants"

        /** StringBuilder 内部名（去糖目标）。 */
        private const val STRING_BUILDER = "java/lang/StringBuilder"

        /** String / Object 内部名（append 重载选择）。 */
        private const val STRING_TYPE = "java/lang/String"
        private const val OBJECT_TYPE = "java/lang/Object"

        /** recipe 标记位：动态参数 / bootstrap 常量（其余字符为字面量文本）。 */
        private const val TAG_ARG = ''
        private const val TAG_CONST = ''

        /** decrypt(String)String —— Base64 legacy 单参。 */
        private const val DESC_1ARG_STRING = "(Ljava/lang/String;)Ljava/lang/String;"

        /** decrypt(String,String,String)String —— Base64 三参。 */
        private const val DESC_3ARG_STRING =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"

        /** decrypt(byte[])String —— bytes legacy 单参。 */
        private const val DESC_1ARG_BYTES = "([B)Ljava/lang/String;"

        /** decrypt(byte[],String,String)String —— bytes 三参。 */
        private const val DESC_3ARG_BYTES = "([BLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;"

        /** String 类型（append(String) 场景复用）。 */
        private val TYPE_STRING: Type = Type.getObjectType(STRING_TYPE)
    }
}
