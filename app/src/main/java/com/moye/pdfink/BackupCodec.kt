package com.moye.pdfink

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 只操作 File，便于 JVM 单测；路径白名单防止 zip slip。 */
object BackupCodec {
    private const val PREFIX = "ink_"
    private const val MAX_ENTRY = 8L * 1024 * 1024

    fun export(root: File, out: OutputStream): Int {
        var count = 0
        ZipOutputStream(out).use { z ->
            val meta = JSONObject()
                .put("v", 3)
                .put("time", System.currentTimeMillis())
                .put("docs", root.listFiles()?.count { validDir(it) } ?: 0)
            z.putNextEntry(ZipEntry("moye-backup.json"))
            z.write(meta.toString().toByteArray(Charsets.UTF_8))
            z.closeEntry()
            val dirs = root.listFiles()?.filter { validDir(it) } ?: emptyList()
            for (d in dirs) {
                d.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    if (!validName(d.name + "/" + f.name) || !f.name.endsWith(".json")) return@forEach
                    z.putNextEntry(ZipEntry(d.name + "/" + f.name))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                    count++
                }
            }
        }
        return count
    }

    fun import(root: File, input: InputStream, merge: Boolean = false): Int {
        var count = 0
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name
                if (!validName(name) || e.isDirectory) {
                    z.closeEntry()
                    continue
                }
                val f = File(root, name)
                f.parentFile?.mkdirs()
                val buf = ByteArray(16384)
                val bytes = java.io.ByteArrayOutputStream()
                var written = 0L
                while (written < MAX_ENTRY) {
                    val n = z.read(buf)
                    if (n <= 0) break
                    bytes.write(buf, 0, n)
                    written += n
                }
                if (written >= MAX_ENTRY) {
                    z.closeEntry()
                    continue
                }
                val payload = if (merge && f.exists()) {
                    try { mergeJson(f.readText(), bytes.toString("UTF-8")).toByteArray(Charsets.UTF_8) }
                    catch (ex: Exception) { bytes.toByteArray() }
                } else bytes.toByteArray()
                FileOutputStream(f).use { o -> o.write(payload) }
                count++
                z.closeEntry()
            }
        }
        return count
    }

    /** 合并只追加笔迹数组，不丢任何一方的 ops。 */
    private fun mergeJson(old: String, incoming: String): String {
        val a = try {
            JSONObject(old).optJSONArray("ops") ?: JSONArray()
        } catch (e: Exception) { JSONArray() }
        val b = try {
            JSONObject(incoming).optJSONArray("ops") ?: JSONArray()
        } catch (e: Exception) { JSONArray() }
        for (i in 0 until b.length()) {
            try { a.put(b.get(i)) } catch (e: Exception) { }
        }
        return JSONObject().put("v", 3).put("ops", a).toString()
    }

    private fun validDir(f: File): Boolean =
        f.isDirectory && f.name.startsWith(PREFIX) &&
            f.name.matches(Regex("ink_[A-Za-z0-9]+"))

    /** 只允许 ink_<hex>/<安全文件名>.json */
    private fun validName(name: String): Boolean {
        if (name.contains("..") || name.contains("\\") || name.startsWith("/")) return false
        if (name.count { it == '/' } != 1) return false
        val parts = name.split("/")
        if (!parts[0].matches(Regex("ink_[A-Za-z0-9]+"))) return false
        if (!parts[1].matches(Regex("[A-Za-z0-9_.-]+\\.json"))) return false
        if (parts[1] == "..json" || parts[1].startsWith(".")) return false
        return true
    }
}
