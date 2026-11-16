package com.moye.pdfink

/**
 * v44：全文搜索用的「每页文字」。
 *
 * 搜索原来读的是 PdfTextExtractor 的老路：把整本 PDF 所有字体的 ToUnicode
 * 合并成一张表，用「表里有没有大于 0xFF 的码」一刀切判断单双字节。多字体的
 * 文档（正文宋体 + 标题黑体 + 英文字体几乎是标配）码位互相覆盖，
 * 小码位的 CID 字体还会被当成单字节读，读出来是错字或空格——选字用的是
 * PdfLayoutDoc，按每段文字实际用的字体解码，所以能选中、却搜不到。
 *
 * 现在搜索优先用 PdfLayoutDoc 的版面文字（与选字同源、同样准），
 * 只有某一页它读不出来时，才用老提取器补那一页。
 */
internal object SearchText {
    fun build(
        count: Int,
        layoutOf: (Int) -> PageTextLayout?,
        fallback: () -> List<String>
    ): List<String> {
        val out = ArrayList<String>(count.coerceAtLeast(0))
        var missing = 0
        for (p in 0 until count) {
            val t = try { layoutOf(p)?.text } catch (e: Exception) { null } ?: ""
            if (t.isBlank()) missing++
            out.add(t)
        }
        if (missing == 0) return out
        val old = try { fallback() } catch (e: Exception) { emptyList() }
        if (old.isEmpty()) return out
        for (p in 0 until out.size) {
            if (out[p].isBlank()) out[p] = old.getOrNull(p) ?: ""
        }
        return out
    }
}
