package com.va.stringfog

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.Variant
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * ===============================================================================
 * 功能：StringFog Gradle 插件入口。
 * 函数简介：对应用了 com.android.application / com.android.library 的工程，
 *   在 release 变体上注册 StringFogFactory，做字符串字面量加密。
 *
 * 关键逻辑：
 *   1. 不用 buildSrc（规避 AGP 9.2.1 buildSrc 编译失败），以 included build 源码形态提供。
 *   2. withId 双注册：兼容 application 与 library；插件应用时机与 android 插件顺序无关。
 *   3. 使用具体子扩展类型（Application/LibraryAndroidComponentsExtension），规避
 *      AndroidComponentsExtension<*,*,*> 星投影导致 onVariants 不可调用的编译问题。
 *   4. kill-switch：-Pva.stringfog.enabled=false 关闭（默认启用，仅 release）。
 *   5. COPY_FRAMES：栈语义不变，规避 VA hidden-API 在 COMPUTE_FRAMES 下类型不可解析。
 * ===============================================================================
 */
class StringFogPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // kill-switch：默认启用；-Pva.stringfog.enabled=false 关闭（大小写不敏感，非法值按默认启用处理）
        val enabled = project.findProperty("va.stringfog.enabled")
            ?.toString()
            ?.lowercase()
            ?.toBooleanStrictOrNull()
            ?: true

        // application 模块（:app）：注册 release 变体字符串加密
        project.plugins.withId("com.android.application") {
            val ext = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            ext.onVariants(ext.selector().withBuildType("release")) { variant ->
                registerStringFog(variant, enabled)
            }
        }

        // library 模块（:lib）：注册 release 变体字符串加密
        project.plugins.withId("com.android.library") {
            val ext = project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
            ext.onVariants(ext.selector().withBuildType("release")) { variant ->
                registerStringFog(variant, enabled)
            }
        }
    }

    /** 对单个 variant 注册 StringFog 工厂 + COPY_FRAMES 帧模式。 */
    private fun registerStringFog(variant: Variant, enabled: Boolean) {
        if (!enabled) return
        // 关键逻辑：InstrumentationScope.PROJECT 仅加密本模块源码，不加密依赖（:lib 已加密的类不会被 :app 二次加密）
        variant.instrumentation.transformClassesWith(
            StringFogFactory::class.java,
            InstrumentationScope.PROJECT
        ) { }
        // 关键逻辑：COPY_FRAMES——插桩栈语义不变，直接复制原帧，规避 hidden-API 类型不可解析
        variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
    }
}
