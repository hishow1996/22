package com.moye.pdfink

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * v45：全文搜索用的「每页文字」落盘缓存，纯 JVM。
 *
 * 全文提取要读整份 PDF 并逐页解版面，几百页的书要几秒；同一份文档第二次搜索
 * 不该再等。缓存按文档内容指纹命名，放在系统可回收的 cacheDir 里，
 * 整体有数量 / 体积上限，超出按最久未用淘汰。
 *
 * 文件格式（整体 gzip）：魔数 · 版本 · 页数 · 每页「UTF-8 字节数 + 字节」。
 * 版本号随提取逻辑一起升：提取结果变了（比如换了解码方式），旧缓存自动作废。
 */
internal object TextCache {
    const val VERSION = 1
    private const val MAGIC = 0x4D4F5958          // "MOYX"
    private const val MAX_PAGE_BYTES = 8 * 1024 * 1024
    const val KEEP_FILES = 40
    const val KEEP_BYTES = 150L * 1024 * 1024

    fun fileFor(dir: File, fingerprint: String): File {
        val safe = fingerprint.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)
        return File(File(dir, "text-cache"), "$safe.v$VERSION.gz")
    }

    /** 页数对不上、版本不符、文件损坏一律当没有缓存（返回 null）。 */
    fun read(file: File, expectedPages: Int): List<String>? {
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(GZIPInputStream(file.inputStream()))).use { s ->
                if (s.readInt() != MAGIC || s.readInt() != VERSION) return null
                val n = s.readInt()
                if (n != expectedPages || n < 0) return null
                val out = ArrayList<String>(n)
                repeat(n) {
                    val len = s.readInt()
                    if (len < 0 || len > MAX_PAGE_BYTES) return null
                    val b = ByteArray(len)
                    s.readFully(b)
                    out.add(String(b, Charsets.UTF_8))
                }
                file.setLastModified(System.currentTimeMillis())   // 记一次使用，淘汰时按它排
                out
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 先写临时文件再改名，写到一半被杀也不会留下半截缓存。失败静默（缓存只是加速）。 */
    fun write(file: File, pages: List<String>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(tmp.outputStream()))).use { s ->
                s.writeInt(MAGIC)
                s.writeInt(VERSION)
                s.writeInt(pages.size)
                for (p in pages) {
                    val b = p.toByteArray(Charsets.UTF_8)
                    s.writeInt(b.size)
                    s.write(b)
                }
            }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            file.parentFile?.let { prune(it) }
        } catch (e: Exception) {
            // 忽略
        }
    }

    /** 超出数量 / 体积上限时，从最久未用的开始删。 */
    fun prune(dir: File, keepFiles: Int = KEEP_FILES, keepBytes: Long = KEEP_BYTES) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".gz") }
            ?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for ((i, f) in files.withIndex()) {
            total += f.length()
            if (i >= keepFiles || total > keepBytes) f.delete()
        }
    }
}
