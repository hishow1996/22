package com.moye.pdfink

import java.text.Normalizer

/**
 * 一条命中：页号（0 基）、命中词在页内文本中的起止位置、带省略号的上下文片段。
 * snipStart / snipEnd 是命中词在 snippet 里的区间，界面据此高亮；没有就是 0,0。
 */
data class SearchHit(
    val page: Int,
    val start: Int,
    val end: Int,
    val snippet: String,
    val snipStart: Int = 0,
    val snipEnd: Int = 0
)

/**
 * 一次搜索的完整结果。
 * capped：命中数触顶，后面还有；fuzzy：完整短语一处都没找到，
 * 改按「所有词出现在同一页」给出的结果；pageCount：命中分布在多少页。
 */
data class SearchResult(
    val hits: List<SearchHit>,
    val capped: Boolean,
    val fuzzy: Boolean,
    val pageCount: Int
)

/**
 * v44 全文搜索：纯 JVM，输入是「每页文字」列表，PDF 文本层由
 * PdfRepo 提供，纯文本由 TextPageSource 直接给出，图片页为 null。
 *
 * v44 起匹配口径改为「折叠后再比」：
 *  - 空白一律不参与匹配。PDF 的文字是按绘制段落存的，同一句话常被拆成
 *    几段，中间被换行或空格隔开；原来拿原文 indexOf，搜「输入文件内容」
 *    碰上换行就搜不到。现在页文本和关键词都先去掉空白（零宽字符、软连字符
 *    也一并去掉）再比，命中位置映射回原文，高亮和跳页不受影响。
 *  - 全角 / 半角、兼容字符统一（NFKC：ＡＢＣ→abc，①→1，ﬁ→fi），
 *    大小写不敏感，拉丁字母去掉重音（cafe 能搜到 café）。
 *  - 短语整个没找到、但关键词里有空格（「合同 违约」）时，退一步按
 *    「各个词都出现在同一页」给结果，并在 SearchResult.fuzzy 里标出来。
 * 命中数有上限，避免超长文档把结果列表撑爆。
 */
object DocumentSearch {
    const val MAX_HITS = 200

    fun find(pages: List<String>, query: String, maxHits: Int = MAX_HITS): List<SearchHit> =
        search(pages, query, maxHits).hits

    fun search(pages: List<String>, query: String, maxHits: Int = MAX_HITS): SearchResult {
        val none = SearchResult(emptyList(), capped = false, fuzzy = false, pageCount = 0)
        val q = query.trim()
        if (q.isEmpty()) return none
        val needle = fold(q).text
        if (needle.isEmpty()) return none
        val limit = maxHits.coerceAtLeast(1)

        val out = ArrayList<SearchHit>()
        var capped = false
        scan@ for ((idx, text) in pages.withIndex()) {
            if (text.isEmpty()) continue
            val f = fold(text)
            var from = 0
            while (true) {
                val at = f.text.indexOf(needle, from)
                if (at < 0) break
                if (out.size >= limit) { capped = true; break@scan }
                out.add(hitAt(idx, text, f, at, needle.length))
                from = at + needle.length
            }
        }
        if (out.isNotEmpty()) return SearchResult(out, capped, false, pageCountOf(out))

        // 完整短语没有：拆成多个词，退一步按「同一页都出现」给结果
        val terms = q.split(Regex("""\s+"""))
            .map { fold(it).text }
            .filter { it.isNotEmpty() }
            .distinct()
        if (terms.size < 2) return none
        scan@ for ((idx, text) in pages.withIndex()) {
            if (text.isEmpty()) continue
            val f = fold(text)
            if (!terms.all { f.text.contains(it) }) continue
            val here = ArrayList<SearchHit>()
            for (t in terms) {
                var from = 0
                while (true) {
                    val at = f.text.indexOf(t, from)
                    if (at < 0) break
                    here.add(hitAt(idx, text, f, at, t.length))
                    from = at + t.length
                    if (here.size > limit) break
                }
            }
            here.sortBy { it.start }
            for (h in here) {
                if (out.size >= limit) { capped = true; break@scan }
                out.add(h)
            }
        }
        return SearchResult(out, capped, out.isNotEmpty(), pageCountOf(out))
    }

    private fun pageCountOf(hits: List<SearchHit>): Int = hits.map { it.page }.distinct().size

    private fun hitAt(page: Int, text: String, f: Folded, at: Int, len: Int): SearchHit {
        val start = f.from[at]
        val end = f.to[at + len - 1]
        val s = snippetMarked(text, start, end - start, SNIPPET_RADIUS)
        return SearchHit(page, start, end, s.text, s.markStart, s.markEnd)
    }

    // ---------- 折叠 ----------

    /** 折叠后的文本；from[i] / to[i] 是第 i 个折叠字符在原文里的起止（to 为开区间）。 */
    internal class Folded(val text: String, val from: IntArray, val to: IntArray)

    private fun skippable(cp: Int): Boolean =
        Character.isWhitespace(cp) || Character.isSpaceChar(cp) ||
            Character.isISOControl(cp) || cp == 0x00AD ||
            cp in 0x200B..0x200F || cp == 0x2060 || cp == 0xFEFF

    /** NFKC 对这些区间的字符不会改动：汉字、平假名片假名主体、谚文音节，省掉逐字 Normalizer 的开销。 */
    private fun stable(cp: Int): Boolean =
        cp < 0x80 ||
            cp in 0x3400..0x9FFF || cp in 0x20000..0x2EBEF ||
            cp in 0x3041..0x3096 || cp in 0x30A1..0x30FA ||
            cp in 0xAC00..0xD7A3

    internal fun fold(src: String): Folded {
        val sb = StringBuilder(src.length)
        var from = IntArray(src.length + 8)
        var to = IntArray(src.length + 8)
        var n = 0
        fun put(ch: Char, s: Int, e: Int) {
            if (n == from.size) {
                from = from.copyOf(n * 2)
                to = to.copyOf(n * 2)
            }
            sb.append(ch)
            from[n] = s
            to[n] = e
            n++
        }
        var i = 0
        while (i < src.length) {
            val cp = src.codePointAt(i)
            val e = i + Character.charCount(cp)
            if (!skippable(cp)) {
                if (stable(cp)) {
                    for (c in Character.toChars(Character.toLowerCase(cp))) put(c, i, e)
                } else {
                    var norm = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKC)
                    // 拉丁字母（带重音的）拆开丢掉重音，只比基础字母
                    if (cp in 0xC0..0x24F) {
                        val d = Normalizer.normalize(norm, Normalizer.Form.NFD)
                        norm = d.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                    }
                    var k = 0
                    while (k < norm.length) {
                        val c2 = norm.codePointAt(k)
                        k += Character.charCount(c2)
                        if (skippable(c2)) continue
                        for (c in Character.toChars(Character.toLowerCase(c2))) put(c, i, e)
                    }
                }
            }
            i = e
        }
        return Folded(sb.toString(), from.copyOf(n), to.copyOf(n))
    }

    // ---------- 片段 ----------

    const val SNIPPET_RADIUS = 36

    internal class Snip(val text: String, val markStart: Int, val markEnd: Int)

    /** 命中点前后各取一段，换行压平，折叠空白，两端补省略号。 */
    fun snippet(text: String, at: Int, len: Int, radius: Int = 28): String =
        snippetMarked(text, at, len, radius).text

    private fun isCjk(c: Char): Boolean =
        c in '\u2E80'..'\u9FFF' || c in '\uAC00'..'\uD7AF' || c in '\uFF00'..'\uFFEF'

    /**
     * 同 [snippet]，并算出命中词在片段里的区间。
     * 两个汉字之间的空白是换行 / 分段留下的，不是真空格，片段里直接去掉，
     * 否则「输入 文件 内容」读起来像被打碎了。
     */
    internal fun snippetMarked(text: String, at: Int, len: Int, radius: Int): Snip {
        if (text.isEmpty()) return Snip("", 0, 0)
        val a = at.coerceIn(0, text.length)
        val z = (at + len).coerceIn(a, text.length)
        var s = (a - radius).coerceAtLeast(0)
        var e = (z + radius).coerceAtMost(text.length)
        // 别把代理对从中间劈开
        if (s in 1 until text.length && Character.isLowSurrogate(text[s])) s--
        if (e in 1 until text.length && Character.isLowSurrogate(text[e])) e++
        val b = StringBuilder()
        var ms = -1
        var me = -1
        var pendingSpace = false
        var prev = '\u0000'
        for (i in s until e) {
            if (i == z && me < 0) me = b.length
            val ch = text[i]
            if (ch.isWhitespace()) {
                if (b.isNotEmpty()) pendingSpace = true
                continue
            }
            if (pendingSpace) {
                if (!(isCjk(prev) && isCjk(ch))) b.append(' ')
                pendingSpace = false
            }
            if (i == a && ms < 0) ms = b.length
            b.append(ch)
            prev = ch
        }
        if (me < 0) me = b.length
        if (ms < 0) ms = 0
        if (me < ms) me = ms
        var body = b.toString()
        if (s > 0) { body = "…$body"; ms++; me++ }
        if (e < text.length) body = "$body…"
        return Snip(body, ms, me)
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
