package com.vapro.vae.stringfog.plugin

import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

/**
 * ===============================================================================
 * 功能：AGP AsmClassVisitorFactory 插桩参数（DSL → 工厂的可缓存输入）。
 * 函数简介：把 stringfog { } DSL 解析后的算法/密钥/包过滤/最小串长以 @Input 形态传给
 *   StringFogFactory；@Input 保证增量构建与配置缓存正确识别参数变化。
 *
 * 关键约束：InstrumentationParameters 的所有输入须为 Gradle 可序列化 Property/ListProperty，
 *   并标注 @Input，否则配置缓存报错或增量失效。
 * ===============================================================================
 */
interface StringFogParams : InstrumentationParameters {

    /** 算法 id：xor / aes / 自定义全限定类名。 */
    @get:Input
    val algorithm: Property<String>

    /** 密钥字符串（或默认哨兵）。 */
    @get:Input
    val key: Property<String>

    /** 仅加密的包前缀白名单。 */
    @get:Input
    val fogPackages: ListProperty<String>

    /** 排除加密的包前缀黑名单。 */
    @get:Input
    val excludePackages: ListProperty<String>

    /** 最小加密串长阈值。 */
    @get:Input
    val minLength: Property<Int>
}
