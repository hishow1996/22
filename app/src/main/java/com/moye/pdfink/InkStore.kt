package com.moye.pdfink

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * 按 PDF 内容指纹归档批注。
 * 写盘串行化 + tmp/rename 原子替换，杜绝快速连续落笔时旧快照覆盖新笔迹。
 */
class InkStore(private val ctx: Context) {

    companion object {
        private const val PREFIX = "ink_"

        /** 首页卡片用：不 attach 也能判断某份卷子是否写过笔迹。 */
        fun hasAnyInk(ctx: Context, fp: String): Boolean {
            if (fp.isEmpty()) return false
            val d = File(ctx.filesDir, PREFIX + fp)
            if (!d.isDirectory) return false
            return d.listFiles()?.any { f ->
                f.name.endsWith(".json") && try {
                    (JSONObject(f.readText()).optJSONArray("ops")?.length() ?: 0) > 0
                } catch (e: Exception) { false }
            } ?: false
        }

        fun fingerprint(ctx: Context, uri: Uri): String = try {
            Fingerprint.from { ctx.contentResolver.openInputStream(uri)!! }
        } catch (e: Exception) {
            (uri.toString().hashCode().toUInt().toString(16) + "uri")
                .padEnd(24, '0').take(24)
        }
    }

    @Volatile private var dir: File? = null
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "moye-ink-writer").apply { isDaemon = false }
    }

    fun attach(uri: Uri): String {
        flush() // 先排空上一份文档的残留写入
        val fp = fingerprint(ctx, uri)
        val d = File(ctx.filesDir, PREFIX + fp)
        d.mkdirs()
        dir = d
        return fp
    }

    fun detach() { dir = null }

    private fun file(key: String): File? = dir?.let { File(it, "$key.json") }

    fun load(key: String): MutableList<Mark> {
        val f = file(key) ?: return mutableListOf()
        val src = if (f.exists()) f else File(f.parentFile, f.name + ".tmp")
        if (!src.exists()) return mutableListOf()
        return try {
            val arr = JSONObject(src.readText()).optJSONArray("ops") ?: JSONArray()
            val l = ArrayList<Mark>(arr.length())
            for (i in 0 until arr.length()) {
                try { l.add(Mark.fromJson(arr.getJSONObject(i))) } catch (e: Exception) { }
            }
            l
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /** 排队写：writer 单线程保证同一 key 的写入严格按落笔顺序。 */
    fun save(key: String, marks: List<Mark>) {
        val f = file(key) ?: return
        val snap = ArrayList(marks)
        try {
            writer.execute { writeAtomic(f, snap) }
        } catch (e: Exception) {
            writeAtomic(f, snap)
        }
    }

    /** 同步写：同样排进 writer 队列再等待，绝不和异步任务抢同一个 tmp 文件。 */
    fun saveNow(key: String, marks: List<Mark>) {
        val f = file(key) ?: return
        val snap = ArrayList(marks)
        try {
            writer.submit(Runnable { writeAtomic(f, snap) }).get()
        } catch (e: Exception) {
            // 队列已关闭时退化为主线程直写
            writeAtomic(f, snap)
        }
    }

    /** f 在入队时就固定，切换文档不会把旧页写进新卷子。 */
    private fun writeAtomic(f: File, marks: List<Mark>) {
        try {
            val a = JSONArray()
            for (m in marks) a.put(m.toJson())
            val text = JSONObject().put("v", 3).put("ops", a).toString()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // 写失败不影响当前书写；下次 flush 会重试
        }
    }

    /** 等待所有排队写入完成。writer 单线程，空任务排到队尾即为 drain。 */
    fun flush() {
        try {
            writer.submit(Runnable { }).get()
        } catch (e: Exception) {
            // 已 shutdown 时无需再等
        }
    }

    fun shutdown() {
        flush()
        writer.shutdown()
    }

    fun hasInk(key: String): Boolean {
        val f = file(key) ?: return false
        if (!f.exists()) return false
        return try {
            (JSONObject(f.readText()).optJSONArray("ops")?.length() ?: 0) > 0
        } catch (e: Exception) { false }
    }

    fun clearAll() {
        flush()
        dir?.listFiles()?.forEach { if (it.name.endsWith(".json")) it.delete() }
    }

    /** 只备份批注页，书签不进备份包。 */
    fun snapshot(): Map<String, List<Mark>> {
        flush()
        val d = dir ?: return emptyMap()
        val map = HashMap<String, List<Mark>>()
        d.listFiles()?.forEach { f ->
            val k = f.name.removeSuffix(".json")
            if (f.name.endsWith(".json") && k.matches(Regex("[pd]\\d+"))) {
                val v = load(k)
                if (v.isNotEmpty()) map[k] = v
            }
        }
        return map
    }

    fun restore(snap: Map<String, List<Mark>>) {
        clearAll()
        for ((k, v) in snap) saveNow(k, v)
    }

    fun exportTo(out: OutputStream): Int {
        flush()
        return BackupCodec.export(ctx.filesDir, out)
    }

    fun importFrom(input: InputStream, merge: Boolean = false): Int {
        flush()
        return BackupCodec.import(ctx.filesDir, input, merge)
    }

    /** 覆盖式导入前清掉所有文档的批注页，避免残留旧卷子。 */
    fun clearAllDocuments() {
        flush()
        ctx.filesDir.listFiles()?.forEach { d ->
            if (d.isDirectory && d.name.startsWith(PREFIX)) {
                d.listFiles()?.forEach { f ->
                    if (f.name.endsWith(".json")) f.delete()
                }
            }
        }
    }

    /** 页面旋转时把全部笔迹整体迁移到新坐标系，笔迹与题目始终贴合。 */
    fun rotatePages(degrees: Int, pageCount: Int) {
        if (((degrees % 360) + 360) % 360 == 0 || pageCount <= 0) return
        flush()
        for (p in 0 until pageCount) {
            for (pre in arrayOf("p", "d")) {
                val k = "$pre$p"
                val f = file(k) ?: continue
                if (!f.exists()) continue
                val l = load(k)
                if (l.isEmpty()) continue
                val rotated = l.map {
                    it.copyWith(pts = InkPalette.rotatePts(it.pts, degrees))
                }
                saveNow(k, rotated)
            }
        }
        flush()
    }

    // ---- 书签（用户自建目录） ----
    private fun bmFile(): File? = dir?.let { File(it, "bookmarks") }

    fun bookmarks(): Set<Int> {
        val f = bmFile() ?: return emptySet()
        if (!f.exists()) return emptySet()
        return try {
            val a = JSONArray(f.readText())
            val s = HashSet<Int>()
            for (i in 0 until a.length()) s.add(a.optInt(i, -1))
            s.filter { it >= 0 }.toSet()
        } catch (e: Exception) { emptySet() }
    }

    fun toggleBookmark(page: Int): Boolean {
        val f = bmFile() ?: return false
        val s = bookmarks().toMutableSet()
        val added = if (s.contains(page)) { s.remove(page); false } else { s.add(page); true }
        try {
            val a = JSONArray()
            s.sorted().forEach { a.put(it) }
            f.writeText(a.toString())
        } catch (e: Exception) { }
        return added
    }

    fun markCount(pageCount: Int): Int {
        var c = 0
        for (p in 0 until pageCount) {
            if (hasInk("p$p")) c++
            if (hasInk("d$p")) c++
        }
        return c
    }
}
