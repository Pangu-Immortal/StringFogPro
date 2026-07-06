package com.vapro.vae.stringfog.plugin.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * ===============================================================================
 * 功能：构建期「明文 → 密文」映射输出器（审计 / 排查用途）。
 * 函数简介：每加密一个字符串字面量，追加一行记录到映射文件，含所在类#方法、算法、明文、密文，
 *   便于事后核对「某处敏感串确实被加密成了什么」以及排查解密异常。
 *
 * 并发与进程安全（关键）：
 *   AGP 插桩可能多线程并发执行；record 用 JVM 级锁 + OS 文件锁（FileChannel.lock）双重保护，
 *   即便跨 Gradle worker 进程写同一文件也不串行错乱。映射文件仅诊断用途，非构建正确性关键路径。
 *
 * 生命周期：文件由插件在配置期清空一次（每次构建刷新）；本器只负责线程/进程安全追加。
 *   增量构建下未重新插桩的类不会再次写入，故「完整映射」以一次 clean/全量构建为准（README 已注明）。
 * ===============================================================================
 */
class MappingWriter(private val file: File) {

    /** JVM 级互斥：先在进程内串行化，再叠加文件锁覆盖跨进程场景。 */
    private val lock = Any()

    /**
     * 追加一条映射记录。
     *
     * @param className  所在类全限定名（点分）
     * @param methodName 所在方法名
     * @param plaintext  原始明文
     * @param cipher     密文的可读表示（Base64 文本；bytes 模式给 Base64 便于人读）
     * @param algorithm  算法 id
     */
    fun record(
        className: String,
        methodName: String,
        plaintext: String,
        cipher: String,
        algorithm: String
    ) {
        // 明文可能含换行/制表，转义为单行，保证映射文件逐行可解析
        val line = buildString {
            append(className).append('#').append(methodName)
            append("  [").append(algorithm).append("]  ")
            append(escape(plaintext))
            append("  ->  ")
            append(cipher)
            append('\n')
        }
        val bytes = line.toByteArray(StandardCharsets.UTF_8)
        synchronized(lock) {
            // 关键逻辑：追加写 + 文件锁，seek 到末尾避免多写者互相覆盖
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { raf ->
                raf.channel.use { ch ->
                    val fl = ch.lock() // 独占文件锁（阻塞直到获得），覆盖跨进程并发
                    try {
                        raf.seek(raf.length())
                        raf.write(bytes)
                    } finally {
                        fl.release()
                    }
                }
            }
        }
    }

    /** 明文单行化：换行/回车/制表转义，避免破坏逐行映射格式。 */
    private fun escape(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
