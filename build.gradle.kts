/**
 * ===============================================================================
 * 功能：StringFogPro 根构建配置（多模块公共约定）。
 * 函数简介：统一所有子模块的 group / version。
 *   - group=com.github.Pangu-Immortal.StringFogPro（JitPack 多模块坐标前缀）。
 *   - version=2.1.0（对应发布 tag v2.1.0；v2.0.0 历史保留）。
 *
 * 发布坐标（JitPack v2.1.0）：
 *   - 运行时：com.github.Pangu-Immortal.StringFogPro:stringfog:v2.1.0
 *   - 插件：  com.github.Pangu-Immortal.StringFogPro:stringfog-gradle-plugin:v2.1.0
 * ===============================================================================
 */

allprojects {
    group = "com.github.Pangu-Immortal.StringFogPro"
    version = "2.1.0"
}
