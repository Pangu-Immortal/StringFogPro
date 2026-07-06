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
 *   6. COPY_FRAMES——插桩栈语义中性（单参）/ 已在 MethodVisitor 补偿栈深（三参），复制原帧即可。
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
            register(components, ext, cliEnabled)
        }

        // library 模块：注册 release 变体加密
        project.plugins.withId("com.android.library") {
            val components = project.extensions
                .getByType(LibraryAndroidComponentsExtension::class.java)
            register(components, ext, cliEnabled)
        }
    }

    /**
     * 在 release 变体上注册 StringFogFactory。
     * 关键逻辑：DSL enabled 与 CLI kill-switch 同时为真才插桩；DSL 值经 provider 传入 params，
     *   保证配置缓存/增量构建正确。
     */
    private fun register(
        components: AndroidComponentsExtension<*, *, *>,
        ext: StringFogExtension,
        cliEnabled: Boolean
    ) {
        components.onVariants(components.selector().withBuildType("release")) { variant ->
            registerStringFog(variant, ext, cliEnabled)
        }
    }

    /** 对单个 release 变体注册插桩工厂 + 帧模式。 */
    private fun registerStringFog(
        variant: Variant,
        ext: StringFogExtension,
        cliEnabled: Boolean
    ) {
        // DSL 开关 && CLI 开关 —— 任一关闭则不插桩
        if (!cliEnabled || !ext.enabled.get()) {
            return
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
        }
        // COPY_FRAMES：单参栈中性、三参已补偿栈深，复制原帧即可，规避 COMPUTE_FRAMES 的类型解析开销
        variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
    }
}
