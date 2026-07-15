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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * ===============================================================================
 * 功能：invokedynamic makeConcatWithConstants 拼接去糖专项测试（Java 9+/Kotlin 字符串拼接加密闭环）。
 * 方法：以 ASM 手工发射「真 invokedynamic」原始类（运行期由真实 StringConcatFactory 引导，得参考拼接结果）
 *   → 核心访问器去糖插桩 → 断言：①去糖版逐字节等价参考结果（往返一致）②达门槛字面量明文从常量池消失
 *   ③出现 StringFogRuntime.decrypt 调用且 invokedynamic 被移除。另含一例真实 javac 编译产物（证明 Java 源通吃）。
 *
 * 说明（Java/Kotlin 通吃）：makeConcatWithConstants 的 bootstrap/recipe 编码由 JVM 的 StringConcatFactory
 *   规定，与编译器无关——javac 与 kotlinc（-Xstring-concat=indy-with-constants，JVM target 9+ 默认）
 *   产出同一字节码形态。故本处「手工 recipe / 真实 javac 产物」即覆盖两者的字节码形态；真实 kotlinc
 *   端到端另由阶段二纯 Kotlin :webkitsdk 验证（14 条拼接片段明文清零）。
 * ===============================================================================
 */
class StringFogInvokeDynamicTest {

    /** 内存类加载器：加载插桩后字节，其内 INVOKESTATIC StringFogRuntime 由测试 classpath 提供。 */
    private class BytesClassLoader : ClassLoader(StringFogInvokeDynamicTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    /** StringConcatFactory.makeConcatWithConstants 的 bootstrap 句柄（携带 recipe + 常量的形态）。 */
    private val makeConcatWithConstantsBsm = Handle(
        Opcodes.H_INVOKESTATIC,
        "java/lang/invoke/StringConcatFactory",
        "makeConcatWithConstants",
        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
            "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
        false
    )

    /** StringConcatFactory.makeConcat 的 bootstrap 句柄（无 recipe/无常量，全动态参数）。 */
    private val makeConcatBsm = Handle(
        Opcodes.H_INVOKESTATIC,
        "java/lang/invoke/StringConcatFactory",
        "makeConcat",
        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)" +
            "Ljava/lang/invoke/CallSite;",
        false
    )

    /**
     * 生成一个「用真 invokedynamic 拼接」的类：public static String make(<dynArgs>) 直接 LOAD 各参数 → indy → ARETURN。
     * 方法描述符 == indyDesc（参数即动态操作数，顺序一致），运行期由真实 bootstrap 引导得到参考拼接结果。
     */
    private fun makeIndyClass(
        internalName: String,
        indyDesc: String,
        bsm: Handle,
        indyName: String,
        bootstrapArgs: Array<Any>
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "make", indyDesc, null, null).apply {
            visitCode()
            var slot = 0
            for (t in Type.getArgumentTypes(indyDesc)) {
                visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot); slot += t.size
            }
            visitInvokeDynamicInsn(indyName, indyDesc, bsm, *bootstrapArgs)
            visitInsn(Opcodes.ARETURN); visitMaxs(0, 0); visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** 便捷构造 makeConcatWithConstants 类。 */
    private fun makeConcatWithConstantsClass(
        internalName: String,
        indyDesc: String,
        recipe: String,
        vararg constants: Any
    ): ByteArray = makeIndyClass(
        internalName, indyDesc, makeConcatWithConstantsBsm, "makeConcatWithConstants",
        arrayOf(recipe, *constants)
    )

    /** 解析默认 config（可调 minLength/算法/密钥/bytes 模式/每串密钥）。 */
    private fun resolve(
        algorithm: String = "xor",
        key: String = FogConfigResolver.DEFAULT_KEY_SENTINEL,
        minLength: Int = 3,
        bytesMode: Boolean = false,
        randomKeyPerString: Boolean = false
    ): FogConfig = FogConfigResolver.resolve(
        algorithm = algorithm, key = key, minLength = minLength, bytesMode = bytesMode,
        randomKeyPerString = randomKeyPerString, randomKeyLength = 16,
        mappingWriter = null, classLoader = javaClass.classLoader
    )

    private fun transform(original: ByteArray, config: FogConfig): ByteArray {
        val cr = ClassReader(original)
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cr.accept(StringFogClassVisitor(StringFogClassVisitor.ASM_API, cw, config), 0)
        return cw.toByteArray()
    }

    /**
     * 帧重算型 ClassWriter（COMPUTE_FRAMES），最大程度还原生产 AGP 帧模式：
     *   对含分支(带 StackMapTable)的真实方法去糖后，须重算帧/maxs。getCommonSuperClass 用测试类加载器解析，
     *   未加载类型兜底 java/lang/Object（本测试方法的合并点类型均为 JDK 类型，兜底不影响正确性）。
     */
    private class FrameComputingClassWriter(flags: Int) : ClassWriter(flags) {
        override fun getCommonSuperClass(type1: String, type2: String): String = try {
            super.getCommonSuperClass(type1, type2)
        } catch (_: Throwable) {
            "java/lang/Object"
        }
    }

    private fun transformComputeFrames(original: ByteArray, config: FogConfig): ByteArray {
        val cr = ClassReader(original)
        val cw = FrameComputingClassWriter(ClassWriter.COMPUTE_FRAMES)
        cr.accept(StringFogClassVisitor(StringFogClassVisitor.ASM_API, cw, config), 0)
        return cw.toByteArray()
    }

    private fun containsPlaintext(bytes: ByteArray, plaintext: String): Boolean {
        val needle = plaintext.toByteArray(StandardCharsets.UTF_8)
        if (needle.isEmpty()) return false
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun invoke(internalName: String, bytes: ByteArray, paramTypes: Array<Class<*>>, args: Array<Any?>): String {
        val clazz = BytesClassLoader().define(internalName.replace('/', '.'), bytes)
        return clazz.getMethod("make", *paramTypes).invoke(null, *args) as String
    }

    // ============================== 手工 recipe：字面量 + 变量 ==============================

    @Test
    @DisplayName("indy：\"字面量\" + 变量 → 去糖等价 + 字面量明文消失 + decrypt 调用出现")
    fun literalPlusVar() {
        val name = "gen/IndyLiteralVar"
        val indyDesc = "(Ljava/lang/String;)Ljava/lang/String;"
        // recipe：字面量文本 + ''(取动态参数)
        val original = makeConcatWithConstantsClass(name, indyDesc, "prefix-secret://mark.via.gp/")
        val pt = arrayOf<Class<*>>(String::class.java)
        val ref = invoke(name, original, pt, arrayOf("TOKEN-123")) // 真 StringConcatFactory 参考结果
        assertEquals("prefix-secret://mark.via.gp/TOKEN-123", ref)
        assertTrue(containsPlaintext(original, "mark.via.gp"), "原始类应含明文片段")

        val fogged = transform(original, resolve())
        assertEquals(ref, invoke(name, fogged, pt, arrayOf("TOKEN-123")), "去糖版必须逐字节等价参考结果")
        assertFalse(containsPlaintext(fogged, "prefix-secret://mark.via.gp/"), "达门槛字面量明文必须消失")
        assertFalse(containsPlaintext(fogged, "makeConcatWithConstants"), "invokedynamic 应被移除")
        assertTrue(containsPlaintext(fogged, "StringFogRuntime"), "应出现运行时解密器引用")
        assertTrue(containsPlaintext(fogged, "decrypt"), "应出现 decrypt 调用")
    }

    // ============================== 手工 recipe：多段拼接 + 长短混合 ==============================

    @Test
    @DisplayName("indy：多段拼接，长片段加密 / 短于 minLength 片段留明文 / 整体往返一致")
    fun multiSegmentMixedLength() {
        val name = "gen/IndyMultiSeg"
        val indyDesc = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
        // "a=" + x + "&host=mark.via.gp&b=" + y + "-end"
        val original = makeConcatWithConstantsClass(name, indyDesc, "a=&host=mark.via.gp&b=-end")
        val pt = arrayOf<Class<*>>(String::class.java, String::class.java)
        val ref = invoke(name, original, pt, arrayOf("1", "2"))
        assertEquals("a=1&host=mark.via.gp&b=2-end", ref)

        val fogged = transform(original, resolve(minLength = 3))
        assertEquals(ref, invoke(name, fogged, pt, arrayOf("1", "2")), "多段去糖必须往返一致")
        assertFalse(containsPlaintext(fogged, "&host=mark.via.gp&b="), "长片段(≥3)明文必须消失")
        assertFalse(containsPlaintext(fogged, "-end"), "长片段(-end 4字符≥3)明文必须消失")
        // "a=" 长度 2 < minLength 3：允许留明文（策略如此）
        assertTrue(containsPlaintext(fogged, "a="), "短于 minLength 的片段应留明文")
    }

    // ============================== 手工 recipe：全基本类型参数 ==============================

    @Test
    @DisplayName("indy：int/char/boolean/long/float/double 参数各选对 append 重载，往返逐字节一致")
    fun primitiveArgTypes() {
        val name = "gen/IndyPrimitives"
        // 顺序：int, char, boolean, long, float, double
        val indyDesc = "(ICZJFD)Ljava/lang/String;"
        val original = makeConcatWithConstantsClass(
            name, indyDesc, "i=,c=,b=,l=,f=,d="
        )
        val pt = arrayOf<Class<*>>(
            Integer.TYPE, Character.TYPE, java.lang.Boolean.TYPE,
            java.lang.Long.TYPE, java.lang.Float.TYPE, java.lang.Double.TYPE
        )
        val args = arrayOf<Any?>(42, 'X', true, 9_999_999_999L, 3.5f, 2.25)
        val ref = invoke(name, original, pt, args)
        assertEquals("i=42,c=X,b=true,l=9999999999,f=3.5,d=2.25", ref)

        val fogged = transform(original, resolve(minLength = 3))
        assertEquals(ref, invoke(name, fogged, pt, args), "基本类型 append 重载/2槽 long-double 必须往返一致")
        assertFalse(containsPlaintext(fogged, "makeConcatWithConstants"), "invokedynamic 应被去糖移除")
    }

    // ============================== 手工 recipe：'' bootstrap 常量折叠 ==============================

    @Test
    @DisplayName("indy：recipe 含 '\\u0002' 常量(含控制字符的字面量) → 构建期折叠加密，往返含 \\u0001 字符一致")
    fun constantSlotFolding() {
        val name = "gen/IndyConstSlot"
        val indyDesc = "(Ljava/lang/String;)Ljava/lang/String;"
        // recipe ""：取常量[0] 再取动态参数[0]；常量含 '' 故被编译器挪到常量槽
        val konst = "prefix-mark.via.gp--suffix"
        val original = makeConcatWithConstantsClass(name, indyDesc, "", konst)
        val pt = arrayOf<Class<*>>(String::class.java)
        val ref = invoke(name, original, pt, arrayOf("V"))
        assertEquals(konst + "V", ref)

        val fogged = transform(original, resolve(minLength = 3))
        assertEquals(ref, invoke(name, fogged, pt, arrayOf("V")), "常量折叠去糖必须往返一致(含控制字符)")
        assertFalse(containsPlaintext(fogged, "mark.via.gp"), "折叠常量里的明文必须消失")
    }

    // ============================== bytes 模式 + AES 三参 覆盖 indy 去糖 ==============================

    @Test
    @DisplayName("indy：bytes 模式 + AES 三参路径下去糖字面量，往返一致且明文消失")
    fun bytesModeAesConcat() {
        val name = "gen/IndyBytesAes"
        val indyDesc = "(Ljava/lang/String;)Ljava/lang/String;"
        val original = makeConcatWithConstantsClass(name, indyDesc, "endpoint=https://mark.via.gp/api?k=")
        val pt = arrayOf<Class<*>>(String::class.java)
        val ref = invoke(name, original, pt, arrayOf("SEC"))
        val fogged = transform(original, resolve(algorithm = "aes", key = "concat-aes-key-2026", bytesMode = true))
        assertEquals(ref, invoke(name, fogged, pt, arrayOf("SEC")), "bytes+AES indy 去糖必须往返一致")
        assertFalse(containsPlaintext(fogged, "mark.via.gp"), "bytes+AES 去糖后明文必须消失")
    }

    // ============================== 每串随机密钥 覆盖 indy 去糖 ==============================

    @Test
    @DisplayName("indy：每串随机密钥(DXApp 生产配置)下去糖字面量，往返一致且明文消失")
    fun randomKeyPerStringConcat() {
        val name = "gen/IndyRandomKey"
        val indyDesc = "(Ljava/lang/String;)Ljava/lang/String;"
        val original = makeConcatWithConstantsClass(name, indyDesc, "host=mark.via.gp flag=")
        val pt = arrayOf<Class<*>>(String::class.java)
        val ref = invoke(name, original, pt, arrayOf("on"))
        // DXApp :webkitsdk 生产配置：xor + randomKeyPerString + minLength=3
        val fogged = transform(original, resolve(randomKeyPerString = true, minLength = 3))
        assertEquals(ref, invoke(name, fogged, pt, arrayOf("on")), "每串随机密钥 indy 去糖必须往返一致")
        assertFalse(containsPlaintext(fogged, "host=mark.via.gp flag="), "每串密钥去糖后明文必须消失")
    }

    // ============================== 不去糖的安全边界：无可加密字面量 / makeConcat ==============================

    @Test
    @DisplayName("indy：无字面量的纯变量拼接(recipe 全 '\\u0001') → 不去糖、原 indy 保留、往返一致")
    fun pureVariableConcatUntouched() {
        val name = "gen/IndyPureVar"
        val indyDesc = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
        val original = makeConcatWithConstantsClass(name, indyDesc, "")
        val pt = arrayOf<Class<*>>(String::class.java, String::class.java)
        val ref = invoke(name, original, pt, arrayOf("p", "q"))
        assertEquals("pq", ref)
        val fogged = transform(original, resolve(minLength = 3))
        assertEquals(ref, invoke(name, fogged, pt, arrayOf("p", "q")), "无明文可加密时应原样保留并往返一致")
        assertTrue(containsPlaintext(fogged, "makeConcatWithConstants"), "无可加密字面量时 indy 应原样保留(零改动)")
    }

    @Test
    @DisplayName("indy：makeConcat(无常量形态) 不被本器触碰，往返一致")
    fun makeConcatLeftUntouched() {
        val name = "gen/IndyMakeConcat"
        val indyDesc = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
        val original = makeIndyClass(name, indyDesc, makeConcatBsm, "makeConcat", arrayOf())
        val pt = arrayOf<Class<*>>(String::class.java, String::class.java)
        val ref = invoke(name, original, pt, arrayOf("foo", "bar"))
        assertEquals("foobar", ref)
        val fogged = transform(original, resolve(minLength = 3))
        assertEquals(ref, invoke(name, fogged, pt, arrayOf("foo", "bar")), "makeConcat 应原样保留并往返一致")
        assertTrue(containsPlaintext(fogged, "makeConcat"), "makeConcat(无字面量)应原样保留")
    }

    // ============================== 真实 javac 编译产物（证明 Java 源通吃） ==============================

    @Test
    @DisplayName("真实 javac 产物：字符串拼接经真编译器产出 indy → 去糖后明文消失且往返一致")
    fun realJavacCompiledConcat() {
        val className = "JavacConcatSample"
        // 真实 Java 源：javac(JDK 9+) 默认把拼接编译为 invokedynamic makeConcatWithConstants
        val source = """
            public class $className {
                public static String make(String user, int n) {
                    return "user=" + user + "&host=mark.via.gp&n=" + n + "&flag=on";
                }
            }
        """.trimIndent()
        val original = compileJava(className, source)
        assertTrue(containsPlaintext(original, "makeConcatWithConstants"), "javac 产物应为 invokedynamic 拼接")
        assertTrue(containsPlaintext(original, "mark.via.gp"), "javac 产物原始应含明文")

        val pt = arrayOf<Class<*>>(String::class.java, Integer.TYPE)
        val ref = invoke(className, original, pt, arrayOf("admin", 7))
        assertEquals("user=admin&host=mark.via.gp&n=7&flag=on", ref)

        val fogged = transform(original, resolve(minLength = 3))
        assertEquals(ref, invoke(className, fogged, pt, arrayOf("admin", 7)), "真实 javac indy 去糖必须往返一致")
        assertFalse(containsPlaintext(fogged, "mark.via.gp"), "真实 javac 拼接的明文片段必须消失")
        assertFalse(containsPlaintext(fogged, "makeConcatWithConstants"), "去糖后 invokedynamic 应移除")
    }

    @Test
    @DisplayName("生产保真：含分支(StackMapTable)的真实 javac 方法多处拼接 → COMPUTE_FRAMES 重算帧去糖，两分支往返一致")
    fun branchyMethodComputeFrames() {
        val className = "JavacBranchyConcat"
        // 含 if 分支 → javac 产 StackMapTable；多处 makeConcatWithConstants；含 long 局部(占2槽)以压更高 maxLocals
        val source = """
            public class $className {
                public static String make(String a, int n) {
                    long salt = 100000L + n;
                    String s = "base=mark.via.gp/" + a + "&salt=" + salt;
                    if (n > 0) {
                        s = s + "&branch=positive-mark.via.gp&n=" + n;
                    } else {
                        s = s + "&branch=nonpositive";
                    }
                    return s + "&tail=end-marker";
                }
            }
        """.trimIndent()
        val original = compileJava(className, source)
        assertTrue(containsPlaintext(original, "mark.via.gp"), "原始应含明文")

        val pt = arrayOf<Class<*>>(String::class.java, Integer.TYPE)
        val refPos = invoke(className, original, pt, arrayOf("X", 5))
        val refNeg = invoke(className, original, pt, arrayOf("Y", -3))

        // 用 COMPUTE_FRAMES(生产 AGP 同款帧模式)去糖含分支方法
        val fogged = transformComputeFrames(original, resolve(randomKeyPerString = true, minLength = 3))
        assertEquals(refPos, invoke(className, fogged, pt, arrayOf("X", 5)), "正分支去糖(重算帧)必须往返一致")
        assertEquals(refNeg, invoke(className, fogged, pt, arrayOf("Y", -3)), "负分支去糖(重算帧)必须往返一致")
        assertFalse(containsPlaintext(fogged, "mark.via.gp"), "分支内外拼接明文必须全部消失")
        assertFalse(containsPlaintext(fogged, "base=mark.via.gp/"), "首段拼接明文必须消失")
        assertFalse(containsPlaintext(fogged, "&tail=end-marker"), "尾段拼接明文必须消失")
        assertFalse(containsPlaintext(fogged, "makeConcatWithConstants"), "去糖后 invokedynamic 全部移除")
    }

    /** 用系统 Java 编译器把源码编译为 class 字节（产出真实 javac invokedynamic 拼接字节码）。 */
    private fun compileJava(className: String, source: String): ByteArray {
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("无系统 Java 编译器(需 JDK 而非 JRE 运行测试)")
        val tmp = Files.createTempDirectory("sf-javac")
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
