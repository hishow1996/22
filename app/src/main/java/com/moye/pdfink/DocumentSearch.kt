package com.moye.pdfink

/** 一条命中：页号（0 基）、在页内文本中的起止位置、带省略号的上下文片段。 */
data class SearchHit(
    val page: Int,
    val start: Int,
    val end: Int,
    val snippet: String
)

/**
 * v43 全文搜索：纯 JVM，输入是「每页文字」列表，PDF 文本层由
 * PdfTextExtractor 提供，纯文本由 TextPageSource 直接给出，图片页为 null。
 * 大小写不敏感；跨页连续编号；命中数有上限，避免超长文档把结果列表撑爆。
 */
object DocumentSearch {
    const val MAX_HITS = 200

    fun find(pages: List<String>, query: String, maxHits: Int = MAX_HITS): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val needle = q.lowercase()
        val limit = maxHits.coerceAtLeast(1)
        val out = ArrayList<SearchHit>()
        for ((idx, text) in pages.withIndex()) {
            if (text.isEmpty()) continue
            val lower = text.lowercase()
            var from = 0
            while (out.size < limit) {
                val at = lower.indexOf(needle, from)
                if (at < 0) break
                val end = at + q.length
                out.add(SearchHit(idx, at, end, snippet(text, at, q.length)))
                from = (at + q.length).coerceAtLeast(at + 1)
            }
            if (out.size >= limit) break
        }
        return out
    }

    /** 命中点前后各取一段，换行压平，折叠空白，两端补省略号。 */
    fun snippet(text: String, at: Int, len: Int, radius: Int = 28): String {
        if (text.isEmpty()) return ""
        val s = (at - radius).coerceAtLeast(0).coerceAtMost(text.length)
        val e = (at + len + radius).coerceIn(s, text.length)
        val body = collapse(text.substring(s, e))
        return (if (s > 0) "…" else "") + body + (if (e < text.length) "…" else "")
    }

    fun collapse(s: String): String {
        val b = StringBuilder(s.length)
        var space = false
        for (ch in s) {
            if (ch.isWhitespace()) {
                if (!space && b.isNotEmpty()) { b.append(' '); space = true }
            } else { b.append(ch); space = false }
        }
        return b.toString().trim()
    }

    /** 「复制全部文字」的口径：按页分隔，保留页号，不编造排版。 */
    fun allText(pages: List<String>): String {
        if (pages.isEmpty()) return ""
        val b = StringBuilder()
        for ((i, t) in pages.withIndex()) {
            if (t.isBlank()) continue
            if (b.isNotEmpty()) b.append("\n\n")
            b.append("— 第 ").append(i + 1).append(" 页 —\n").append(t)
        }
        return b.toString()
    }

    fun hasText(pages: List<String>): Boolean = pages.any { it.isNotBlank() }
}
