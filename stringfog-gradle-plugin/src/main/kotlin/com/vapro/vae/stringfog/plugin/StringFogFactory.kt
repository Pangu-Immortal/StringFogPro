package com.vapro.vae.stringfog.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.vapro.vae.stringfog.plugin.core.FogConfig
import com.vapro.vae.stringfog.plugin.core.FogConfigResolver
import com.vapro.vae.stringfog.plugin.core.StringFogClassVisitor
import java.util.concurrent.ConcurrentHashMap
import org.objectweb.asm.ClassVisitor

/**
 * ===============================================================================
 * 功能：StringFog AGP 插桩工厂（DSL 参数 → 核心 ASM 访问器的适配层）。
 * 函数简介：AGP 对每个待插桩类调用本工厂；工厂按 StringFogParams 解析 FogConfig，
 *   过滤不该加密的类（包白/黑名单 + 排除运行时自身防递归），再委托核心 StringFogClassVisitor。
 *
 * 分层：本类只做 AGP 适配（读参数、类过滤、注入 ASM api 版本）；真正的字节码改写在
 *   com.vapro.vae.stringfog.plugin.core 纯 ASM 层，二者解耦，核心层可脱离 AGP 独立测试。
 * ===============================================================================
 */
abstract class StringFogFactory : AsmClassVisitorFactory<StringFogParams> {

    /**
     * 创建类访问器：注入 AGP 提供的 ASM api 版本，委托核心访问器执行 LDC 加密。
     * 关键逻辑：FogConfig 在此按需解析（不作为实例字段），避免非序列化状态破坏 AGP worker 隔离
     *   （AGP 会序列化工厂实例，实例只应携带 parameters/instrumentationContext）。解析结果按
     *   (algorithm|key|minLength) 缓存于伴生对象静态表，避免逐类重复构造。
     */
    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        val api = instrumentationContext.apiVersion.get()
        return StringFogClassVisitor(api, nextClassVisitor, resolveConfig())
    }

    /** 按参数解析 FogConfig（静态缓存，跨类复用，且不成为可序列化实例状态）。 */
    private fun resolveConfig(): FogConfig {
        val p = parameters.get()
        val algorithm = p.algorithm.get()
        val key = p.key.get()
        val minLength = p.minLength.get()
        val cacheKey = "$algorithm|$key|$minLength"
        return CONFIG_CACHE.getOrPut(cacheKey) {
            FogConfigResolver.resolve(algorithm, key, minLength, javaClass.classLoader)
        }
    }

    /**
     * 类过滤：返回 true 才插桩。
     * 关键逻辑（顺序）：
     *   1. 永远排除 com.vapro.vae.stringfog.*（运行时解密器 + 算法实现），防 decrypt 递归加密。
     *   2. 命中 excludePackages 任一前缀 → 跳过（黑名单优先）。
     *   3. fogPackages 非空且未命中任一前缀 → 跳过（白名单收窄）。
     */
    override fun isInstrumentable(classData: ClassData): Boolean {
        val name = classData.className
        // 1. 排除运行时解密器与内置算法实现自身（防 decrypt 递归加密）。
        //    关键逻辑：按「精确类名」排除，不用包前缀——否则会误伤用户置于同名包下的业务类
        //    （例如演示 com.vapro.vae.stringfog.sample.* 就在运行时同名包命名空间内）。
        if (name in RUNTIME_CLASSES) {
            return false
        }
        val p = parameters.get()
        // 2. 黑名单优先
        val exclude = p.excludePackages.get()
        if (exclude.isNotEmpty() && exclude.any { name.startsWith(it) }) {
            return false
        }
        // 3. 白名单收窄（为空表示全部加密）
        val fog = p.fogPackages.get()
        if (fog.isNotEmpty() && fog.none { name.startsWith(it) }) {
            return false
        }
        return true
    }

    companion object {
        /** FogConfig 静态缓存：跨类、跨工厂实例复用同一配置解析结果（静态不参与实例序列化）。 */
        private val CONFIG_CACHE = ConcurrentHashMap<String, FogConfig>()

        /**
         * 永远排除的运行时/内置算法类（精确类名，防 decrypt 递归加密）。
         * 注：自定义算法实现若置于加密作用域内，请自行用 excludePackages 排除。
         */
        private val RUNTIME_CLASSES = setOf(
            "com.vapro.vae.stringfog.StringFogRuntime",
            "com.vapro.vae.stringfog.IStringFog",
            "com.vapro.vae.stringfog.XorFog",
            "com.vapro.vae.stringfog.AesFog"
        )
    }
}
