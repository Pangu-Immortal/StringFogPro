package com.vapro.vae.stringfog.plugin.core

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList

/**
 * ===============================================================================
 * 功能：StringFog 核心类访问器（纯 ASM，不依赖 AGP，可独立单元测试）。
 * 函数简介：三条改写线协同——
 *   1. visitMethod  → 包装 StringFogMethodVisitor：方法体内 LDC 字面量加密 + makeConcatWithConstants
 *      的 invokedynamic 拼接去糖（触达 recipe/bootstrap 常量里的明文）。
 *   2. visitField   → 字段 ConstantValue 加密（v2.3.0 新增）：对「static + String 类型 + 带 String
 *      ConstantValue 属性、且达加密门槛」的常量字段，剥离其明文 ConstantValue 属性（字段变普通
 *      static [final] 无初值），并把「密文 + decrypt + PUTSTATIC」序列累积到 <clinit> 注入队列。
 *      根治 const val（Kotlin）/ static final String（Java）编译成字段 ConstantValue 后，明文以常量
 *      形态残留于 .class/AAR/dex 常量池——旧版只改方法体 LDC/indy，覆盖不到字段属性的盲点。
 *   3. visitEnd     → 若累积了字段初始化序列但类里没有 <clinit>，合成一个 static <clinit> 承载它们；
 *      若已有 <clinit>，则序列在其两类改写之后插到方法体最前（见 StringFogMethodVisitor.prependInsns）。
 *
 * 类级过滤（包名 include/exclude、排除运行时自身）由上层 AGP 工厂 isInstrumentable 负责，本类只专注
 *   字节码改写，便于脱离 AGP 复用。
 *
 * 设计要点：ASM9 API；下游 visitMethod 返回 null（抽象/无方法体）时透传，避免 NPE。字段/方法在 class
 *   文件里「字段全部在前、方法全部在后」，故 visitMethod(<clinit>) 时字段已全部访问完，注入队列已就绪。
 *   PUTSTATIC 对 final static 字段仅在本类 <clinit> 内合法——注入位置正是本类 <clinit>，语义与验证器均满足。
 * ===============================================================================
 */
class StringFogClassVisitor(
    api: Int,
    next: ClassVisitor?,
    config: FogConfig
) : ClassVisitor(api, next) {

    /** 当前类的全限定名（点分），由 visit 捕获，供映射记录定位；缺省 "?" 防空。 */
    private var className: String = "?"

    /** 当前类的内部名（斜杠分隔），由 visit 捕获，供 PUTSTATIC owner 使用；缺省 "?" 防空。 */
    private var ownerInternalName: String = "?"

    /** 加密指令发射器（LDC 加密 / 拼接去糖块 / 字段 ConstantValue 注入共用的单一真相源）。 */
    private val emitter: FogInsnEmitter = FogInsnEmitter(config)

    /**
     * 字段 ConstantValue 加密的 <clinit> 注入队列：按字段访问顺序累积
     * 「密文+decrypt（emitter 发射）+ PUTSTATIC」序列。visitMethod(<clinit>) 消费它（后插到方法体最前），
     * 或 visitEnd 在无 <clinit> 时用它合成 <clinit>。二者互斥，队列恰被消费一次。
     */
    private val fieldInitInsns: InsnList = InsnList()

    /** 是否已访问到类自有的 <clinit>（决定 visitEnd 是否需要合成 <clinit> 承载字段初始化）。 */
    private var clinitVisited: Boolean = false

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String?>?
    ) {
        // 关键逻辑：ASM 内部名以 '/' 分隔；保留斜杠形态供 PUTSTATIC owner，另转点分类名供映射记录人读。
        if (name != null) {
            ownerInternalName = name
            className = name.replace('/', '.')
        }
        super.visit(version, access, name, signature, superName, interfaces)
    }

    /**
     * 字段访问：对「static + String 类型 + 带 String ConstantValue」的常量字段做 ConstantValue 加密。
     * 关键逻辑（剥离与可重建的原子一致性）：先用 emitter 构造运行期还原序列，仅当其非 null（达加密门槛）
     *   才剥离 ConstantValue——保证「已剥离的字段必有对应 <clinit> 重建序列」，杜绝剥离后无法重建导致字段
     *   运行期取到 null。未达门槛 / 非常量 / 非 static / 非 String 的字段：原样透传（含原 ConstantValue）。
     * @param value 字段的 ConstantValue（有该属性时非 null；String 常量字段即为明文字符串）
     */
    override fun visitField(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        value: Any?
    ): FieldVisitor? {
        if (name != null &&
            descriptor == STRING_FIELD_DESC &&
            value is String &&
            access and Opcodes.ACC_STATIC != 0
        ) {
            val enc = emitter.buildEncryptInsns(value, className, name)
            if (enc != null) {
                // enc 的节点被移入注入队列（一次性消费），随后追加对应字段的 PUTSTATIC
                fieldInitInsns.add(enc)
                fieldInitInsns.add(FieldInsnNode(Opcodes.PUTSTATIC, ownerInternalName, name, descriptor))
                // 透传原字段但抹掉 ConstantValue（value=null）：字段成为无初值 static [final]，明文属性彻底消失
                return super.visitField(access, name, descriptor, signature, null)
            }
        }
        return super.visitField(access, name, descriptor, signature, value)
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String?>?
    ): MethodVisitor? {
        // 关键逻辑：下游若返回 null（抽象/native 无方法体）则直接透传，避免 NPE
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
        // 抽象/native 方法无 Code 属性、无字面量可插桩：直接透传下游，不进树 API 缓冲（减小改写面）
        if (access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) return mv
        // ClassVisitor 的 Array<out String?>? → MethodNode 需要的 Array<String?>?（内部名非空，安全转型）
        val declaredExceptions: Array<String?>? = exceptions?.let { arrayOfNulls<String>(it.size).apply { it.copyInto(this) } }
        // <clinit>：既走方法体 LDC/indy 改写，又承载字段 ConstantValue 注入序列（在两类改写之后插到最前）
        val isClinit = name == CLINIT_NAME
        if (isClinit) clinitVisited = true
        return StringFogMethodVisitor(
            api, access, name, descriptor, signature, declaredExceptions, mv, emitter, className,
            if (isClinit) fieldInitInsns else null
        )
    }

    /**
     * 类访问收尾：若累积了字段初始化序列却没有类自有的 <clinit>（fieldInitInsns 未被任何 <clinit> 消费），
     *   合成一个 static <clinit> 承载这些「密文+decrypt+PUTSTATIC」序列。
     * 关键逻辑：合成序列直接回放到下游 ClassWriter（不经 StringFogMethodVisitor），故其内已加密的密文 LDC
     *   不会被二次加密；直线、无分支，maxStack/maxLocals 由 ClassWriter（COMPUTE_MAXS/COMPUTE_FRAMES）重算。
     */
    override fun visitEnd() {
        if (!clinitVisited && fieldInitInsns.size() > 0) {
            synthesizeClinit()
        }
        super.visitEnd()
    }

    /** 合成一个仅承载字段初始化序列的 static <clinit>（无既有 <clinit> 时）。 */
    private fun synthesizeClinit() {
        val mv = super.visitMethod(Opcodes.ACC_STATIC, CLINIT_NAME, "()V", null, null) ?: return
        mv.visitCode()
        fieldInitInsns.accept(mv) // 回放注入序列（密文+decrypt+PUTSTATIC），不经二次加密
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0) // 由 ClassWriter 重算真实栈深/局部数
        mv.visitEnd()
    }

    companion object {
        /** 默认 ASM API 级别（ASM9，支持到 Java 21+ class 文件）。 */
        const val ASM_API: Int = Opcodes.ASM9

        /** 静态初始化方法名（字段 ConstantValue 注入目标）。 */
        private const val CLINIT_NAME = "<clinit>"

        /** String 字段描述符（仅对 String 类型常量字段做 ConstantValue 加密）。 */
        private const val STRING_FIELD_DESC = "Ljava/lang/String;"
    }
}
