package com.vapro.vae.stringfog.plugin.core

/**
 * ===============================================================================
 * 功能：类级插桩过滤纯逻辑（fogPackages 白名单 / excludePackages 黑名单 / 运行时自身排除）。
 * 函数简介：从 AGP 层剥离出的纯函数，输入类名 + DSL 包过滤，输出是否应插桩；
 *   与 AGP 类型解耦，便于脱离 AGP 直接单元测试各配置分支行为。
 *
 * 裁决顺序（与 StringFogFactory.isInstrumentable 一致）：
 *   1. 运行时解密器 / 内置算法实现类——永远排除（防 decrypt 递归加密）。
 *   2. excludePackages 命中任一前缀——排除（黑名单优先）。
 *   3. fogPackages 非空且未命中任一前缀——排除（白名单收窄；为空=全部加密）。
 *   4. 其余——插桩。
 * ===============================================================================
 */
object PackageFilter {

    /**
     * 永远排除的运行时/内置算法类（精确类名，点分）。
     * 注：自定义算法实现若置于加密作用域内，请自行用 excludePackages 排除。
     */
    val RUNTIME_CLASSES: Set<String> = setOf(
        "com.vapro.vae.stringfog.StringFogRuntime",
        "com.vapro.vae.stringfog.IStringFog",
        "com.vapro.vae.stringfog.IKeyGenerator",
        "com.vapro.vae.stringfog.RandomKeyGenerator",
        "com.vapro.vae.stringfog.XorFog",
        "com.vapro.vae.stringfog.AesFog"
    )

    /**
     * 判定某类是否应被插桩。
     *
     * @param className       点分类全限定名
     * @param fogPackages     白名单前缀（为空=全部）
     * @param excludePackages 黑名单前缀（优先于白名单）
     * @return true=应插桩
     */
    fun shouldInstrument(
        className: String,
        fogPackages: List<String>,
        excludePackages: List<String>
    ): Boolean {
        // 1. 排除运行时解密器与内置算法实现自身（防 decrypt 递归加密）
        if (className in RUNTIME_CLASSES) return false
        // 2. 黑名单优先
        if (excludePackages.isNotEmpty() && excludePackages.any { className.startsWith(it) }) return false
        // 3. 白名单收窄（为空表示全部加密）
        if (fogPackages.isNotEmpty() && fogPackages.none { className.startsWith(it) }) return false
        return true
    }
}
