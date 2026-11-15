package com.moye.pdfink

import org.json.JSONArray
import org.json.JSONObject

data class TabItem(val uri: String, val name: String, val kind: String)

/**
 * v43 多标签的纯数据层（纯 JVM，便于单测）。
 *
 * 口径：标签只记 Uri / 名称 / 类型，不持有 PageSource。同一时刻只实例一个
 * 页源；切走前保存会话，切回来靠 InkStore 与 SessionRepository 按内容指纹
 * 自动恢复页码、缩放、笔迹。所以内存不随标签数翻倍，荣耀 10 上不引重库。
 */
object TabModels {
    const val MAX = 8

    fun decode(json: String): List<TabItem> {
        if (json.isBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            val out = ArrayList<TabItem>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val u = o.optString("u", "")
                if (u.isBlank()) continue
                out.add(TabItem(u, o.optString("n", ""), o.optString("k", "")))
            }
            out.take(MAX)
        } catch (e: Exception) { emptyList() }
    }

    fun encode(items: List<TabItem>): String {
        val a = JSONArray()
        for (t in items.take(MAX)) {
            a.put(JSONObject().apply {
                put("u", t.uri); put("n", t.name); put("k", t.kind)
            })
        }
        return a.toString()
    }

    /** 打开一个文件：已存在则移到末尾并激活，不重复建；超上限淘汰最久未用。 */
    fun activate(
        items: List<TabItem>, uri: String, name: String, kind: String
    ): Pair<List<TabItem>, Int> {
        if (uri.isBlank()) return items to items.lastIndex
        val kept = items.filterNot { it.uri == uri }.toMutableList()
        kept.add(TabItem(uri, name, kind))
        while (kept.size > MAX) kept.removeAt(0)
        return kept to kept.lastIndex
    }

    /**
     * 关掉某个标签，返回「新列表」与「接下来该激活的下标」；
     * 下标为 null 表示标签清空，调用方应回书架首页。
     */
    fun remove(items: List<TabItem>, index: Int, active: Int): Pair<List<TabItem>, Int?> {
        if (index !in items.indices) return items to active.coerceAtLeast(0).takeIf { items.isNotEmpty() }
        val kept = items.toMutableList().apply { removeAt(index) }
        if (kept.isEmpty()) return kept to null
        val next = when {
            index < active -> active - 1
            index == active -> minOf(active, kept.lastIndex)
            else -> active
        }
        return kept to next.coerceIn(0, kept.lastIndex)
    }

    /** 短名供标签条显示，避免长文件名把整条标签撑满屏。 */
    fun shortName(name: String, limit: Int = 10): String =
        if (name.length <= limit) name else name.take(limit) + "…"
}
