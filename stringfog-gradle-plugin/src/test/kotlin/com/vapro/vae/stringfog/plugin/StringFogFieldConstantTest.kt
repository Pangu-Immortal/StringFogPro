package com.vapro.vae.stringfog.plugin

import com.vapro.vae.stringfog.plugin.core.FogConfig
import com.vapro.vae.stringfog.plugin.core.FogConfigResolver
import com.vapro.vae.stringfog.plugin.core.StringFogClassVisitor
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/**
 * ===============================================================================
 * 功能：字段 ConstantValue 加密专项测试（v2.3.0 根治 const val / static final String 明文残留盲点）。
 * 背景：Kotlin `const val X="s"` 与 Java `public static final String X="s"` 编译后，明文以字段的
 *   ConstantValue 属性形态落进 class 常量池——旧版 StringFog 只改方法体 LDC/invokedynamic，覆盖不到
 *   字段属性，导致明文残留在 .class/AAR/dex。v2.3.0 在 ClassVisitor 层剥离该属性并在 <clinit> 注入
 *   「密文+decrypt+PUTSTATIC」运行期还原。
 *
 * 覆盖：①Java 无 <clinit>（合成路径）②Java 有 <clinit>（前插路径）③ASM 复刻 Kotlin object 形态
 *   （const val 字段 + INSTANCE 既有 <clinit>，DXApp 生产配置 xor+每串密钥）④短于 minLength 的常量
 *   字段不动（保留 ConstantValue）⑤多常量字段一并注入 ⑥const 内联进 invokedynamic 拼接 recipe 也清零
 *   （复核 v2.2.0 拼接去糖对 recipe 明文的覆盖，settling ADXWebView "覆盖不到" 之说）。
 *
 * 说明（Kotlin 通吃）：`const val` 生成的字段 ConstantValue 与 Java `static final String` 逐字节同构
 *   （JVMS ConstantValue 属性由编译器无关的 class 格式规定）。故 javac 产物 + ASM 手工 ConstantValue
 *   即覆盖 Kotlin 形态；真实 kotlinc 端到端由阶段二纯 Kotlin :webkitsdk AAR 的 .class 层明文清零复验。
 * ===============================================================================
 */
class StringFogFieldConstantTest {

    /** 内存类加载器：加载插桩后字节，其内 INVOKESTATIC StringFogRuntime 由测试 classpath 提供。 */
    private class BytesClassLoader : ClassLoader(StringFogFieldConstantTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    private fun transform(original: ByteArray, config: FogConfig): ByteArray {
        val cr = ClassReader(original)
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cr.accept(StringFogClassVisitor(StringFogClassVisitor.ASM_API, cw, config), 0)
        return cw.toByteArray()
    }

    private fun resolve(
        algorithm: String = "xor",
        key: String = FogConfigResolver.DEFAULT_KEY_SENTINEL,
        minLength: Int = 1,
        bytesMode: Boolean = false,
        randomKeyPerString: Boolean = false
    ): FogConfig = FogConfigResolver.resolve(
        algorithm = algorithm, key = key, minLength = minLength, bytesMode = bytesMode,
        randomKeyPerString = randomKeyPerString, randomKeyLength = 16,
        mappingWriter = null, classLoader = javaClass.classLoader
    )

    private fun containsPlaintext(bytes: ByteArray, plaintext: String): Boolean {
        val needle = plaintext.toByteArray(StandardCharsets.UTF_8)
        if (needle.isEmpty()) return false
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    /** 读取某字段的 ConstantValue（tree API）：有该属性时返回其值，无则 null（即已被剥离或本无常量）。 */
    private fun fieldConstantValue(bytes: ByteArray, fieldName: String): Any? {
        val cn = ClassNode()
        ClassReader(bytes).accept(cn, 0)
        return cn.fields.firstOrNull { it.name == fieldName }?.value
    }

    /** 类是否含 <clinit>（静态初始化方法）。 */
    private fun hasClinit(bytes: ByteArray): Boolean {
        val cn = ClassNode()
        ClassReader(bytes).accept(cn, 0)
        return cn.methods.any { it.name == "<clinit>" }
    }

    /** 反射读某 public static 字段值（触发类初始化 → 跑注入的 decrypt）。 */
    private fun staticField(internalName: String, bytes: ByteArray, fieldName: String): Any? {
        val clazz = BytesClassLoader().define(internalName.replace('/', '.'), bytes)
        return clazz.getField(fieldName).get(null)
    }

    private fun invokeStatic(internalName: String, bytes: ByteArray, method: String): String {
        val clazz = BytesClassLoader().define(internalName.replace('/', '.'), bytes)
        return clazz.getMethod(method).invoke(null) as String
    }

    // ============================== ① Java 无 <clinit>：合成 <clinit> 承载注入 ==============================

    @Test
    @DisplayName("Java static final String（无 <clinit>）：ConstantValue 明文剥离 + 合成 <clinit> 还原 + 字段值逐字节等价")
    fun javaStaticFinalSynthesizeClinit() {
        val className = "JavaConstField"
        val secret = "java-static-final-mark.via.gp-secret-0001"
        val source = """
            public class $className {
                public static final String SECRET = "$secret";
                public static String get() { return SECRET; }
            }
        """.trimIndent()
        val original = compileJava(className, source)
        // 原始：字段带 ConstantValue、明文在常量池、无 <clinit>（const 无需 clinit 赋值）
        assertNotNull(fieldConstantValue(original, "SECRET"), "原始字段应带 ConstantValue")
        assertTrue(containsPlaintext(original, secret), "原始应含明文")
        assertFalse(hasClinit(original), "const 字段的类原本无 <clinit>")

        val fogged = transform(original, resolve(minLength = 3))
        assertNull(fieldConstantValue(fogged, "SECRET"), "插桩后字段 ConstantValue 必须被剥离")
        assertFalse(containsPlaintext(fogged, secret), "字段 ConstantValue + get() 内联 LDC 的明文必须全部消失")
        assertTrue(hasClinit(fogged), "应合成 <clinit> 承载字段初始化")
        assertTrue(containsPlaintext(fogged, "StringFogRuntime"), "应出现运行时解密器引用")
        assertTrue(containsPlaintext(fogged, "decrypt"), "应出现 decrypt 调用")
        // 运行期：反射读字段（触发合成 clinit）与 get() 均逐字节还原
        assertEquals(secret, staticField(className, fogged, "SECRET"), "运行期字段值必须逐字节等于原文")
        assertEquals(secret, invokeStatic(className, fogged, "get"), "get() 必须逐字节还原")
    }

    // ============================== ② Java 有 <clinit>：注入序列前插既有 <clinit> ==============================

    @Test
    @DisplayName("Java const 字段 + 既有 <clinit>：ConstantValue 剥离前插既有 clinit，既有 LDC 一并加密，两值各自还原")
    fun javaConstWithExistingClinitPrepend() {
        val className = "JavaConstWithClinit"
        val secret = "existing-clinit-mark.via.gp-A-value"
        val mut = "existing-clinit-mutable-B-value"
        val source = """
            public class $className {
                public static final String SECRET = "$secret";
                public static String MUT;
                static { MUT = "$mut"; }
                public static String secret() { return SECRET; }
            }
        """.trimIndent()
        val original = compileJava(className, source)
        assertNotNull(fieldConstantValue(original, "SECRET"), "原始 SECRET 应带 ConstantValue")
        assertTrue(hasClinit(original), "static 块使原始类已有 <clinit>")

        val fogged = transform(original, resolve(minLength = 3))
        assertNull(fieldConstantValue(fogged, "SECRET"), "SECRET ConstantValue 必须被剥离")
        assertFalse(containsPlaintext(fogged, secret), "SECRET 明文必须消失")
        assertFalse(containsPlaintext(fogged, mut), "既有 clinit 里的 MUT LDC 明文也必须被加密消失")
        // 前插的密文序列不被二次加密破坏、既有 clinit 逻辑保留 → 两字段各自逐字节还原
        assertEquals(secret, staticField(className, fogged, "SECRET"), "SECRET 运行期逐字节还原")
        assertEquals(mut, staticField(className, fogged, "MUT"), "MUT 运行期逐字节还原（既有 clinit 逻辑不被破坏）")
        assertEquals(secret, invokeStatic(className, fogged, "secret"), "secret() 逐字节还原")
    }

    // ============================== ③ ASM 复刻 Kotlin object：const val + INSTANCE 既有 clinit（生产配置） ==============================

    @Test
    @DisplayName("Kotlin object 形态（const val + INSTANCE，xor+每串密钥生产配置）：字段 ConstantValue 清零、INSTANCE 与常量各自还原")
    fun kotlinObjectLikeConstVal() {
        val internal = "gen/KtObjectConst"
        val via = "mark.via.gp-object-const-probe"
        val original = buildKotlinObjectLike(internal, "VIA_PACKAGE", via)
        assertNotNull(fieldConstantValue(original, "VIA_PACKAGE"), "复刻的 const val 字段应带 ConstantValue")
        assertTrue(containsPlaintext(original, via), "原始应含明文")

        // DXApp :webkitsdk 生产配置：xor + randomKeyPerString + minLength=3
        val fogged = transform(original, resolve(randomKeyPerString = true, minLength = 3))
        assertNull(fieldConstantValue(fogged, "VIA_PACKAGE"), "const val 字段 ConstantValue 必须被剥离")
        assertFalse(containsPlaintext(fogged, via), "mark.via.gp 明文必须从 .class 常量池清零")
        assertEquals(via, staticField(internal, fogged, "VIA_PACKAGE"), "VIA_PACKAGE 运行期逐字节还原")
        // 既有 <clinit>（初始化 INSTANCE）不被破坏
        assertNotNull(staticField(internal, fogged, "INSTANCE"), "INSTANCE 应被既有 clinit 正常初始化")
    }

    // ============================== ④ 短于 minLength 的常量字段：不动（保留 ConstantValue） ==============================

    @Test
    @DisplayName("短于 minLength 的常量字段：保留 ConstantValue（不误伤），达门槛字段才加密")
    fun shortConstFieldUntouched() {
        val className = "JavaShortConst"
        val shortVal = "ab" // 长度 2 < minLength 5
        val longVal = "long-enough-mark.via.gp-secret"
        val source = """
            public class $className {
                public static final String SHORT = "$shortVal";
                public static final String LONG = "$longVal";
            }
        """.trimIndent()
        val original = compileJava(className, source)
        val fogged = transform(original, resolve(minLength = 5))
        // 短字段：ConstantValue 保留、明文仍在
        assertNotNull(fieldConstantValue(fogged, "SHORT"), "短于 minLength 的字段应保留 ConstantValue")
        assertTrue(containsPlaintext(fogged, shortVal), "短字段明文应保留")
        // 长字段：ConstantValue 剥离、明文消失、运行期还原
        assertNull(fieldConstantValue(fogged, "LONG"), "达门槛字段 ConstantValue 应被剥离")
        assertFalse(containsPlaintext(fogged, longVal), "达门槛字段明文应消失")
        assertEquals(longVal, staticField(className, fogged, "LONG"), "达门槛字段运行期还原")
        assertEquals(shortVal, staticField(className, fogged, "SHORT"), "短字段值不变")
    }

    // ============================== ⑤ 多常量字段：一并注入、各自还原 ==============================

    @Test
    @DisplayName("多常量字段（bytes+AES 三参）：全部 ConstantValue 剥离并在同一 <clinit> 注入，逐个还原")
    fun multipleConstFields() {
        val className = "JavaMultiConst"
        val a = "multi-const-alpha-mark.via.gp"
        val b = "multi-const-bravo-secret-token"
        val c = "multi-const-charlie-endpoint-url"
        val source = """
            public class $className {
                public static final String A = "$a";
                public static final String B = "$b";
                public static final String C = "$c";
            }
        """.trimIndent()
        val original = compileJava(className, source)
        val fogged = transform(original, resolve(algorithm = "aes", key = "multi-key-2026", bytesMode = true, minLength = 3))
        for ((name, value) in listOf("A" to a, "B" to b, "C" to c)) {
            assertNull(fieldConstantValue(fogged, name), "$name ConstantValue 必须剥离")
            assertFalse(containsPlaintext(fogged, value), "$name 明文必须消失")
            assertEquals(value, staticField(className, fogged, name), "$name 运行期逐字节还原")
        }
    }

    // ============================== ⑥ const 内联进 invokedynamic 拼接 recipe：也清零（复核 v2.2.0 覆盖） ==============================

    @Test
    @DisplayName("const 内联进 invokedynamic 拼接 recipe（ADXWebView 日志形态）：recipe 明文一并清零，往返一致")
    fun constInlinedIntoConcatRecipe() {
        val className = "JavaConstInConcat"
        // static final 常量被 javac 常量折叠内联进拼接 recipe（正是 ADXWebView "expect mark.via.gp" 日志形态）
        val source = """
            public class $className {
                private static final String VIA = "mark.via.gp";
                public static String log(String real) {
                    return "package name (getPackageName=" + real + ", expect " + VIA + ")";
                }
            }
        """.trimIndent()
        val original = compileJava(className, source)
        assertTrue(containsPlaintext(original, "makeConcatWithConstants"), "javac 拼接应产出 invokedynamic")
        assertTrue(containsPlaintext(original, "mark.via.gp"), "原始 recipe 应含内联的 const 明文")

        val fogged = transform(original, resolve(minLength = 3))
        // VIA 字段本身 ConstantValue 剥离；被内联进 recipe 的 mark.via.gp 由拼接去糖加密 → 明文彻底清零
        assertFalse(containsPlaintext(fogged, "mark.via.gp"), "字段 + recipe 内联的 mark.via.gp 必须全部清零")
        assertFalse(containsPlaintext(fogged, "makeConcatWithConstants"), "达门槛 recipe 应被去糖移除")

        val clazz = BytesClassLoader().define(className, fogged)
        val ref = clazz.getMethod("log", String::class.java).invoke(null, "com.host.app") as String
        assertEquals("package name (getPackageName=com.host.app, expect mark.via.gp)", ref, "日志拼接必须逐字节还原")
    }

    // ============================== 构造工具 ==============================

    /**
     * 以 ASM 复刻 Kotlin `object` 的字节码形态：
     *   - `public static final Ljava/lang/String; <fieldName>` 带 ConstantValue（== kotlinc const val 形态）
     *   - `public static final L<internal>; INSTANCE`（对象单例引用）
     *   - `<clinit>`：new <internal>; dup; invokespecial <init>()V; putstatic INSTANCE; return
     *   - `public <init>()V`
     * 用于验证「const val 字段 ConstantValue 剥离 + 前插既有 <clinit>（初始化 INSTANCE）」。
     */
    private fun buildKotlinObjectLike(internal: String, fieldName: String, constValue: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, internal, null, "java/lang/Object", null)
        // const val 字段（带 ConstantValue 明文）
        cw.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            fieldName, "Ljava/lang/String;", null, constValue
        ).visitEnd()
        // 单例 INSTANCE 字段（对象引用，无 ConstantValue）
        cw.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "INSTANCE", "L$internal;", null, null
        ).visitEnd()
        // 构造器
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
        }
        // 既有 <clinit>：初始化 INSTANCE（不涉及 const 字段——const 字段本无 clinit 赋值）
        cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, internal)
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, internal, "<init>", "()V", false)
            visitFieldInsn(Opcodes.PUTSTATIC, internal, "INSTANCE", "L$internal;")
            visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** 用系统 Java 编译器把源码编译为 class 字节（产出真实 javac ConstantValue / invokedynamic 拼接字节码）。 */
    private fun compileJava(className: String, source: String): ByteArray {
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("无系统 Java 编译器(需 JDK 而非 JRE 运行测试)")
        val tmp = Files.createTempDirectory("sf-field-javac")
        val srcFile = tmp.resolve("$className.java").toFile().apply { writeText(source) }
        val outDir = tmp.resolve("out").toFile().apply { mkdirs() }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8).use { fm ->
            fm.setLocation(StandardLocation.CLASS_OUTPUT, listOf(outDir))
            val units = fm.getJavaFileObjectsFromFiles(listOf(srcFile))
            val ok = compiler.getTask(null, fm, diagnostics, null, null, units).call()
            check(ok) {
                "javac 编译失败：" + diagnostics.diagnostics.joinToString("\n") { it.toString() }
            }
        }
        return outDir.resolve("$className.class").readBytes()
    }
}
