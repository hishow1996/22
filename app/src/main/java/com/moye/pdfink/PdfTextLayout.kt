package com.moye.pdfink

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 一页的文字层：text 里每个 UTF-16 单元对应一个外框。
 * 外框用归一化页面坐标（0..1，左上为原点，已含页面自带的 /Rotate），
 * 与页面显示大小无关，缩放后只需乘以当前逻辑画布宽高。
 * real=false 的单元是合成的空格 / 换行（复制用），不参与点选。
 */
class PageTextLayout(
    val text: String,
    /** 每单元 4 个：x0, y0, x1, y1 */
    val boxes: FloatArray,
    val real: BooleanArray
) {
    val length: Int get() = text.length

    companion object {
        /** 「已读过，本页没有可选文字」的占位，避免反复后台读取。 */
        val EMPTY = PageTextLayout("", FloatArray(0), BooleanArray(0))
    }

    /** 用户在阅读器里把页面又转了一次（顺时针 90 的倍数）时，外框跟着转。 */
    fun rotated(degrees: Int): PageTextLayout {
        val d = RasterRotation.normalize(degrees)
        if (d == 0) return this
        val out = FloatArray(boxes.size)
        val p = FloatArray(2)
        val q = FloatArray(2)
        for (i in 0 until length) {
            rotateNorm(d, boxes[4 * i], boxes[4 * i + 1], p)
            rotateNorm(d, boxes[4 * i + 2], boxes[4 * i + 3], q)
            out[4 * i] = min(p[0], q[0])
            out[4 * i + 1] = min(p[1], q[1])
            out[4 * i + 2] = max(p[0], q[0])
            out[4 * i + 3] = max(p[1], q[1])
        }
        return PageTextLayout(text, out, real)
    }
}

/** 归一化坐标下的点按顺时针 deg（90 的倍数）旋转。 */
internal fun rotateNorm(deg: Int, u: Float, v: Float, out: FloatArray) {
    when (deg) {
        90 -> { out[0] = 1f - v; out[1] = u }
        180 -> { out[0] = 1f - u; out[1] = 1f - v }
        270 -> { out[0] = v; out[1] = 1f - u }
        else -> { out[0] = u; out[1] = v }
    }
}

// ---------------------------------------------------------------------------
// PDF 语法（对象 / 内容流共用的最小词法器）
// ---------------------------------------------------------------------------

internal class PName(val v: String)
internal class PRef(val num: Int)
internal class PKw(val v: String)

internal class PdfSyntax(private val s: String, private val allowRef: Boolean) {
    var i = 0

    companion object {
        val EOF = Any()
        val ARR_END = Any()
        val DICT_END = Any()
        fun isDelim(c: Char) = c == '(' || c == ')' || c == '<' || c == '>' ||
            c == '[' || c == ']' || c == '{' || c == '}' || c == '/' || c == '%'
        fun isWs(c: Char) = c == ' ' || c == '\n' || c == '\r' || c == '\t' ||
            c == '\u000c' || c == '\u0000'
    }

    fun skipWs() {
        while (i < s.length) {
            val c = s[i]
            if (isWs(c)) i++
            else if (c == '%') {
                while (i < s.length && s[i] != '\n' && s[i] != '\r') i++
            } else break
        }
    }

    /** 读下一个值：数字(Float) / PName / ByteArray(字符串) / List / Map / PRef / PKw / EOF 等哨兵。 */
    fun read(): Any? {
        skipWs()
        if (i >= s.length) return EOF
        val c = s[i]
        when {
            c == '/' -> {
                i++
                val st = i
                while (i < s.length && !isWs(s[i]) && !isDelim(s[i])) i++
                return PName(decodeName(s.substring(st, i)))
            }
            c == '(' -> return literal()
            c == '<' -> {
                if (i + 1 < s.length && s[i + 1] == '<') {
                    i += 2
                    return dict()
                }
                return hex()
            }
            c == '>' -> {
                if (i + 1 < s.length && s[i + 1] == '>') {
                    i += 2
                    return DICT_END
                }
                i++
                return read()
            }
            c == '[' -> {
                i++
                val list = ArrayList<Any?>()
                while (true) {
                    val v = read()
                    if (v === ARR_END || v === EOF) break
                    if (v === DICT_END) continue
                    list.add(v)
                }
                return list
            }
            c == ']' -> { i++; return ARR_END }
            c == '{' || c == '}' || c == ')' -> { i++; return read() }
        }
        val st = i
        while (i < s.length && !isWs(s[i]) && !isDelim(s[i])) i++
        if (i == st) { i++; return read() }
        val w = s.substring(st, i)
        val f = w[0]
        if (f in '0'..'9' || f == '-' || f == '+' || f == '.') {
            val num = w.toFloatOrNull()
            if (num != null) {
                if (allowRef && w.all { it in '0'..'9' }) refAfter(w)?.let { return it }
                return num
            }
        }
        return PKw(w)
    }

    /** 已读到整数 w；若后面紧跟「整数 R」就是间接引用。 */
    private fun refAfter(w: String): PRef? {
        val save = i
        skipWs()
        var j = i
        while (j < s.length && s[j] in '0'..'9') j++
        if (j > i) {
            var k = j
            while (k < s.length && isWs(s[k])) k++
            if (k < s.length && s[k] == 'R' &&
                (k + 1 >= s.length || isWs(s[k + 1]) || isDelim(s[k + 1]))
            ) {
                i = k + 1
                val n = w.toIntOrNull()
                if (n != null) return PRef(n)
            }
        }
        i = save
        return null
    }

    private fun dict(): Map<String, Any?> {
        val m = HashMap<String, Any?>()
        while (true) {
            val k = read()
            if (k === DICT_END || k === EOF) break
            if (k !is PName) continue
            val v = read()
            if (v === DICT_END || v === EOF) break
            if (v === ARR_END) continue
            m[k.v] = v
        }
        return m
    }

    private fun hex(): ByteArray {
        i++ // '<'
        val out = java.io.ByteArrayOutputStream()
        var hi = -1
        while (i < s.length && s[i] != '>') {
            val d = Character.digit(s[i], 16)
            i++
            if (d < 0) continue
            if (hi < 0) hi = d else { out.write((hi shl 4) or d); hi = -1 }
        }
        if (hi >= 0) out.write(hi shl 4)
        if (i < s.length) i++ // '>'
        return out.toByteArray()
    }

    private fun literal(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var depth = 1
        i++ // '('
        while (i < s.length && depth > 0) {
            val ch = s[i]
            when {
                ch == '\\' -> {
                    i++
                    if (i >= s.length) break
                    val e = s[i]
                    when {
                        e == 'n' -> out.write(10)
                        e == 'r' -> out.write(13)
                        e == 't' -> out.write(9)
                        e == 'b' -> out.write(8)
                        e == 'f' -> out.write(12)
                        e == '\n' -> Unit
                        e == '\r' -> if (i + 1 < s.length && s[i + 1] == '\n') i++
                        e in '0'..'7' -> {
                            var v = e - '0'
                            var k = 0
                            while (k < 2 && i + 1 < s.length && s[i + 1] in '0'..'7') {
                                i++
                                v = v * 8 + (s[i] - '0')
                                k++
                            }
                            out.write(v and 0xFF)
                        }
                        else -> out.write(e.code and 0xFF)
                    }
                    i++
                }
                ch == '(' -> { depth++; out.write('('.code); i++ }
                ch == ')' -> { depth--; if (depth > 0) out.write(')'.code); i++ }
                else -> { out.write(ch.code and 0xFF); i++ }
            }
        }
        return out.toByteArray()
    }

    private fun decodeName(raw: String): String {
        if (!raw.contains('#')) return raw
        val sb = StringBuilder()
        var k = 0
        while (k < raw.length) {
            val ch = raw[k]
            if (ch == '#' && k + 2 < raw.length) {
                val v = raw.substring(k + 1, k + 3).toIntOrNull(16)
                if (v != null) { sb.append(v.toChar()); k += 3; continue }
            }
            sb.append(ch)
            k++
        }
        return sb.toString()
    }

    /** 内联图片 BI … ID … EI：跳到 EI 之后。 */
    fun skipInlineImage() {
        val m = EI.find(s, i)
        i = if (m != null) m.range.last + 1 else s.length
    }

    private val EI = Regex("""\sEI(?=\s|$)""")
}

// ---------------------------------------------------------------------------
// 字体：码位 → 文字、码位 → 字宽
// ---------------------------------------------------------------------------

internal class PdfFont(
    val twoByte: Boolean,
    val cmap: Map<Int, String>,
    private val ucs2: Boolean,
    private val widths: FloatArray?,
    private val firstChar: Int,
    private val missingW: Float,
    private val defaultW: Float,
    private val cidW: Map<Int, Float>?,
    private val widthScale: Float,
    private val std: FloatArray?,
    val ascent: Float,
    val descent: Float
) {
    /** 字宽，单位 1/1000 em。 */
    fun width(code: Int): Float {
        if (twoByte) return cidW?.get(code) ?: defaultW
        val w = widths
        if (w != null) {
            val k = code - firstChar
            return (if (k in w.indices) w[k] else missingW) * widthScale
        }
        val t = std
        if (t != null) return if (code in 32..126) t[code - 32] else 556f
        return if (code == 32) 278f else 540f
    }

    fun toText(code: Int): String? {
        cmap[code]?.let { return it }
        if (twoByte) {
            return if (ucs2 && code > 0x20 && code != 0xFFFF) code.toChar().toString() else null
        }
        if (code in 32..126 || code >= 0xA0) return code.toChar().toString()
        if (code in 0x80..0x9F) {
            val c = WIN1252[code - 0x80]
            if (c != '\u0000') return c.toString()
        }
        return null
    }

    private companion object {
        const val WIN1252 =
            "€\u0000‚ƒ„…†‡ˆ‰Š‹Œ\u0000Ž\u0000\u0000‘’“”•–—˜™š›œ\u0000žŸ"
    }
}

// ---------------------------------------------------------------------------
// 文档 + 解释器
// ---------------------------------------------------------------------------

/**
 * 带位置的文字层提取（零第三方库）。与 [PdfTextExtractor] 共用对象表；
 * 这里额外解析字体的 /Widths（/W）与 ToUnicode，并按内容流的
 * 文本矩阵、CTM、Form XObject 算出每个字的外框。
 *
 * 精度（如实）：字宽取自字体字典，没有字宽的标准字体按经验值估算，
 * 外框高度取 /FontDescriptor 的 Ascent/Descent；旋转文字按外接矩形。
 * 加密 PDF、纯图片页没有文字层，返回 null。
 */
class PdfLayoutDoc private constructor(
    private val o: PdfTextExtractor.Objects,
    private val pageNums: List<Int>
) {
    val pageCount: Int get() = pageNums.size

    companion object {
        private const val MAX_DEPTH = 6
        private const val MAX_FORMS = 400
        private const val MAX_OUTLINE_ITEMS = 3000
        private const val MAX_OUTLINE_DEPTH = 12

        fun open(bytes: ByteArray): PdfLayoutDoc? {
            if (bytes.isEmpty() || bytes.size > PdfTextExtractor.MAX_SOURCE_BYTES) return null
            return try {
                val text = String(bytes, Charsets.ISO_8859_1)
                if (text.contains("/Encrypt")) return null
                val objs = PdfTextExtractor.collect(text)
                val pages = PdfTextExtractor.pageOrder(objs, text)
                if (pages.isEmpty()) null else PdfLayoutDoc(objs, pages)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }

        // 矩阵 [a b c d e f]，行向量约定：先 A 后 B。
        internal fun mul(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
            a[0] * b[0] + a[1] * b[2],
            a[0] * b[1] + a[1] * b[3],
            a[2] * b[0] + a[3] * b[2],
            a[2] * b[1] + a[3] * b[3],
            a[4] * b[0] + a[5] * b[2] + b[4],
            a[4] * b[1] + a[5] * b[3] + b[5]
        )

        internal fun identity() = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
    }

    private val parsed = HashMap<Int, Any?>()
    private val fonts = HashMap<Int, PdfFont>()

    fun layout(page: Int): PageTextLayout? {
        return try {
            layoutUnsafe(page)
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    // ----- v45：目录（Outlines） -----

    /**
     * 读取 PDF 自带的目录树，按文档顺序展平成带层级的列表；没有目录或读不出来返回空。
     * 目标支持：直接目标数组、/A << /S /GoTo /D … >>、命名目标（/Dests 字典与 /Names 名称树）。
     */
    fun outline(): List<OutlineEntry> = try {
        outlineUnsafe()
    } catch (e: Exception) {
        emptyList()
    } catch (e: StackOverflowError) {
        emptyList()
    }

    private fun catalogDict(): Map<String, Any?>? {
        var fallback: Map<String, Any?>? = null
        for ((n, b) in o.bodies) {
            if (!b.contains("/Catalog")) continue
            val d = dict(PRef(n)) ?: continue
            if ((resolve(d["Type"]) as? PName)?.v != "Catalog") continue
            if (d["Outlines"] != null) return d
            if (fallback == null && d["Pages"] != null) fallback = d
        }
        return fallback
    }

    private fun outlineUnsafe(): List<OutlineEntry> {
        val cat = catalogDict() ?: return emptyList()
        val root = dict(cat["Outlines"]) ?: return emptyList()
        val pageIndex = HashMap<Int, Int>()
        pageNums.forEachIndexed { i, n -> pageIndex[n] = i }
        val out = ArrayList<OutlineEntry>()
        val seen = HashSet<Int>()

        fun walk(first: Any?, level: Int) {
            var cur: Any? = first
            while (cur != null && out.size < MAX_OUTLINE_ITEMS) {
                val ref = cur as? PRef
                if (ref != null && !seen.add(ref.num)) return   // 环
                val d = dict(cur) ?: return
                val title = PdfText.decode(resolve(d["Title"]) as? ByteArray).trim()
                if (title.isNotEmpty()) {
                    out.add(OutlineEntry(title, destPage(d, cat, pageIndex), level))
                }
                if (level < MAX_OUTLINE_DEPTH) d["First"]?.let { walk(it, level + 1) }
                cur = d["Next"]
            }
        }
        walk(root["First"], 0)
        return out
    }

    private fun destPage(item: Map<String, Any?>, cat: Map<String, Any?>, pageIndex: Map<Int, Int>): Int {
        var dest: Any? = item["Dest"]
        if (dest == null) {
            val a = dict(item["A"])
            if (a != null && (resolve(a["S"]) as? PName)?.v == "GoTo") dest = a["D"]
        }
        return pageOfDest(dest, cat, pageIndex, 0)
    }

    private fun pageOfDest(dest: Any?, cat: Map<String, Any?>, pageIndex: Map<Int, Int>, depth: Int): Int {
        if (dest == null || depth > 4) return -1
        return when (val v = resolve(dest)) {
            is List<*> -> (v.firstOrNull() as? PRef)?.let { pageIndex[it.num] } ?: -1
            is Map<*, *> -> pageOfDest(v["D"], cat, pageIndex, depth + 1)
            is PName -> namedDest(v.v, cat, pageIndex, depth)
            is ByteArray -> namedDest(String(v, Charsets.ISO_8859_1), cat, pageIndex, depth)
            else -> -1
        }
    }

    private fun namedDest(name: String, cat: Map<String, Any?>, pageIndex: Map<Int, Int>, depth: Int): Int {
        dict(cat["Dests"])?.get(name)?.let { return pageOfDest(it, cat, pageIndex, depth + 1) }
        val tree = dict(dict(cat["Names"])?.get("Dests")) ?: return -1
        val v = findInNameTree(tree, name, 0) ?: return -1
        return pageOfDest(v, cat, pageIndex, depth + 1)
    }

    private fun findInNameTree(node: Map<String, Any?>, key: String, depth: Int): Any? {
        if (depth > 8) return null
        list(node["Names"])?.let { l ->
            var i = 0
            while (i + 1 < l.size) {
                val k = resolve(l[i]) as? ByteArray
                if (k != null && String(k, Charsets.ISO_8859_1) == key) return l[i + 1]
                i += 2
            }
        }
        list(node["Kids"])?.forEach { kid ->
            val kd = dict(kid) ?: return@forEach
            findInNameTree(kd, key, depth + 1)?.let { return it }
        }
        return null
    }

    // ----- 对象访问 -----

    private fun obj(num: Int): Any? {
        if (parsed.containsKey(num)) return parsed[num]
        val body = o.bodies[num]
        val v = if (body == null) null else PdfSyntax(body, true).read().let {
            if (it === PdfSyntax.EOF) null else it
        }
        parsed[num] = v
        return v
    }

    private fun resolve(v: Any?): Any? {
        var cur = v
        var guard = 0
        while (cur is PRef && guard++ < 8) cur = obj(cur.num)
        return cur
    }

    @Suppress("UNCHECKED_CAST")
    private fun dict(v: Any?): Map<String, Any?>? = resolve(v) as? Map<String, Any?>

    private fun list(v: Any?): List<Any?>? = resolve(v) as? List<Any?>

    private fun num(v: Any?): Float? = (resolve(v) as? Float)

    private fun nums(v: Any?): FloatArray? {
        val l = list(v) ?: return null
        val out = FloatArray(l.size)
        for (k in l.indices) out[k] = num(l[k]) ?: return null
        return out
    }

    private fun inherited(page: Map<String, Any?>, key: String): Any? {
        var d: Map<String, Any?>? = page
        var guard = 0
        while (d != null && guard++ < 24) {
            d[key]?.let { return it }
            d = dict(d["Parent"])
        }
        return null
    }

    // ----- 页面几何 -----

    private class PageGeom(val l: Float, val b: Float, val w: Float, val h: Float, val rot: Int) {
        private val p = FloatArray(2)
        private val q = FloatArray(2)

        /** PDF 用户空间矩形 → 归一化（左上原点、含 /Rotate）矩形，写入 out[o..o+3]。 */
        fun rect(minX: Float, minY: Float, maxX: Float, maxY: Float, out: FloatArray, o: Int) {
            val ax = (minX - l) / w
            val bx = (maxX - l) / w
            val ay = 1f - (maxY - b) / h
            val by = 1f - (minY - b) / h
            rotateNorm(rot, ax, ay, p)
            rotateNorm(rot, bx, by, q)
            out[o] = min(p[0], q[0])
            out[o + 1] = min(p[1], q[1])
            out[o + 2] = max(p[0], q[0])
            out[o + 3] = max(p[1], q[1])
        }
    }

    private fun pageGeom(pd: Map<String, Any?>): PageGeom? {
        val media = nums(inherited(pd, "MediaBox"))?.takeIf { it.size >= 4 }
            ?: floatArrayOf(0f, 0f, 612f, 792f)
        val crop = nums(inherited(pd, "CropBox"))?.takeIf { it.size >= 4 } ?: media
        var l = max(min(crop[0], crop[2]), min(media[0], media[2]))
        var b = max(min(crop[1], crop[3]), min(media[1], media[3]))
        var r = min(max(crop[0], crop[2]), max(media[0], media[2]))
        var t = min(max(crop[1], crop[3]), max(media[1], media[3]))
        if (r - l < 1f || t - b < 1f) {
            l = min(media[0], media[2]); b = min(media[1], media[3])
            r = max(media[0], media[2]); t = max(media[1], media[3])
        }
        if (r - l < 1f || t - b < 1f) return null
        val rotRaw = num(inherited(pd, "Rotate"))?.toInt() ?: 0
        return PageGeom(l, b, r - l, t - b, RasterRotation.normalize(rotRaw))
    }

    // ----- 字体 -----

    private fun fontFor(res: Map<String, Any?>?, name: String): PdfFont? {
        val fd = dict(res?.get("Font")) ?: return null
        val ref = fd[name]
        val key = (ref as? PRef)?.num
        if (key != null) fonts[key]?.let { return it }
        val f = dict(ref)?.let { buildFont(it) } ?: return null
        if (key != null) fonts[key] = f
        return f
    }

    private fun buildFont(fd: Map<String, Any?>): PdfFont {
        val sub = (resolve(fd["Subtype"]) as? PName)?.v
        val cmap = HashMap<Int, String>()
        (fd["ToUnicode"] as? PRef)?.let { r ->
            o.streams[r.num]?.let {
                PdfTextExtractor.parseCmap(String(it, Charsets.ISO_8859_1), cmap)
            }
        }
        if (sub == "Type0") {
            val encName = (resolve(fd["Encoding"]) as? PName)?.v ?: ""
            val desc = dict(list(fd["DescendantFonts"])?.firstOrNull())
            val dw = num(desc?.get("DW")) ?: 1000f
            val cidW = HashMap<Int, Float>()
            list(desc?.get("W"))?.let { parseCidWidths(it, cidW) }
            val d = dict(desc?.get("FontDescriptor"))
            return PdfFont(
                twoByte = true, cmap = cmap,
                ucs2 = encName.contains("UCS2") || encName.contains("UTF16"),
                widths = null, firstChar = 0, missingW = 0f,
                defaultW = dw, cidW = cidW, widthScale = 1f, std = null,
                ascent = ascentOf(d), descent = descentOf(d)
            )
        }
        val widths = nums(fd["Widths"])
        val first = num(fd["FirstChar"])?.toInt() ?: 0
        val d = dict(fd["FontDescriptor"])
        val missing = num(d?.get("MissingWidth")) ?: 0f
        // Type3 的字宽单位由 FontMatrix 决定
        val scale = if (sub == "Type3") (nums(fd["FontMatrix"])?.getOrNull(0) ?: 0.001f) * 1000f
        else 1f
        return PdfFont(
            twoByte = false, cmap = cmap, ucs2 = false,
            widths = widths, firstChar = first, missingW = missing,
            defaultW = 1000f, cidW = null, widthScale = scale,
            std = if (widths == null) {
                StdFontWidths.forBaseFont((resolve(fd["BaseFont"]) as? PName)?.v)
            } else null,
            ascent = ascentOf(d), descent = descentOf(d)
        )
    }

    private fun ascentOf(d: Map<String, Any?>?): Float =
        ((num(d?.get("Ascent")) ?: 800f) / 1000f).coerceIn(0.5f, 1.1f)

    private fun descentOf(d: Map<String, Any?>?): Float {
        val v = (num(d?.get("Descent")) ?: -200f) / 1000f
        return (if (v > 0f) -v else v).coerceIn(-0.5f, 0f)
    }

    /** /W：「c [w1 w2 …]」或「c1 c2 w」两种写法混排。 */
    private fun parseCidWidths(w: List<Any?>, out: MutableMap<Int, Float>) {
        var i = 0
        while (i < w.size) {
            val c1 = num(w[i])?.toInt() ?: break
            val nx = resolve(w.getOrNull(i + 1))
            if (nx is List<*>) {
                for ((k, v) in nx.withIndex()) out[c1 + k] = num(v) ?: continue
                i += 2
            } else {
                val c2 = (nx as? Float)?.toInt() ?: break
                val wv = num(w.getOrNull(i + 2)) ?: break
                for (c in c1..min(c2, c1 + 70000)) out[c] = wv
                i += 3
            }
        }
    }

    // ----- 内容流 -----

    private fun contentOf(pd: Map<String, Any?>): String {
        val c = pd["Contents"]
        val nums: List<Int> = when {
            c is PRef -> {
                val r = resolve(c)
                if (r is List<*>) r.mapNotNull { (it as? PRef)?.num } else listOf(c.num)
            }
            c is List<*> -> c.mapNotNull { (it as? PRef)?.num }
            else -> emptyList()
        }
        val sb = StringBuilder()
        for (n in nums) {
            val d = o.streams[n] ?: continue
            sb.append(String(d, Charsets.ISO_8859_1)).append('\n')
        }
        return sb.toString()
    }

    private fun layoutUnsafe(page: Int): PageTextLayout? {
        val num = pageNums.getOrNull(page) ?: return null
        val pd = dict(PRef(num)) ?: return null
        val geom = pageGeom(pd) ?: return null
        val res = dict(inherited(pd, "Resources"))
        val content = contentOf(pd)
        if (content.isBlank()) return null
        val b = Builder(geom)
        Interp(b).run(content, res, identity(), 0)
        return b.build()
    }

    // ----- 版面累积 -----

    private class Builder(val geom: PageGeom) {
        val sb = StringBuilder()
        var boxes = FloatArray(4 * 512)
        var real = BooleanArray(512)
        var n = 0
        var hasPen = false
        var penEx = 0f
        var penOy = 0f
        var penH = 0f
        var lastChar = '\n'
        private val tmp = FloatArray(4)
        private val last = FloatArray(4)

        private fun ensure() {
            if (n >= real.size) {
                real = real.copyOf(real.size * 2)
                boxes = boxes.copyOf(boxes.size * 2)
            }
        }

        private fun add(ch: Char, x0: Float, y0: Float, x1: Float, y1: Float, r: Boolean) {
            ensure()
            sb.append(ch)
            boxes[4 * n] = x0
            boxes[4 * n + 1] = y0
            boxes[4 * n + 2] = x1
            boxes[4 * n + 3] = y1
            real[n] = r
            n++
        }

        private fun synth(ch: Char) {
            if (n == 0) return
            add(ch, last[2], last[1], last[2], last[3], false)
            lastChar = ch
        }

        fun glyph(
            s: String, minX: Float, minY: Float, maxX: Float, maxY: Float,
            ox: Float, oy: Float, ex: Float
        ) {
            if (s.isEmpty() || n >= MAX_CHARS) return
            val h = (maxY - minY).coerceAtLeast(1e-3f)
            if (hasPen) {
                val ref = max(h, penH)
                val dy = abs(oy - penOy)
                val gap = ox - penEx
                if (dy > 0.5f * ref || gap < -1.5f * ref) synth('\n')
                else if (gap > 0.18f * ref && lastChar != ' ' && lastChar != '\n' &&
                    s[0] != ' '
                ) synth(' ')
            }
            val k = s.length
            for (idx in 0 until k) {
                val x0 = minX + (maxX - minX) * idx / k
                val x1 = minX + (maxX - minX) * (idx + 1) / k
                geom.rect(x0, minY, x1, maxY, tmp, 0)
                add(s[idx], tmp[0], tmp[1], tmp[2], tmp[3], true)
                last[0] = tmp[0]; last[1] = tmp[1]; last[2] = tmp[2]; last[3] = tmp[3]
            }
            lastChar = s[k - 1]
            penEx = ex
            penOy = oy
            penH = h
            hasPen = true
        }

        fun build(): PageTextLayout? {
            var end = n
            while (end > 0 && !real[end - 1]) end--
            if (end == 0) return null
            return PageTextLayout(
                sb.substring(0, end), boxes.copyOf(4 * end), real.copyOf(end)
            )
        }

        companion object { const val MAX_CHARS = 60000 }
    }

    // ----- 内容流解释器 -----

    private class Gs(
        var ctm: FloatArray,
        var font: PdfFont? = null,
        var fs: Float = 0f,
        var tc: Float = 0f,
        var tw: Float = 0f,
        var th: Float = 1f,
        var tl: Float = 0f,
        var rise: Float = 0f
    ) {
        fun copy() = Gs(ctm, font, fs, tc, tw, th, tl, rise)
    }

    private inner class Interp(val b: Builder) {
        private var gs = Gs(identity())
        private val stack = ArrayList<Gs>()
        private var tm = identity()
        private var tlm = identity()
        private var forms = 0

        fun run(content: String, res: Map<String, Any?>?, ctm: FloatArray, depth: Int) {
            val saved = gs
            val savedStack = ArrayList(stack)
            gs = Gs(ctm, saved.font, saved.fs, saved.tc, saved.tw, saved.th, saved.tl, saved.rise)
            if (depth == 0) gs = Gs(ctm)
            stack.clear()
            val syn = PdfSyntax(content, false)
            val args = ArrayList<Any?>()
            while (true) {
                val t = syn.read()
                if (t === PdfSyntax.EOF) break
                if (t === PdfSyntax.ARR_END || t === PdfSyntax.DICT_END) continue
                if (t !is PKw) {
                    args.add(t)
                    if (args.size > 64) args.removeAt(0)
                    continue
                }
                if (b.n >= Builder.MAX_CHARS) break
                if (t.v == "BI") {
                    syn.skipInlineImage()
                    args.clear()
                    continue
                }
                op(t.v, args, res, depth)
                args.clear()
            }
            gs = saved
            stack.clear()
            stack.addAll(savedStack)
        }

        private fun f(args: List<Any?>, k: Int): Float = (args.getOrNull(k) as? Float) ?: 0f

        private fun op(name: String, a: List<Any?>, res: Map<String, Any?>?, depth: Int) {
            when (name) {
                "q" -> stack.add(gs.copy())
                "Q" -> if (stack.isNotEmpty()) gs = stack.removeAt(stack.size - 1)
                "cm" -> if (a.size >= 6) {
                    gs.ctm = mul(floatArrayOf(f(a, 0), f(a, 1), f(a, 2), f(a, 3), f(a, 4), f(a, 5)), gs.ctm)
                }
                "BT" -> { tm = identity(); tlm = identity() }
                "Tc" -> gs.tc = f(a, 0)
                "Tw" -> gs.tw = f(a, 0)
                "Tz" -> gs.th = f(a, 0) / 100f
                "TL" -> gs.tl = f(a, 0)
                "Ts" -> gs.rise = f(a, 0)
                "Tf" -> {
                    val n = (a.getOrNull(0) as? PName)?.v
                    gs.font = if (n != null) fontFor(res, n) else null
                    gs.fs = f(a, 1)
                }
                "Td" -> td(f(a, 0), f(a, 1))
                "TD" -> { gs.tl = -f(a, 1); td(f(a, 0), f(a, 1)) }
                "Tm" -> if (a.size >= 6) {
                    tm = floatArrayOf(f(a, 0), f(a, 1), f(a, 2), f(a, 3), f(a, 4), f(a, 5))
                    tlm = tm
                }
                "T*" -> td(0f, -gs.tl)
                "Tj" -> (a.lastOrNull() as? ByteArray)?.let { show(it) }
                "'" -> { td(0f, -gs.tl); (a.lastOrNull() as? ByteArray)?.let { show(it) } }
                "\"" -> {
                    gs.tw = f(a, 0)
                    gs.tc = f(a, 1)
                    td(0f, -gs.tl)
                    (a.lastOrNull() as? ByteArray)?.let { show(it) }
                }
                "TJ" -> (a.lastOrNull() as? List<*>)?.let { arr ->
                    for (e in arr) {
                        if (e is ByteArray) show(e)
                        else if (e is Float) {
                            tm = mul(floatArrayOf(1f, 0f, 0f, 1f, -e / 1000f * gs.fs * gs.th, 0f), tm)
                        }
                    }
                }
                "Do" -> form((a.getOrNull(0) as? PName)?.v, res, depth)
            }
        }

        private fun td(x: Float, y: Float) {
            tlm = mul(floatArrayOf(1f, 0f, 0f, 1f, x, y), tlm)
            tm = tlm
        }

        private fun form(name: String?, res: Map<String, Any?>?, depth: Int) {
            if (name == null || depth >= MAX_DEPTH || forms >= MAX_FORMS) return
            val ref = dict(res?.get("XObject"))?.get(name) as? PRef ?: return
            val xd = dict(ref) ?: return
            if ((resolve(xd["Subtype"]) as? PName)?.v != "Form") return
            val data = o.streams[ref.num] ?: return
            forms++
            val m = nums(xd["Matrix"])?.takeIf { it.size >= 6 } ?: identity()
            val r = dict(xd["Resources"]) ?: res
            val savedTm = tm
            val savedTlm = tlm
            run(String(data, Charsets.ISO_8859_1), r, mul(m, gs.ctm), depth + 1)
            tm = savedTm
            tlm = savedTlm
        }

        private fun show(bs: ByteArray) {
            val font = gs.font ?: return
            val fs = gs.fs
            var i = 0
            while (i < bs.size) {
                val code: Int
                val len: Int
                if (font.twoByte) {
                    if (i + 1 >= bs.size) break
                    code = ((bs[i].toInt() and 0xFF) shl 8) or (bs[i + 1].toInt() and 0xFF)
                    len = 2
                } else {
                    code = bs[i].toInt() and 0xFF
                    len = 1
                }
                i += len
                val w = font.width(code) / 1000f
                val s = font.toText(code)
                if (s != null) {
                    val trm = mul(mul(floatArrayOf(fs * gs.th, 0f, 0f, fs, 0f, gs.rise), tm), gs.ctm)
                    emit(s, trm, w, font.ascent, font.descent)
                }
                val adv = (w * fs + gs.tc + (if (len == 1 && code == 32) gs.tw else 0f)) * gs.th
                tm = mul(floatArrayOf(1f, 0f, 0f, 1f, adv, 0f), tm)
            }
        }

        private fun emit(s: String, m: FloatArray, w: Float, asc: Float, desc: Float) {
            // 字形单位框的四个角：(0,desc) (w,desc) (w,asc) (0,asc)
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (k in 0 until 4) {
                val gx = if (k == 1 || k == 2) w else 0f
                val gy = if (k < 2) desc else asc
                val x = m[0] * gx + m[2] * gy + m[4]
                val y = m[1] * gx + m[3] * gy + m[5]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            if (!minX.isFinite() || !maxX.isFinite() || !minY.isFinite() || !maxY.isFinite()) return
            b.glyph(s, minX, minY, maxX, maxY, m[4], m[5], m[0] * w + m[4])
        }
    }
}

// ---------------------------------------------------------------------------
// 点选 / 选区的纯计算（坐标一律用「逻辑像素」= 归一化 × 画布宽高）
// ---------------------------------------------------------------------------

object TextSelectionMath {

    private fun isBlank(l: PageTextLayout, i: Int): Boolean = !l.real[i] || l.text[i].isWhitespace()

    /** 手指按下处的字：外框内最优先，容差 tol 像素内取最近；找不到返回 -1。空白不算。 */
    fun hitChar(l: PageTextLayout, x: Float, y: Float, w: Float, h: Float, tol: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until l.length) {
            if (isBlank(l, i)) continue
            val x0 = l.boxes[4 * i] * w
            val y0 = l.boxes[4 * i + 1] * h
            val x1 = l.boxes[4 * i + 2] * w
            val y1 = l.boxes[4 * i + 3] * h
            val dx = max(max(x0 - x, x - x1), 0f)
            val dy = max(max(y0 - y, y - y1), 0f)
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best >= 0 && bestD <= tol * tol) best else -1
    }

    /** 拖把手时用：不设容差，先认最近的一行，再认行内最近的字。 */
    fun nearestChar(l: PageTextLayout, x: Float, y: Float, w: Float, h: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until l.length) {
            if (!l.real[i]) continue
            val x0 = l.boxes[4 * i] * w
            val y0 = l.boxes[4 * i + 1] * h
            val x1 = l.boxes[4 * i + 2] * w
            val y1 = l.boxes[4 * i + 3] * h
            val dx = max(max(x0 - x, x - x1), 0f)
            val dy = max(max(y0 - y, y - y1), 0f)
            val d = dx + 4f * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** 去掉两端的空白 / 合成字符；全空返回 null。 end 为开区间。 */
    fun trim(l: PageTextLayout, start: Int, end: Int): IntArray? {
        var a = start.coerceIn(0, l.length)
        var b = end.coerceIn(0, l.length)
        while (a < b && isBlank(l, a)) a++
        while (b > a && isBlank(l, b - 1)) b--
        return if (a < b) intArrayOf(a, b) else null
    }

    /** 长按选词：按系统分词（中文走词典分词）。 */
    fun wordAt(l: PageTextLayout, idx: Int): IntArray? {
        if (idx !in 0 until l.length || isBlank(l, idx)) return null
        var s = idx
        var e = idx + 1
        try {
            val bi = java.text.BreakIterator.getWordInstance(java.util.Locale.getDefault())
            bi.setText(l.text)
            val ps = bi.preceding(idx + 1)
            val fe = bi.following(idx)
            if (ps != java.text.BreakIterator.DONE) s = ps
            if (fe != java.text.BreakIterator.DONE) e = fe
        } catch (ex: Exception) {
            s = idx; e = idx + 1
        }
        if (s > idx || e <= idx || e - s > 48) { s = idx; e = idx + 1 }
        return trim(l, s, e)
    }

    /** 选区每一行一个矩形，flat 数组 x0,y0,x1,y1…（逻辑像素）。 */
    fun lineRects(l: PageTextLayout, start: Int, end: Int, w: Float, h: Float): FloatArray {
        val out = ArrayList<Float>()
        var has = false
        var rx0 = 0f; var ry0 = 0f; var rx1 = 0f; var ry1 = 0f
        fun flush() {
            if (has) { out.add(rx0); out.add(ry0); out.add(rx1); out.add(ry1) }
            has = false
        }
        for (i in start.coerceAtLeast(0) until end.coerceAtMost(l.length)) {
            if (!l.real[i]) { if (l.text[i] == '\n') flush(); continue }
            val x0 = l.boxes[4 * i] * w
            val y0 = l.boxes[4 * i + 1] * h
            val x1 = l.boxes[4 * i + 2] * w
            val y1 = l.boxes[4 * i + 3] * h
            if (has) {
                val yc = (y0 + y1) * 0.5f
                val cyc = (ry0 + ry1) * 0.5f
                if (abs(yc - cyc) > 0.6f * max(ry1 - ry0, y1 - y0)) flush()
            }
            if (!has) { rx0 = x0; ry0 = y0; rx1 = x1; ry1 = y1; has = true }
            else {
                rx0 = min(rx0, x0); ry0 = min(ry0, y0)
                rx1 = max(rx1, x1); ry1 = max(ry1, y1)
            }
        }
        flush()
        return out.toFloatArray()
    }
}
