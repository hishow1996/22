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
 */
class Recents(ctx: Context) {
    data class Item(
        val uri: String, val name: String, val time: Long, val fp: String,
        val pages: Int = 0, val custom: Boolean = false
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
     * 重新导入 / 打开一份卷子：按指纹去重并排最前。
     * 若这条记录曾被手动改名，保留用户命名，不用系统名覆盖。
     */
    fun add(uri: Uri, name: String, fp: String, pages: Int = 0) {
        val key = fp.ifEmpty { uri.toString().hashCode().toUInt().toString(16) }
        val old = all()
        val existing = old.find { it.fp == key }
        val finalName = if (existing?.custom == true) existing.name else name
        val list = old.filter { it.fp != key && it.uri != uri.toString() }
            .toMutableList()
        list.add(0, Item(uri.toString(), finalName, System.currentTimeMillis(),
            key, pages, existing?.custom == true))
        save(list.take(12))
    }

    /** 点开已有记录：只更新 uri / 时间 / 页数并置顶，名字与 custom 原样保留。 */
    fun touch(fp: String, uri: Uri, pages: Int = 0) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.fp == fp }
        if (i < 0) return
        val it = list.removeAt(i)
        list.add(0, it.copy(uri = uri.toString(),
            time = System.currentTimeMillis(), pages = pages))
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
            Item(u, o.optString("n"), o.optLong("t"), fp,
                o.optInt("g", 0), o.optBoolean("c", false))
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
                .put("g", it.pages).put("c", it.custom))
        }
        p.edit().putString("list", a.toString()).apply()
    }
}
