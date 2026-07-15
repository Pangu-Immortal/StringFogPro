/**
 * ===============================================================================
 * 功能：StringFogPro 根构建配置（多模块公共约定）。
 * 函数简介：统一所有子模块的 group / version。
 *   - group=com.github.Pangu-Immortal.StringFogPro（JitPack 多模块坐标前缀）。
 *   - version 默认 2.2.0（对应发布 tag v2.2.0；v2.0.0/v2.1.0 历史保留）。
 *     可用 -PstringfogVersion=<x> 覆盖：本地 publishToMavenLocal 快速联调时用 v2.2.0 对齐 JitPack 式坐标；
 *     JitPack 侧构建时由其注入 tag 版本，与此默认值无关。
 *
 * 版本沿革：
 *   - 2.1.0：生产级打磨（IKeyGenerator/bytes/mapping/AAR，适配矩阵实测）。
 *   - 2.2.0：新增 invokedynamic makeConcatWithConstants（Java 9+/Kotlin 字符串拼接）字面量加密——
 *            去糖为等价 StringBuilder 链，触达旧版 LDC 路径够不着的 recipe/bootstrap 常量里的明文。
 *
 * 发布坐标（JitPack v2.2.0）：
 *   - 运行时：com.github.Pangu-Immortal.StringFogPro:stringfog:v2.2.0
 *   - 插件：  com.github.Pangu-Immortal.StringFogPro:stringfog-gradle-plugin:v2.2.0
 * ===============================================================================
 */

// 版本可被 -PstringfogVersion 覆盖（本地联调用 v2.2.0 对齐 JitPack 坐标）；默认 2.2.0。
val stringfogVersion: String = (findProperty("stringfogVersion") as String?) ?: "2.2.0"

allprojects {
    group = "com.github.Pangu-Immortal.StringFogPro"
    version = stringfogVersion
}
