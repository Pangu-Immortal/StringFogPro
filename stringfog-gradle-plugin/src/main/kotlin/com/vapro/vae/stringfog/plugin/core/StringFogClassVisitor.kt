package com.vapro.vae.stringfog.plugin.core

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * ===============================================================================
 * 功能：StringFog 核心类访问器（纯 ASM，不依赖 AGP，可独立单元测试）。
 * 函数简介：拦截每个方法的 visitMethod，包装出 StringFogMethodVisitor，对方法体内
 *   LDC 字符串字面量做加密替换。类级过滤（包名 include/exclude、排除运行时自身）
 *   由上层 AGP 工厂 isInstrumentable 负责，本类只专注字节码改写，便于脱离 AGP 复用。
 *
 * 设计要点：ASM9 API；下游 visitMethod 返回 null（抽象/无方法体）时透传，避免 NPE。
 * ===============================================================================
 */
class StringFogClassVisitor(
    api: Int,
    next: ClassVisitor?,
    private val config: FogConfig
) : ClassVisitor(api, next) {

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String?>?
    ): MethodVisitor? {
        // 关键逻辑：下游若返回 null（抽象/native 无方法体）则直接透传，避免 NPE
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
        return StringFogMethodVisitor(api, mv, config)
    }

    companion object {
        /** 默认 ASM API 级别（ASM9，支持到 Java 21+ class 文件）。 */
        const val ASM_API: Int = Opcodes.ASM9
    }
}
