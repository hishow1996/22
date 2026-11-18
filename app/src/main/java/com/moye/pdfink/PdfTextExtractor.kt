package com.moye.pdfink

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.math.min

/**
 * v43 轻量 PDF 文本层提取（零第三方库）。
 *
 * 系统 PdfRenderer 只给位图，全文搜索 / 页面文字复制需要读内容流。这里不引
 * PDF 解析库，用「全文件扫描对象 + 展开 ObjStm + 解 Flate + 扫 Tj/TJ」的
 * 最小实现，覆盖不带加密的常见 PDF。
 *
 * 做法：
 *  - 把文件当 ISO-8859-1 字符串处理，字节与字符一一对应；
 *    `N G obj … endobj` 全表扫描建对象索引，不依赖 xref 表（xref stream 也能过）。
 *  - PDF 1.5+ 把小对象压进 /ObjStm，按 /N /First 的偏移表展开后并入索引。
 *  - 页序走 /Root → /Pages → /Kids 递归；失败时按对象号近似排序。
 *  - /Contents 流解 /FlateDecode，按 Tj / TJ / ' / " 收字符串，
 *    Td / TD / T* / BT / ET 视为换行；TJ 数组里小于 -100 的数字补空格。
 *  - /ToUnicode CMap 的 bfchar / bfrange 做码位→Unicode 映射；无 CMap 时
 *    先试整段 UTF-8（部分生成器直写），再退回 Latin-1。
 *
 * 边界（如实）：扫描件 / 纯图片页本来就没有文字；/Encrypt 加密文档不支持；
 * 无 ToUnicode 的 CJK 子集字体可能乱码；不还原精确阅读顺序与栏位排版。
 */
object PdfTextExtractor {
    /** 单个源文件最多读 24MB，避免超大 PDF 把内存吃满。 */
    const val MAX_SOURCE_BYTES = 24 * 1024 * 1024
    private const val MAX_PAGES = 600

    private val OBJ = Regex("""(\d{1,9})\s+(\d{1,5})\s+obj\b""")
    private val REF = Regex("""(\d{1,9})\s+\d{1,5}\s+R""")
    private val NUM = Regex("""-?\d+(?:\.\d+)?""")
    private val WS = Regex("""\s""")
    private val HEX_TAG = Regex("""<\s*([0-9A-Fa-f]+)\s*>""")
    private val PAGE_TYPE = Regex("""/Type\s*/Page(?![sA-Za-z])""")
    private val BFCHAR = Regex("""beginbfchar(.*?)endbfchar""", RegexOption.DOT_MATCHES_ALL)
    private val BFRANGE = Regex("""beginbfrange(.*?)endbfrange""", RegexOption.DOT_MATCHES_ALL)
    private val CMAP_PAIR = Regex("""<\s*([0-9A-Fa-f]+)\s*>\s*<\s*([0-9A-Fa-f]+)\s*>""")
    private val RANGE = Regex(
        """<\s*([0-9A-Fa-f]+)\s*>\s*<\s*([0-9A-Fa-f]+)\s*>\s*(<[^>]*>|\[[^\]]*\])"""
    )

    internal class Objects {
        val bodies = LinkedHashMap<Int, String>()
        val streams = HashMap<Int, ByteArray>()
    }

    fun extract(bytes: ByteArray): List<String> {
        if (bytes.isEmpty() || bytes.size > MAX_SOURCE_BYTES) return emptyList()
        return try { unsafe(String(bytes, Charsets.ISO_8859_1)) }
        catch (e: Exception) { emptyList() }
    }

    private fun unsafe(text: String): List<String> {
        if (text.contains("/Encrypt")) return emptyList()
        val o = collect(text)
        val pages = pageOrder(o, text)
        if (pages.isEmpty()) return emptyList()
        val cmap = cmapOf(o)
        return pages.map { contentToText(contentOf(o, it), cmap) }
    }

    // ---------- 对象表 ----------
    /**
     * newestWins：同一编号出现多次（增量更新）时取文件里最后一份。默认仍取第一份，
     * 保持读文字的老行为；写批注（PdfAnnotWriter）必须用最新版本，才不会把旧页对象写回去。
     */
    internal fun collect(text: String, newestWins: Boolean = false): Objects {
        val o = Objects()
        for (m in OBJ.findAll(text)) {
            val num = m.groupValues[1].toIntOrNull() ?: continue
            if (!newestWins && o.bodies.containsKey(num)) continue
            val start = m.range.last + 1
            val end = text.indexOf("endobj", start)
            if (end < 0) continue
            val body = text.substring(start, end)
            o.bodies[num] = body
            val si = body.indexOf("stream")
            if (si < 0) continue
            var ds = si + 6
            if (ds < body.length && body[ds] == '\r') ds++
            if (ds < body.length && body[ds] == '\n') ds++
            val de = body.lastIndexOf("endstream")
            if (de <= ds) continue
            val raw = body.substring(ds, de).toByteArray(Charsets.ISO_8859_1)
            o.streams[num] = decodeStream(body.substring(0, si), raw)
        }
        expandObjStm(o)
        return o
    }

    private val FILTER = Regex(
        """/(ASCII85Decode|A85|ASCIIHexDecode|AHx|FlateDecode|Fl|LZWDecode|LZW|RunLengthDecode|RL|DCTDecode|DCT|CCITTFaxDecode|JBIG2Decode|JPXDecode|Crypt)(?![A-Za-z])"""
    )

    /**
     * 按 /Filter 列出的顺序依次解码。认得 ASCII85 / ASCIIHex / Flate
     * （ReportLab 默认是 [ASCII85 Flate]，以前会读出乱码）；遇到不认得的滤镜就停在那一步。
     */
    internal fun decodeStream(dictHead: String, raw: ByteArray): ByteArray {
        val fi = dictHead.indexOf("/Filter")
        if (fi < 0) return raw
        var data = raw
        for (m in FILTER.findAll(dictHead.substring(fi + 7))) {
            data = when (m.groupValues[1]) {
                "ASCII85Decode", "A85" -> ascii85(data) ?: return data
                "ASCIIHexDecode", "AHx" -> asciiHex(data)
                "FlateDecode", "Fl" -> inflate(data) ?: return data
                else -> return data
            }
        }
        return data
    }

    private fun ascii85(src: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(src.size)
        var i = 0
        if (src.size >= 2 && src[0] == '<'.code.toByte() && src[1] == '~'.code.toByte()) i = 2
        val grp = IntArray(5)
        var n = 0
        while (i < src.size) {
            val c = src[i].toInt() and 0xFF
            i++
            if (c == '~'.code) break
            if (c <= 32) continue
            if (c == 'z'.code && n == 0) {
                out.write(0); out.write(0); out.write(0); out.write(0)
                continue
            }
            if (c < 33 || c > 117) return null
            grp[n++] = c - 33
            if (n == 5) {
                var v = 0L
                for (k in 0..4) v = v * 85 + grp[k]
                for (k in 0..3) out.write(((v shr (24 - 8 * k)) and 0xFF).toInt())
                n = 0
            }
        }
        if (n > 1) {
            for (k in n until 5) grp[k] = 84
            var v = 0L
            for (k in 0..4) v = v * 85 + grp[k]
            for (k in 0 until n - 1) out.write(((v shr (24 - 8 * k)) and 0xFF).toInt())
        }
        return out.toByteArray()
    }

    private fun asciiHex(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(src.size / 2 + 1)
        var hi = -1
        for (b in src) {
            val ch = (b.toInt() and 0xFF).toChar()
            if (ch == '>') break
            val d = Character.digit(ch, 16)
            if (d < 0) continue
            if (hi < 0) hi = d else { out.write((hi shl 4) or d); hi = -1 }
        }
        if (hi >= 0) out.write(hi shl 4)
        return out.toByteArray()
    }

    private fun inflate(raw: ByteArray): ByteArray? = try {
        val inf = Inflater()
        inf.setInput(raw)
        val out = ByteArrayOutputStream(maxOf(1024, raw.size * 2))
        val buf = ByteArray(8192)
        var guard = 0
        while (!inf.finished() && guard++ < 4096) {
            val n = inf.inflate(buf)
            if (n == 0) {
                if (inf.needsDictionary() || inf.needsInput()) break
            } else out.write(buf, 0, n)
        }
        inf.end()
        out.toByteArray()
    } catch (e: Exception) { null }

    /** PDF 1.5+ 对象流：头部是「对象号 偏移」的整数对，后面是各对象正文。 */
    private fun expandObjStm(o: Objects) {
        for ((num, body) in o.bodies.toList()) {
            if (!body.contains("/ObjStm")) continue
            val data = o.streams[num] ?: continue
            val n = intAfter(body, "/N") ?: continue
            val first = intAfter(body, "/First") ?: continue
            if (n <= 0 || first <= 0 || first >= data.size) continue
            val head = String(data, 0, first, Charsets.ISO_8859_1)
            val nums = NUM.findAll(head)
                .map { it.value.toIntOrNull() }.filterNotNull().toList()
            val pairs = (0 until minOf(n, nums.size / 2))
                .map { i -> nums[2 * i] to nums[2 * i + 1] }
            for ((i, pr) in pairs.withIndex()) {
                val s = first + pr.second
                val e = if (i + 1 < pairs.size) first + pairs[i + 1].second else data.size
                if (s < 0 || s >= data.size || e <= s) continue
                val len = minOf(e, data.size) - s
                if (!o.bodies.containsKey(pr.first))
                    o.bodies[pr.first] = String(data, s, len, Charsets.ISO_8859_1)
            }
        }
    }

    private fun intAfter(s: String, key: String): Int? {
        val i = s.indexOf(key)
        if (i < 0) return null
        return NUM.find(s.substring(i + key.length))?.value?.toIntOrNull()
    }

    private fun intRefAfter(s: String, key: String): Int? {
        val i = s.indexOf(key)
        if (i < 0) return null
        return REF.find(s.substring(i + key.length))?.groupValues?.get(1)?.toIntOrNull()
    }

    // ---------- 页树 ----------
    internal fun pageOrder(o: Objects, text: String): List<Int> {
        val out = ArrayList<Int>()
        val seen = HashSet<Int>()
        var root: Int? = null
        val ti = text.lastIndexOf("trailer")
        if (ti >= 0) root = REF.find(text.substring(ti, minOf(ti + 2048, text.length)))
            ?.groupValues?.get(1)?.toIntOrNull()
        if (root == null) {
            for ((_, b) in o.bodies) {
                if (b.contains("/Type") && b.contains("/XRef")) {
                    root = intRefAfter(b, "/Root"); if (root != null) break
                }
            }
        }
        if (root == null) root = o.bodies.entries
            .firstOrNull { it.value.contains("/Catalog") }?.key

        fun walk(num: Int) {
            if (!seen.add(num) || out.size >= MAX_PAGES) return
            val b = o.bodies[num] ?: return
            if (PAGE_TYPE.containsMatchIn(b)) { out.add(num); return }
            for (k in kidsOf(b)) walk(k)
        }
        if (root != null) {
            val pages = intRefAfter(o.bodies[root] ?: "", "/Pages")
            walk(pages ?: root)
        }
        if (out.isEmpty()) {
            out.addAll(o.bodies.keys
                .filter { PAGE_TYPE.containsMatchIn(o.bodies[it] ?: "") }.sorted())
        }
        return out.take(MAX_PAGES)
    }

    private fun kidsOf(b: String): List<Int> {
        val i = b.indexOf("/Kids")
        if (i < 0) return emptyList()
        val sub = b.substring(i + 5)
        val close = sub.indexOf(']')
        val seg = if (close >= 0) sub.substring(0, close) else sub.take(512)
        return REF.findAll(seg).map { it.groupValues[1].toIntOrNull() }
            .filterNotNull().toList()
    }

    // ---------- 内容流 ----------
    private fun contentOf(o: Objects, pageNum: Int): String {
        val b = o.bodies[pageNum] ?: return ""
        val i = b.indexOf("/Contents")
        if (i < 0) return ""
        val sub = b.substring(i + 9)
        val seg = if (sub.trimStart().startsWith("[")) {
            val e = sub.indexOf(']')
            if (e >= 0) sub.substring(0, e) else sub
        } else sub.take(96)
        val refs = REF.findAll(seg).map { it.groupValues[1].toIntOrNull() }
            .filterNotNull().distinct().toList()
        val sb = StringBuilder()
        for (n in refs) {
            val d = o.streams[n] ?: continue
            sb.append(String(d, Charsets.ISO_8859_1)).append('\n')
        }
        if (sb.isEmpty()) {
            o.streams[pageNum]?.let { sb.append(String(it, Charsets.ISO_8859_1)) }
        }
        return sb.toString()
    }

    // ---------- ToUnicode CMap ----------
    private fun cmapOf(o: Objects): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        val refs = ArrayList<Int>()
        for ((_, b) in o.bodies) {
            if (!b.contains("/ToUnicode")) continue
            intRefAfter(b, "/ToUnicode")?.let { refs.add(it) }
        }
        for (r in refs.distinct().sorted()) {
            val d = o.streams[r] ?: continue
            parseCmap(String(d, Charsets.ISO_8859_1), out)
        }
        return out
    }

    internal fun parseCmap(s: String, out: MutableMap<Int, String>) {
        for (m in BFCHAR.findAll(s)) {
            for (p in CMAP_PAIR.findAll(m.groupValues[1])) {
                val k = hexInt(p.groupValues[1]) ?: continue
                val v = hexUnicode(p.groupValues[2])
                if (v.isNotEmpty()) out.putIfAbsent(k, v)
            }
        }
        for (m in BFRANGE.findAll(s)) {
            for (t in RANGE.findAll(m.groupValues[1])) {
                val lo = hexInt(t.groupValues[1]) ?: continue
                val hi = hexInt(t.groupValues[2]) ?: continue
                if (hi < lo) continue
                val spec = t.groupValues[3]
                if (spec.startsWith("<")) {
                    val base = hexInt(spec.trim().removePrefix("<").removeSuffix(">"))
                        ?: continue
                    val last = minOf(hi.toLong(), lo.toLong() + 65535L).toInt()
                    for (c in lo..last) {
                        if (!out.containsKey(c)) out[c] = codeUnits(base + (c - lo))
                    }
                } else {
                    val us = HEX_TAG.findAll(spec)
                        .map { hexUnicode(it.groupValues[1]) }.toList()
                    for ((k, u) in (lo..min(hi, lo + us.size - 1)).zip(us)) {
                        if (u.isNotEmpty() && !out.containsKey(k)) out[k] = u
                    }
                }
            }
        }
    }

    private fun hexInt(h: String): Int? {
        val c = h.replace(WS, "")
        if (c.isEmpty() || c.length > 8) return null
        var v = 0
        for (ch in c) {
            val d = Character.digit(ch, 16)
            if (d < 0) return null
            v = (v shl 4) or d
        }
        return v
    }

    /** CMap 目标串按「2 字节 = 1 个 UTF-16 单元」还原，代理对也成立。 */
    private fun hexUnicode(h: String): String {
        val c = h.replace(WS, "")
        if (c.length < 2 || c.length % 2 != 0) return ""
        val b = StringBuilder()
        var i = 0
        while (i + 3 < c.length) {
            val hi = c.substring(i, i + 2).toIntOrNull(16) ?: break
            val lo = c.substring(i + 2, i + 4).toIntOrNull(16) ?: break
            b.append(((hi shl 8) or lo).toChar())
            i += 4
        }
        if (b.isEmpty() && c.length == 2) {
            c.toIntOrNull(16)?.let { b.append(it.toChar()) }
        }
        return b.toString()
    }

    private fun codeUnits(cp: Int): String = try {
        if (cp in 0..0xFFFF) cp.toChar().toString()
        else {
            val v = cp - 0x10000
            val hi = (0xD800 + (v shr 10)).toChar()
            val lo = (0xDC00 + (v and 0x3FF)).toChar()
            String(charArrayOf(hi, lo))
        }
    } catch (e: Exception) { "" }

    // ---------- 内容流词法 ----------
    private fun contentToText(c: String, cmap: Map<Int, String>): String {
        val out = StringBuilder()
        val buf = StringBuilder()
        val twoByte = cmap.keys.any { it > 0xFF }
        var inArray = false
        var i = 0
        fun flush(newline: Boolean) {
            if (buf.isNotEmpty()) { out.append(buf); buf.setLength(0) }
            if (newline) out.append('\n')
        }
        while (i < c.length) {
            val ch = c[i]
            when {
                ch == '%' -> while (i < c.length && c[i] != '\n' && c[i] != '\r') i++
                ch == '(' -> {
                    val r = readLiteral(c, i)
                    buf.append(decode(r.second, cmap, twoByte))
                    i = r.first
                }
                ch == '<' && (i + 1 >= c.length || (c[i + 1] != '<' && c[i + 1] != '/')) -> {
                    val e = c.indexOf('>', i + 1)
                    if (e < 0) i++ else {
                        buf.append(decode(hexBytes(c.substring(i + 1, e)), cmap, twoByte))
                        i = e + 1
                    }
                }
                ch == '[' -> { inArray = true; i++ }
                ch == ']' -> { inArray = false; i++ }
                ch == '/' -> {
                    var j = i + 1
                    while (j < c.length && !isDelim(c[j]) && !c[j].isWhitespace()) j++
                    i = if (j > i) j else i + 1
                }
                ch.isWhitespace() -> i++
                else -> {
                    var j = i
                    while (j < c.length && !isDelim(c[j]) && !c[j].isWhitespace()) j++
                    val tok = c.substring(i, j)
                    i = if (j > i) j else i + 1
                    when (tok) {
                        "Tj", "TJ" -> flush(false)
                        "'", "\"" -> flush(true)
                        "Td", "TD", "T*", "BT", "ET" -> flush(true)
                        else -> {
                            val num = tok.toFloatOrNull()
                            if (num != null && inArray && num < -100f) buf.append(' ')
                        }
                    }
                }
            }
        }
        flush(false)
        return normalize(out.toString())
    }

    private fun isDelim(ch: Char): Boolean = ch in "()<>[]{}/%"

    /** PDF 字面串：括号嵌套、反斜杠转义、八进制、反斜杠换行续接。 */
    private fun readLiteral(c: String, start: Int): Pair<Int, ByteArray> {
        val out = ByteArrayOutputStream()
        var depth = 1
        var i = start + 1
        while (i < c.length && depth > 0) {
            val ch = c[i]
            when {
                ch == '\\' -> {
                    i++
                    if (i >= c.length) break
                    val e = c[i]
                    when {
                        e == 'n' -> out.write(10)
                        e == 'r' -> out.write(13)
                        e == 't' -> out.write(9)
                        e == 'b' -> out.write(8)
                        e == 'f' -> out.write(12)
                        e == '\n' -> Unit
                        e == '\r' -> if (i + 1 < c.length && c[i + 1] == '\n') i++
                        e in '0'..'7' -> {
                            var oct = e.toString()
                            var k = 0
                            while (k < 2 && i + 1 < c.length && c[i + 1] in '0'..'7') {
                                i++; oct += c[i]; k++
                            }
                            out.write(oct.toInt(8) and 0xFF)
                        }
                        else -> out.write(e.toInt() and 0xFF)
                    }
                    i++
                }
                ch == '(' -> { depth++; out.write('('.toInt()); i++ }
                ch == ')' -> { depth--; if (depth > 0) out.write(')'.toInt()); i++ }
                else -> { out.write(ch.toInt() and 0xFF); i++ }
            }
        }
        return i to out.toByteArray()
    }

    private fun hexBytes(h: String): ByteArray {
        val c = h.replace(WS, "")
        val padded = if (c.length % 2 == 0) c else c + "0"
        val out = ByteArray(padded.length / 2)
        var i = 0
        while (i + 1 < padded.length) {
            out[i / 2] = (padded.substring(i, i + 2).toIntOrNull(16) ?: 0).toByte()
            i += 2
        }
        return out
    }

    private fun decode(bs: ByteArray, cmap: Map<Int, String>, twoByte: Boolean): String {
        if (bs.isEmpty()) return ""
        val sb = StringBuilder()
        if (cmap.isNotEmpty()) {
            if (twoByte) {
                var i = 0
                while (i + 1 < bs.size) {
                    val code = ((bs[i].toInt() and 0xFF) shl 8) or (bs[i + 1].toInt() and 0xFF)
                    sb.append(cmap[code] ?: " ")
                    i += 2
                }
            } else {
                for (b in bs) sb.append(cmap[b.toInt() and 0xFF] ?: printable(b))
            }
        } else {
            val utf8 = try { String(bs, Charsets.UTF_8) } catch (e: Exception) { "" }
            val hasHigh = bs.any { (it.toInt() and 0x80) != 0 }
            if (hasHigh && utf8.isNotEmpty() && !utf8.contains('\uFFFD')) sb.append(utf8)
            else for (b in bs) sb.append(printable(b))
        }
        return sb.toString()
    }

    private fun printable(b: Byte): String {
        val v = b.toInt() and 0xFF
        return if (v in 32..126 || v >= 0xA0) v.toChar().toString() else " "
    }

    private fun normalize(s: String): String {
        val out = ArrayList<String>()
        for (line in s.split('\n')) {
            val t = StringBuilder()
            var space = true
            for (ch in line) {
                if (ch.isWhitespace()) {
                    if (!space) { t.append(' '); space = true }
                } else { t.append(ch); space = false }
            }
            val v = t.toString().trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out.joinToString("\n")
    }
}
