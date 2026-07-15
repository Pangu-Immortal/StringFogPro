/**
 * ===============================================================================
 * 功能：StringFogPro 根构建配置（多模块公共约定）。
 * 函数简介：统一所有子模块的 group / version。
 *   - group=com.github.Pangu-Immortal.StringFogPro（JitPack 多模块坐标前缀）。
 *   - version 默认 2.3.0（对应发布 tag v2.3.0；v2.0.0/v2.1.0/v2.2.0 历史保留）。
 *     可用 -PstringfogVersion=<x> 覆盖：本地 publishToMavenLocal 快速联调时用 v2.3.0 对齐 JitPack 式坐标；
 *     JitPack 侧构建时由其注入 tag 版本，与此默认值无关。
 *
 * 版本沿革：
 *   - 2.1.0：生产级打磨（IKeyGenerator/bytes/mapping/AAR，适配矩阵实测）。
 *   - 2.2.0：新增 invokedynamic makeConcatWithConstants（Java 9+/Kotlin 字符串拼接）字面量加密——
 *            去糖为等价 StringBuilder 链，触达旧版 LDC 路径够不着的 recipe/bootstrap 常量里的明文。
 *   - 2.3.0：新增字段 ConstantValue 加密（根治 const val / static final String 盲点）——对 static+String
 *            类型且带 ConstantValue 属性的常量字段，剥离明文属性并在 <clinit> 注入「密文+decrypt+PUTSTATIC」
 *            运行期还原，使明文不再以字段常量形态残留于 .class/AAR/dex 常量池。
 *
 * 发布坐标（JitPack v2.3.0）：
 *   - 运行时：com.github.Pangu-Immortal.StringFogPro:stringfog:v2.3.0
 *   - 插件：  com.github.Pangu-Immortal.StringFogPro:stringfog-gradle-plugin:v2.3.0
 * ===============================================================================
 */

// 版本可被 -PstringfogVersion 覆盖（本地联调用 v2.3.0 对齐 JitPack 坐标）；默认 2.3.0。
val stringfogVersion: String = (findProperty("stringfogVersion") as String?) ?: "2.3.0"

allprojects {
    group = "com.github.Pangu-Immortal.StringFogPro"
    version = stringfogVersion
}
