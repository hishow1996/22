package com.moye.pdfink

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** 最近打开的卷子书架：按内容指纹去重，同一份卷子换入口打开不会变成两条。 */
class Recents(ctx: Context) {
    data class Item(val uri: String, val name: String, val time: Long, val fp: String, val pages: Int = 0)

    private val p = ctx.getSharedPreferences("moye_recents", Context.MODE_PRIVATE)

    fun add(uri: Uri, name: String, fp: String, pages: Int = 0) {
        val key = fp.ifEmpty { uri.toString().hashCode().toUInt().toString(16) }
        val list = all().filter { it.fp != key && it.uri != uri.toString() }.toMutableList()
        list.add(0, Item(uri.toString(), name, System.currentTimeMillis(), key, pages))
        val a = JSONArray()
        list.take(12).forEach {
            a.put(JSONObject()
                .put("u", it.uri).put("n", it.name)
                .put("t", it.time).put("f", it.fp).put("g", it.pages))
        }
        p.edit().putString("list", a.toString()).apply()
    }

    fun all(): List<Item> = try {
        val a = JSONArray(p.getString("list", "[]") ?: "[]")
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val u = o.optString("u")
            val fp = o.optString("f", "").ifEmpty {
                u.hashCode().toUInt().toString(16)
            }
            Item(u, o.optString("n"), o.optLong("t"), fp, o.optInt("g", 0))
        }
    } catch (e: Exception) { emptyList() }

    fun remove(fp: String) {
        val a = JSONArray()
        all().filter { it.fp != fp }.forEach {
            a.put(JSONObject()
                .put("u", it.uri).put("n", it.name)
                .put("t", it.time).put("f", it.fp).put("g", it.pages))
        }
        p.edit().putString("list", a.toString()).apply()
    }

    fun rename(fp: String, name: String) {
        if (name.isBlank()) return
        val a = JSONArray()
        all().forEach {
            val n = if (it.fp == fp) name.trim() else it.name
            a.put(JSONObject()
                .put("u", it.uri).put("n", n)
                .put("t", it.time).put("f", it.fp).put("g", it.pages))
        }
        p.edit().putString("list", a.toString()).apply()
    }

    fun displayName(uri: Uri): String {
        var s = uri.lastPathSegment ?: "PDF"
        s = s.substringAfterLast('/').substringAfterLast(':')
        return s.removeSuffix(".pdf").removeSuffix(".PDF").take(28).ifEmpty { "PDF" }
    }
}
