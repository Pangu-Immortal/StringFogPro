package com.vapro.vae.stringfog.plugin.core

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * ===============================================================================
 * 功能：StringFog 核心类访问器（纯 ASM，不依赖 AGP，可独立单元测试）。
 * 函数简介：在 visit 捕获类名，在 visitMethod 包装出 StringFogMethodVisitor，对方法体内
 *   LDC 字符串字面量做加密替换、并对 makeConcatWithConstants 的 invokedynamic 拼接去糖。
 *   类级过滤（包名 include/exclude、排除运行时自身）由上层 AGP 工厂 isInstrumentable 负责，
 *   本类只专注字节码改写，便于脱离 AGP 复用。
 *
 * 设计要点：ASM9 API；下游 visitMethod 返回 null（抽象/无方法体）时透传，避免 NPE。
 *   访问标志/描述符/异常表向下传给 StringFogMethodVisitor（树 API），去糖分配新局部需依据
 *   方法真实 maxLocals（MethodNode 持有）；类名/方法名供映射输出（MappingWriter）定位加密位置。
 * ===============================================================================
 */
class StringFogClassVisitor(
    api: Int,
    next: ClassVisitor?,
    private val config: FogConfig
) : ClassVisitor(api, next) {

    /** 当前类的全限定名（点分），由 visit 捕获，供映射记录定位；缺省 "?" 防空。 */
    private var className: String = "?"

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String?>?
    ) {
        // 关键逻辑：ASM 内部名以 '/' 分隔，转为人读的点分类名
        if (name != null) {
            className = name.replace('/', '.')
        }
        super.visit(version, access, name, signature, superName, interfaces)
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
        return StringFogMethodVisitor(
            api, access, name, descriptor, signature, declaredExceptions, mv, config, className
        )
    }

    companion object {
        /** 默认 ASM API 级别（ASM9，支持到 Java 21+ class 文件）。 */
        const val ASM_API: Int = Opcodes.ASM9
    }
}
