package com.vapro.vae.stringfog.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.vapro.vae.stringfog.plugin.core.FogConfig
import com.vapro.vae.stringfog.plugin.core.FogConfigResolver
import com.vapro.vae.stringfog.plugin.core.MappingWriter
import com.vapro.vae.stringfog.plugin.core.PackageFilter
import com.vapro.vae.stringfog.plugin.core.StringFogClassVisitor
import java.io.File
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
     *   全部影响插桩的参数缓存于伴生对象静态表，避免逐类重复构造，且使同配置各类共享同一
     *   MappingWriter（单一映射文件）。
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
        val bytesMode = p.bytesMode.get()
        val randomKeyPerString = p.randomKeyPerString.get()
        val randomKeyLength = p.randomKeyLength.get()
        val mappingPath = p.mappingFilePath.getOrElse("")
        // 缓存键覆盖所有影响插桩产物的参数，避免不同配置命中同一缓存
        val cacheKey = "$algorithm|$key|$minLength|$bytesMode|$randomKeyPerString|$randomKeyLength|$mappingPath"
        return CONFIG_CACHE.getOrPut(cacheKey) {
            val mappingWriter = if (mappingPath.isNotEmpty()) {
                MAPPING_WRITERS.getOrPut(mappingPath) { MappingWriter(File(mappingPath)) }
            } else {
                null
            }
            FogConfigResolver.resolve(
                algorithm = algorithm,
                key = key,
                minLength = minLength,
                bytesMode = bytesMode,
                randomKeyPerString = randomKeyPerString,
                randomKeyLength = randomKeyLength,
                mappingWriter = mappingWriter,
                classLoader = javaClass.classLoader
            )
        }
    }

    /**
     * 类过滤：返回 true 才插桩。委托纯逻辑 {@link PackageFilter}（运行时自身排除 + 黑名单优先 + 白名单收窄），
     * 使过滤规则可脱离 AGP 独立单元测试。
     */
    override fun isInstrumentable(classData: ClassData): Boolean {
        val p = parameters.get()
        return PackageFilter.shouldInstrument(
            className = classData.className,
            fogPackages = p.fogPackages.get(),
            excludePackages = p.excludePackages.get()
        )
    }

    companion object {
        /** FogConfig 静态缓存：跨类、跨工厂实例复用同一配置解析结果（静态不参与实例序列化）。 */
        private val CONFIG_CACHE = ConcurrentHashMap<String, FogConfig>()

        /** MappingWriter 静态缓存：同一映射文件路径复用同一写入器（进程内共享，配合文件锁跨进程安全）。 */
        private val MAPPING_WRITERS = ConcurrentHashMap<String, MappingWriter>()
    }
}
