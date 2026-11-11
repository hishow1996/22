package com.moye.pdfink

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

/**
 * 最近打开的卷子书架：按内容指纹去重，同一份卷子换入口打开不会变成两条。
 *
 * v9：标题改用系统 DISPLAY_NAME（和文件管理器同源），保留 .pdf、不截断；
 * custom 标记手动改过名，重新导入 / 点开同一份时不再把用户命名覆盖掉。
 * v28：加 kind / mime 支持任意文件类型；老记录按文件名后缀自动回填类型，
 * 不丢记录、也不需要用户重新导入一次。
 */
class Recents(ctx: Context) {
    data class Item(
        val uri: String, val name: String, val time: Long, val fp: String,
        val pages: Int = 0, val custom: Boolean = false,
        val sizeBytes: Long = -1L,
        val kind: String = "", val mime: String = ""
    )

    private val app = ctx.applicationContext
    private val p = ctx.getSharedPreferences("moye_recents", Context.MODE_PRIVATE)

    /** 系统显示名：和文件管理器一致，含 .pdf，不剥后缀、不截断。 */
    fun displayName(uri: Uri): String {
        val n = try {
            app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && !c.isNull(i)) c.getString(i) else null
                } else null
            }
        } catch (e: Exception) { null }
        if (!n.isNullOrBlank()) return n.trim()
        var s = uri.lastPathSegment ?: "PDF"
        s = s.substringAfterLast('/').substringAfterLast(':')
        return s.ifEmpty { "PDF" }
    }

    /**
     * 读取文件大小：优先使用 DocumentsProvider 元数据，不读取 PDF 正文；
     * 部分 Provider 不提供 SIZE 时，再通过文件描述符长度兜底。
     */
    private fun readSizeBytes(uri: Uri): Long? {
        val fromColumns = try {
            app.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) c.getLong(i).takeIf { it >= 0L }
                    else null
                } else null
            }
        } catch (e: Exception) { null }
        if (fromColumns != null) return fromColumns

        val fromAsset = try {
            app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length.takeIf { it >= 0L }
            }
        } catch (e: Exception) { null }
        if (fromAsset != null) return fromAsset

        return try {
            app.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize.takeIf { it >= 0L }
            }
        } catch (e: Exception) { null }
    }

    /**
     * 读取并缓存最近文件大小。旧版本记录首次显示时懒加载，
     * 后续重绘直接使用缓存，避免重复访问文件 Provider。
     */
    fun fileSizeBytes(item: Item): Long? {
        if (item.sizeBytes >= 0L) return item.sizeBytes
        val size = readSizeBytes(Uri.parse(item.uri)) ?: return null
        val current = all()
        if (current.any { it.fp == item.fp && it.sizeBytes != size }) {
            save(current.map {
                if (it.fp == item.fp) it.copy(sizeBytes = size) else it
            })
        }
        return size
    }

    /**
     * 重新导入 / 打开一份卷子：按指纹去重并排最前。
     * 若这条记录曾被手动改名，保留用户命名，不用系统名覆盖。
     */
    fun add(
        uri: Uri, name: String, fp: String, pages: Int = 0,
        kind: FileKind = FileKind.PDF, mime: String = ""
    ) {
        val key = fp.ifEmpty { uri.toString().hashCode().toUInt().toString(16) }
        val old = all()
        val existing = old.find { it.fp == key }
        val finalName = if (existing?.custom == true) existing.name else name
        val list = old.filter { it.fp != key && it.uri != uri.toString() }
            .toMutableList()
        list.add(0, Item(uri.toString(), finalName, System.currentTimeMillis(),
            key, pages, existing?.custom == true, readSizeBytes(uri) ?: -1L,
            FileType.key(kind), mime))
        save(list.take(12))
    }

    /** v28：首页/导航面板统一入口，任意类型都走这里，类型由文件名+MIME 判定。 */
    fun mimeOf(uri: Uri): String = try {
        app.contentResolver.getType(uri) ?: ""
    } catch (e: Exception) { "" }

    /** 点开已有记录：只更新 uri / 时间 / 页数并置顶，名字与 custom 原样保留。 */
    fun touch(fp: String, uri: Uri, pages: Int = 0) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.fp == fp }
        if (i < 0) return
        val it = list.removeAt(i)
        list.add(0, it.copy(uri = uri.toString(),
            time = System.currentTimeMillis(), pages = pages,
            sizeBytes = readSizeBytes(uri) ?: it.sizeBytes))
        save(list)
    }

    /** 老记录（升级前剥了 .pdf / 截断过）下次显示时刷新成真实名。 */
    fun refreshName(fp: String, name: String) {
        if (name.isBlank()) return
        save(all().map {
            if (it.fp == fp && !it.custom) it.copy(name = name) else it
        })
    }

    fun all(): List<Item> = try {
        val a = JSONArray(p.getString("list", "[]") ?: "[]")
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val u = o.optString("u")
            val fp = o.optString("f", "").ifEmpty {
                u.hashCode().toUInt().toString(16)
            }
            val name = o.optString("n")
            // v28 老记录没有 k 字段：按文件名后缀回填，不丢记录。
            val k = o.optString("k", "").ifEmpty {
                FileType.key(FileType.from(name, null))
            }
            Item(u, name, o.optLong("t"), fp,
                o.optInt("g", 0), o.optBoolean("c", false),
                o.optLong("s", -1L), k, o.optString("m", ""))
        }
    } catch (e: Exception) { emptyList() }

    fun remove(fp: String) { save(all().filter { it.fp != fp }) }

    fun rename(fp: String, name: String) {
        if (name.isBlank()) return
        save(all().map {
            if (it.fp == fp) it.copy(name = name.trim(), custom = true) else it
        })
    }

    private fun save(list: List<Item>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject()
                .put("u", it.uri).put("n", it.name)
                .put("t", it.time).put("f", it.fp)
                .put("g", it.pages).put("c", it.custom)
                .put("s", it.sizeBytes)
                .put("k", it.kind).put("m", it.mime))
        }
        p.edit().putString("list", a.toString()).apply()
    }
}
