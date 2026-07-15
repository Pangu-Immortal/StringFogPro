package com.vapro.vae.stringfog.plugin

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.Variant
import com.vapro.vae.stringfog.plugin.core.FogConfigResolver
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * ===============================================================================
 * 功能：StringFog Gradle 插件入口（AGP 9.x AsmClassVisitorFactory / onVariants 自动化集成）。
 * 函数简介：对 com.android.application / com.android.library 工程，创建 stringfog { } DSL 扩展，
 *   在 release 变体上注册 StringFogFactory，对本模块 java/kotlin 编译产物做字符串字面量加密。
 *
 * 关键逻辑：
 *   1. 现代化 API——AsmClassVisitorFactory + Instrumentation + onVariants，非废弃 Transform。
 *   2. 双 withId 注册——同时兼容 application 与 library，且与 android 插件应用顺序无关。
 *   3. release-only——仅 release 变体加密，debug 不插桩，便于开发期调试。
 *   4. kill-switch——DSL enabled=false 或 -Pva.stringfog.enabled=false 关闭。
 *   5. PROJECT 作用域——仅加密本模块源码，不加密依赖（避免二次加密 / 加密 AndroidX）。
 *   6. COMPUTE_FRAMES——invokedynamic 拼接去糖改变指令数并新增局部，帧/maxs 须重算（见 registerStringFog）。
 *   7. 配置校验——注册前校验 minLength/algorithm/include∩exclude 冲突/aes 默认密钥，早失败/早告警。
 * ===============================================================================
 */
class StringFogPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // 创建 DSL 扩展并设默认值
        val ext = project.extensions.create("stringfog", StringFogExtension::class.java)
        ext.enabled.convention(true)
        ext.algorithm.convention(FogConfigResolver.DEFAULT_ALGORITHM)
        ext.key.convention(FogConfigResolver.DEFAULT_KEY_SENTINEL)
        ext.minLength.convention(1)
        ext.bytesMode.convention(false)
        ext.randomKeyPerString.convention(false)
        ext.randomKeyLength.convention(16)
        ext.mappingEnabled.convention(false)

        // 全局命令行 kill-switch（优先级最高，非法值按默认启用处理）
        val cliEnabled = project.findProperty("va.stringfog.enabled")
            ?.toString()
            ?.lowercase()
            ?.toBooleanStrictOrNull()
            ?: true

        // application 模块：注册 release 变体加密
        project.plugins.withId("com.android.application") {
            val components = project.extensions
                .getByType(ApplicationAndroidComponentsExtension::class.java)
            register(project, components, ext, cliEnabled)
        }

        // library 模块：注册 release 变体加密
        project.plugins.withId("com.android.library") {
            val components = project.extensions
                .getByType(LibraryAndroidComponentsExtension::class.java)
            register(project, components, ext, cliEnabled)
        }
    }

    /**
     * 在 release 变体上注册 StringFogFactory。
     * 关键逻辑：DSL enabled 与 CLI kill-switch 同时为真才插桩；DSL 值经 provider 传入 params，
     *   保证配置缓存/增量构建正确。
     */
    private fun register(
        project: Project,
        components: AndroidComponentsExtension<*, *, *>,
        ext: StringFogExtension,
        cliEnabled: Boolean
    ) {
        components.onVariants(components.selector().withBuildType("release")) { variant ->
            registerStringFog(project, variant, ext, cliEnabled)
        }
    }

    /** 对单个 release 变体注册插桩工厂 + 帧模式。 */
    private fun registerStringFog(
        project: Project,
        variant: Variant,
        ext: StringFogExtension,
        cliEnabled: Boolean
    ) {
        // DSL 开关 && CLI 开关 —— 任一关闭则不插桩
        if (!cliEnabled || !ext.enabled.get()) {
            project.logger.lifecycle("[StringFog] 变体 ${variant.name} 未启用加密（enabled 或 -Pva.stringfog.enabled 关闭）")
            return
        }

        // 配置健壮性校验（硬错误抛出、软冲突告警）
        validateConfig(project, ext)

        // 映射文件路径（mappingEnabled 时输出到 build/outputs/stringfog/，否则空串=不输出）
        val mappingPath = if (ext.mappingEnabled.get()) {
            project.layout.buildDirectory
                .file("outputs/stringfog/stringfog-mapping-${variant.name}.txt")
                .get().asFile.absolutePath
        } else {
            ""
        }

        variant.instrumentation.transformClassesWith(
            StringFogFactory::class.java,
            InstrumentationScope.PROJECT
        ) { params ->
            // DSL → params：以 provider 链接，保证惰性求值与配置缓存兼容
            params.algorithm.set(ext.algorithm)
            params.key.set(ext.key)
            params.fogPackages.set(ext.fogPackages)
            params.excludePackages.set(ext.excludePackages)
            params.minLength.set(ext.minLength)
            params.bytesMode.set(ext.bytesMode)
            params.randomKeyPerString.set(ext.randomKeyPerString)
            params.randomKeyLength.set(ext.randomKeyLength)
            params.mappingFilePath.set(mappingPath)
        }
        // COMPUTE_FRAMES_FOR_INSTRUMENTED_CLASSES：invokedynamic 拼接去糖会改变指令数并新增局部变量，
        //   原 StackMapTable 与 maxStack/maxLocals 必须重算（COPY_FRAMES 直接复制原帧会致 VerifyError）。
        //   AGP 对该模式提供类路径感知的 ClassWriter，getCommonSuperClass 走变换类路径解析，帧重算安全。
        //   仅重算被插桩类（PROJECT 作用域=本模块自有类），开销有界；LDC-only 类一并重算亦逐字节正确。
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_CLASSES
        )

        project.logger.lifecycle(
            "[StringFog] 变体 ${variant.name} 已启用：algorithm=${ext.algorithm.get()}, " +
                "bytesMode=${ext.bytesMode.get()}, randomKeyPerString=${ext.randomKeyPerString.get()}, " +
                "minLength=${ext.minLength.get()}" +
                if (mappingPath.isNotEmpty()) ", mapping=$mappingPath" else ""
        )
    }

    /**
     * 配置健壮性校验。
     * 硬错误（抛出，早失败）：minLength 为负、algorithm 空白、randomKeyLength 非正。
     * 软冲突（告警，不阻塞）：fogPackages ∩ excludePackages 重叠（exclude 优先）、
     *   aes/自定义算法未设 key（走内置默认密钥，安全性弱，仅演示用）。
     */
    private fun validateConfig(project: Project, ext: StringFogExtension) {
        val minLength = ext.minLength.get()
        require(minLength >= 0) { "[StringFog] minLength 不得为负，当前=$minLength" }

        val algorithm = ext.algorithm.get()
        require(algorithm.isNotBlank()) { "[StringFog] algorithm 不得为空白" }

        val randomKeyLength = ext.randomKeyLength.get()
        require(randomKeyLength > 0) { "[StringFog] randomKeyLength 必须为正，当前=$randomKeyLength" }

        // include ∩ exclude 重叠检测（exclude 优先，仅告警提示配置冗余/意图不清）
        val fog = ext.fogPackages.get().toSet()
        val exclude = ext.excludePackages.get().toSet()
        val overlap = fog.intersect(exclude)
        if (overlap.isNotEmpty()) {
            project.logger.warn(
                "[StringFog] fogPackages 与 excludePackages 存在重叠前缀 $overlap，" +
                    "excludePackages 优先（这些包将不被加密）；请确认是否符合预期"
            )
        }

        // aes/自定义算法未显式设 key → 走内置默认密钥（非机密，release 前建议设 key）
        val keySet = ext.key.get() != FogConfigResolver.DEFAULT_KEY_SENTINEL && ext.key.get().isNotEmpty()
        if (algorithm != "xor" && !keySet && !ext.randomKeyPerString.get()) {
            project.logger.warn(
                "[StringFog] algorithm=$algorithm 未设置 key，将使用内置默认密钥（仅便于开箱演示，" +
                    "安全性弱）；release 前建议 stringfog { key.set(\"...\") } 或开启 randomKeyPerString"
            )
        }
    }
}
