package com.moye.pdfink

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 一条要写进 PDF 的批注。和 [Mark] 解耦，这样整个写入器是纯 JVM，可以直接单测。
 * kind 的取值与 [Shapes] 一一对应（FREE→INK，其余同名），单测里有断言保证不漂移。
 * pts 为归一化坐标，处在「展示坐标系」：左上原点，已含 PDF 自带 /Rotate 与用户旋转。
 * HIGHLIGHT / UNDERLINE / STRIKE 的 pts 是每行一个矩形（x0,y0,x1,y1）。
 * width 归一化到页面短边。
 */
class AnnotInput(
    val kind: Int,
    val color: Int,
    val alpha: Int,
    val width: Float,
    val pts: FloatArray,
    val text: String? = null,
    /** FREETEXT：字形轮廓折线（归一化展示坐标，闭合）；用轮廓而不是字体，中文也能原样显示，不必内嵌字体。 */
    val contours: List<FloatArray>? = null,
    /** FREETEXT：是否画半透明白底。 */
    val fill: Boolean = false
) {
    companion object {
        const val INK = 0
        const val LINE = 1
        const val RECT = 2
        const val OVAL = 3
        const val ARROW = 4
        const val NOTE = 5
        const val HIGHLIGHT = 6
        const val UNDERLINE = 7
        const val STRIKE = 8
        /** v53：键盘输入的文字批注框。pts = 外框 l,t,r,b（展示归一化），text = 文字。 */
        const val FREETEXT = 9
    }
}

/**
 * 在原 PDF 末尾追加一次「增量更新」，把墨页的批注写成标准 PDF 注释
 * （Ink / Line / Square / Circle / Text / Highlight / Underline / StrikeOut），
 * WPS、Acrobat、系统阅读器都能看到，也能继续编辑；原文件的字节一个不动。
 *
 * 做法：新批注 + 它们的外观流 + 被改写的页对象（带上 /Annots）追加在文件后面，
 * 再接一段新的交叉引用（原文件用交叉引用表就写表，用交叉引用流就写流），/Prev 指回旧的。
 *
 * 不支持（返回 -1，什么都不写）：加密 PDF、读不出页树的文件。
 */
object PdfAnnotWriter {

    class Plan(val update: ByteArray, val count: Int)

    /** 返回写入的批注条数；不支持返回 -1，此时 [out] 里没有写任何字节。 */
    fun write(
        source: ByteArray,
        perPage: Map<Int, List<AnnotInput>>,
        userRotation: Int,
        out: OutputStream
    ): Int {
        val plan = try {
            plan(source, perPage, userRotation)
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        } ?: return -1
        if (plan.count == 0) return 0
        out.write(source)
        out.write(plan.update)
        return plan.count
    }

    // ------------------------------------------------------------------
    // 计划：生成要追加的字节
    // ------------------------------------------------------------------

    internal fun plan(
        source: ByteArray,
        perPage: Map<Int, List<AnnotInput>>,
        userRotation: Int
    ): Plan? {
        if (source.isEmpty() || source.size > PdfTextExtractor.MAX_SOURCE_BYTES) return null
        val text = String(source, Charsets.ISO_8859_1)
        if (text.contains("/Encrypt")) return null
        val objs = PdfTextExtractor.collect(text, newestWins = true)
        val pages = PdfTextExtractor.pageOrder(objs, text)
        if (pages.isEmpty()) return null
        val tr = readTrailer(text) ?: return null
        val doc = Doc(objs)

        val lastByte = source[source.size - 1].toInt()
        val prefix = if (lastByte == '\n'.code || lastByte == '\r'.code) "" else "\n"
        val base = source.size + prefix.length

        val body = ByteArrayOutputStream()
        val entries = ArrayList<IntArray>()           // [编号, 代数, 偏移]
        var next = max(tr.size, (objs.bodies.keys.maxOrNull() ?: 0) + 1)
        var count = 0

        fun emit(num: Int, gen: Int, bytes: ByteArray) {
            entries.add(intArrayOf(num, gen, base + body.size()))
            body.write(bytes)
        }

        val userRot = ((userRotation % 360) + 360) % 360
        for (pageIndex in perPage.keys.sorted()) {
            val list = perPage[pageIndex] ?: continue
            if (list.isEmpty()) continue
            val pageNum = pages.getOrNull(pageIndex) ?: continue
            val pageBody = objs.bodies[pageNum] ?: continue
            val pageDict = doc.dict(PRef(pageNum)) ?: continue
            val geom = doc.geometry(pageDict, userRot) ?: continue

            val newRefs = ArrayList<Int>()
            for (a in list) {
                val built = AnnotBuilder.build(a, geom) ?: continue
                val annotNum = next++
                var apNum = -1
                if (built.ap != null) apNum = next++
                emit(annotNum, 0, annotObject(annotNum, built, pageNum, apNum))
                if (built.ap != null) emit(apNum, 0, apObject(apNum, built.ap))
                newRefs.add(annotNum)
                count++
            }
            if (newRefs.isEmpty()) continue

            // 页对象（或它指向的 /Annots 数组）出一份新版本，带上新批注
            val refs = newRefs.joinToString(" ") { "$it 0 R" }
            val annots = pageDict["Annots"]
            if (annots is PRef) {
                val ab = objs.bodies[annots.num]?.trim()
                if (ab != null && ab.startsWith("[") && ab.endsWith("]")) {
                    val nb = ab.substring(0, ab.length - 1).trimEnd() + " " + refs + "]"
                    emit(annots.num, genOf(text, annots.num), objectBytes(annots.num, genOf(text, annots.num), nb))
                } else {
                    // 数组对象读不明白：宁可放弃这一页的写入，也不破坏原文件
                    rollbackLast(entries, newRefs.size * 2)
                    count -= newRefs.size
                    continue
                }
            } else {
                val nb = pageWithAnnots(pageBody, annots, refs)
                if (nb == null) {
                    rollbackLast(entries, newRefs.size * 2)
                    count -= newRefs.size
                    continue
                }
                emit(pageNum, genOf(text, pageNum), objectBytes(pageNum, genOf(text, pageNum), nb))
            }
        }
        if (count == 0) return Plan(ByteArray(0), 0)

        // 交叉引用
        val xrefPos = base + body.size()
        val out = ByteArrayOutputStream()
        out.write(prefix.toByteArray(Charsets.ISO_8859_1))
        out.write(body.toByteArray())

        val idText = if (tr.id.isNullOrEmpty()) "" else
            " /ID [" + tr.id.joinToString("") { "<" + hex(it) + ">" } + "]"
        val infoText = if (tr.info != null) " /Info ${tr.info} 0 R" else ""

        if (!tr.stream) {
            val sorted = entries.sortedBy { it[0] }
            val sb = StringBuilder("xref\n")
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1][0] == sorted[j][0] + 1) j++
                sb.append(sorted[i][0]).append(' ').append(j - i + 1).append('\n')
                for (k in i..j) {
                    sb.append(String.format(Locale.ROOT, "%010d %05d n \n", sorted[k][2], sorted[k][1]))
                }
                i = j + 1
            }
            sb.append("trailer\n<< /Size ").append(next)
                .append(" /Root ").append(tr.root).append(" 0 R")
                .append(" /Prev ").append(tr.prev)
                .append(infoText).append(idText).append(" >>\nstartxref\n")
                .append(xrefPos).append("\n%%EOF\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        } else {
            val xnum = next++
            entries.add(intArrayOf(xnum, 0, xrefPos))
            val sorted = entries.sortedBy { it[0] }
            val index = StringBuilder()
            val data = ByteArrayOutputStream()
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1][0] == sorted[j][0] + 1) j++
                index.append(sorted[i][0]).append(' ').append(j - i + 1).append(' ')
                for (k in i..j) {
                    data.write(1)
                    val off = sorted[k][2]
                    data.write((off ushr 24) and 0xFF); data.write((off ushr 16) and 0xFF)
                    data.write((off ushr 8) and 0xFF); data.write(off and 0xFF)
                    data.write((sorted[k][1] ushr 8) and 0xFF); data.write(sorted[k][1] and 0xFF)
                }
                i = j + 1
            }
            val raw = data.toByteArray()
            val head = "$xnum 0 obj\n<< /Type /XRef /Size $next /W [1 4 2] /Index [${index.toString().trim()}]" +
                " /Root ${tr.root} 0 R /Prev ${tr.prev}$infoText$idText /Length ${raw.size} >>\nstream\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(raw)
            out.write("\nendstream\nendobj\nstartxref\n$xrefPos\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        }
        return Plan(out.toByteArray(), count)
    }

    /** 回滚刚写进 entries 的 n 个条目（body 里已写的字节成为无人引用的孤儿对象，无害）。 */
    private fun rollbackLast(entries: ArrayList<IntArray>, n: Int) {
        repeat(n.coerceAtMost(entries.size)) { entries.removeAt(entries.size - 1) }
    }

    private fun genOf(text: String, num: Int): Int {
        val m = Regex("(?<![0-9])$num\\s+(\\d+)\\s+obj").findAll(text).lastOrNull() ?: return 0
        return m.groupValues[1].toIntOrNull() ?: 0
    }

    private fun objectBytes(num: Int, gen: Int, body: String): ByteArray =
        "$num $gen obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1)

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format(Locale.ROOT, "%02X", x.toInt() and 0xFF))
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 页对象改写
    // ------------------------------------------------------------------

    /** 给页字典加上（或续上）/Annots；写不出可靠结果返回 null。 */
    internal fun pageWithAnnots(body: String, annots: Any?, refs: String): String? {
        val b = body.trim()
        if (annots is List<*>) {
            val key = Regex("/Annots(?![A-Za-z0-9])").find(b) ?: return null
            var p = key.range.last + 1
            while (p < b.length && PdfSyntax.isWs(b[p])) p++
            if (p >= b.length || b[p] != '[') return null
            val close = matchBracket(b, p)
            if (close < 0) return null
            return b.substring(0, close).trimEnd() + " " + refs + b.substring(close)
        }
        if (annots != null) return null              // 既不是数组也不是引用：不碰
        val end = b.lastIndexOf(">>")
        if (end < 0 || !b.startsWith("<<")) return null
        return b.substring(0, end).trimEnd() + "\n/Annots [" + refs + "]\n" + b.substring(end)
    }

    /** 从 [open]（'[' 的位置）找匹配的 ']'，跳过字符串与嵌套；找不到返回 -1。 */
    internal fun matchBracket(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            val c = s[i]
            when {
                c == '(' -> {
                    var d = 1
                    i++
                    while (i < s.length && d > 0) {
                        when (s[i]) {
                            '\\' -> i++
                            '(' -> d++
                            ')' -> d--
                        }
                        i++
                    }
                    continue
                }
                c == '[' -> depth++
                c == ']' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    // ------------------------------------------------------------------
    // 文档访问（只用到页树、几何、继承属性）
    // ------------------------------------------------------------------

    private class Doc(private val o: PdfTextExtractor.Objects) {
        private val parsed = HashMap<Int, Any?>()

        private fun obj(num: Int): Any? {
            if (parsed.containsKey(num)) return parsed[num]
            val body = o.bodies[num]
            val v = if (body == null) null else PdfSyntax(body, true).read().let {
                if (it === PdfSyntax.EOF) null else it
            }
            parsed[num] = v
            return v
        }

        fun resolve(v: Any?): Any? {
            var cur = v
            var guard = 0
            while (cur is PRef && guard++ < 8) cur = obj(cur.num)
            return cur
        }

        @Suppress("UNCHECKED_CAST")
        fun dict(v: Any?): Map<String, Any?>? = resolve(v) as? Map<String, Any?>

        private fun nums(v: Any?): FloatArray? {
            val l = resolve(v) as? List<*> ?: return null
            val out = FloatArray(l.size)
            for (k in l.indices) out[k] = (resolve(l[k]) as? Float) ?: return null
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

        /** 页面几何，与 PdfLayoutDoc 的取框、旋转口径一致。 */
        fun geometry(page: Map<String, Any?>, userRot: Int): Geom? {
            val media = nums(inherited(page, "MediaBox"))?.takeIf { it.size >= 4 }
                ?: floatArrayOf(0f, 0f, 612f, 792f)
            val crop = nums(inherited(page, "CropBox"))?.takeIf { it.size >= 4 } ?: media
            var l = max(min(crop[0], crop[2]), min(media[0], media[2]))
            var b = max(min(crop[1], crop[3]), min(media[1], media[3]))
            var r = min(max(crop[0], crop[2]), max(media[0], media[2]))
            var t = min(max(crop[1], crop[3]), max(media[1], media[3]))
            if (r - l < 1f || t - b < 1f) {
                l = min(media[0], media[2]); b = min(media[1], media[3])
                r = max(media[0], media[2]); t = max(media[1], media[3])
            }
            if (r - l < 1f || t - b < 1f) return null
            val rot = ((resolve(inherited(page, "Rotate")) as? Float)?.toInt() ?: 0)
            val pdfRot = (((rot % 360) + 360) % 360).let { (it / 90) * 90 }
            return Geom(l, b, r - l, t - b, (pdfRot + userRot) % 360)
        }
    }

    // ------------------------------------------------------------------
    // 坐标
    // ------------------------------------------------------------------

    /** total = PDF 自带 /Rotate + 用户旋转（顺时针，90 的倍数）。 */
    internal class Geom(val l: Float, val b: Float, val w: Float, val h: Float, total: Int) {
        private val inv = (360 - total) % 360
        private val tmp = FloatArray(2)
        val unit: Float get() = min(w, h)

        /** 展示后的页面宽 / 高（点）：旋转 90 / 270 时与 MediaBox 的宽高互换。 */
        val dispW: Float = if (total % 180 == 90) h else w
        val dispH: Float = if (total % 180 == 90) w else h

        /** 展示归一化 (u,v)（左上原点）→ PDF 用户空间。 */
        fun toPdf(u: Float, v: Float, out: FloatArray) {
            rotateNorm(inv, u, v, tmp)
            out[0] = l + tmp[0] * w
            out[1] = b + (1f - tmp[1]) * h
        }
    }

    // ------------------------------------------------------------------
    // 批注对象与外观流
    // ------------------------------------------------------------------

    internal class Ap(val bbox: FloatArray, val resources: String, val content: String)

    internal class Built(val dict: String, val ap: Ap?)

    private fun annotObject(num: Int, built: Built, pageNum: Int, apNum: Int): ByteArray {
        val ap = if (apNum >= 0) " /AP << /N $apNum 0 R >>" else ""
        val d = "<< /Type /Annot ${built.dict} /P $pageNum 0 R$ap >>"
        return objectBytes(num, 0, d)
    }

    private fun apObject(num: Int, ap: Ap): ByteArray {
        val raw = ap.content.toByteArray(Charsets.ISO_8859_1)
        val deflated = if (raw.size > 400) deflate(raw) else null
        val data = deflated ?: raw
        val filter = if (deflated != null) " /Filter /FlateDecode" else ""
        val head = "$num 0 obj\n<< /Type /XObject /Subtype /Form /BBox [${AnnotBuilder.nums(ap.bbox)}]" +
            " /Resources ${ap.resources}$filter /Length ${data.size} >>\nstream\n"
        val out = ByteArrayOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_SPEED)
        d.setInput(raw)
        d.finish()
        val out = ByteArrayOutputStream(raw.size / 2 + 16)
        val buf = ByteArray(8192)
        while (!d.finished()) {
            val n = d.deflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        d.end()
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // 尾部：找最后一段交叉引用的信息
    // ------------------------------------------------------------------

    internal class Trailer(
        val root: Int, val size: Int, val info: Int?, val id: List<ByteArray>?,
        val prev: Long, val stream: Boolean
    )

    internal fun readTrailer(text: String): Trailer? {
        val sx = text.lastIndexOf("startxref")
        if (sx < 0) return null
        val m = Regex("""startxref\s+(\d+)""").find(text, sx) ?: return null
        val pos = m.groupValues[1].toLongOrNull() ?: return null
        var table = false
        var dictAt = -1
        if (pos in 0 until text.length) {
            var p = pos.toInt()
            while (p < text.length && PdfSyntax.isWs(text[p])) p++
            if (text.startsWith("xref", p)) {
                table = true
                val t = text.indexOf("trailer", p)
                if (t >= 0) dictAt = text.indexOf("<<", t)
            } else if (Regex("""\d+\s+\d+\s+obj""").matchAt(text, p) != null) {
                dictAt = text.indexOf("<<", p)
            }
        }
        if (dictAt < 0) {
            // startxref 指偏了：退到最后一个 trailer 字典
            val t = text.lastIndexOf("trailer")
            if (t < 0) return null
            table = true
            dictAt = text.indexOf("<<", t)
            if (dictAt < 0) return null
        }
        @Suppress("UNCHECKED_CAST")
        val d = PdfSyntax(text.substring(dictAt, min(text.length, dictAt + 8192)), true).read()
            as? Map<String, Any?> ?: return null
        val root = (d["Root"] as? PRef)?.num ?: return null
        val size = (d["Size"] as? Float)?.toInt() ?: 0
        val info = (d["Info"] as? PRef)?.num
        val id = (d["ID"] as? List<*>)?.mapNotNull { it as? ByteArray }
        return Trailer(root, size, info, id, pos, !table)
    }
}

/** 把一条 [AnnotInput] 变成标注字典 + 外观流。 */
internal object AnnotBuilder {

    fun nums(a: FloatArray): String = a.joinToString(" ") { f(it) }

    fun f(v: Float): String {
        if (v.isNaN() || v.isInfinite()) return "0"
        var s = String.format(Locale.ROOT, "%.3f", v)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return if (s == "-0" || s.isEmpty()) "0" else s
    }

    private fun rgb(color: Int): FloatArray = floatArrayOf(
        ((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f
    )

    private fun alphaOf(a: AnnotInput): Float = (a.alpha.coerceIn(0, 255)) / 255f

    private fun gsResources(alpha: Float, multiply: Boolean): String {
        if (alpha >= 0.999f && !multiply) return "<< >>"
        val bm = if (multiply) " /BM /Multiply" else ""
        return "<< /ExtGState << /G0 << /CA ${f(alpha)} /ca ${f(alpha)}$bm >> >> >>"
    }

    private fun pdfString(s: String): String {
        val ascii = s.all { it.code in 32..126 }
        if (ascii) {
            val sb = StringBuilder("(")
            for (c in s) {
                if (c == '(' || c == ')' || c == '\\') sb.append('\\')
                sb.append(c)
            }
            return sb.append(')').toString()
        }
        val sb = StringBuilder("<FEFF")
        for (c in s) sb.append(String.format(Locale.ROOT, "%04X", c.code))
        return sb.append('>').toString()
    }

    fun build(a: AnnotInput, g: PdfAnnotWriter.Geom): PdfAnnotWriter.Built? = when (a.kind) {
        AnnotInput.INK -> ink(a, g)
        AnnotInput.LINE, AnnotInput.ARROW -> line(a, g)
        AnnotInput.RECT, AnnotInput.OVAL -> box(a, g)
        AnnotInput.NOTE -> note(a, g)
        AnnotInput.FREETEXT -> freeText(a, g)
        AnnotInput.HIGHLIGHT, AnnotInput.UNDERLINE, AnnotInput.STRIKE -> textMarkup(a, g)
        else -> null
    }

    private fun lineWidth(a: AnnotInput, g: PdfAnnotWriter.Geom, min: Float): Float =
        max(a.width * g.unit, min)

    private fun ink(a: AnnotInput, g: PdfAnnotWriter.Geom): PdfAnnotWriter.Built? {
        val n = a.pts.size / 2
        if (n == 0) return null
        val xy = FloatArray(max(n, 2) * 2)
        val p = FloatArray(2)
        for (i in 0 until n) {
            g.toPdf(a.pts[2 * i], a.pts[2 * i + 1], p)
            xy[2 * i] = p[0]; xy[2 * i + 1] = p[1]
        }
        if (n == 1) { xy[2] = xy[0]; xy[3] = xy[1] }
        val lw = lineWidth(a, g, 0.4f)
        val b = bounds(xy, lw / 2f + 0.5f)
        val c = rgb(a.color)
        val alpha = alphaOf(a)
        val sb = StringBuilder()
        if (alpha < 0.999f) sb.append("/G0 gs\n")
        sb.append(nums(c)).append(" RG ").append(f(lw)).append(" w 1 J 1 j\n")
        sb.append(f(xy[0])).append(' ').append(f(xy[1])).append(" m\n")
        var i = 2
        while (i + 1 < xy.size) { sb.append(f(xy[i])).append(' ').append(f(xy[i + 1])).append(" l\n"); i += 2 }
        sb.append("S\n")
        val dict = "/Subtype /Ink /Rect [${nums(b)}] /InkList [[${nums(xy)}]] /C [${nums(c)}] " +
            "/CA ${f(alpha)} /BS << /W ${f(lw)} /S /S >> /F 4"
        return PdfAnnotWriter.Built(dict, PdfAnnotWriter.Ap(b, gsResources(alpha, false), "q\n$sb" + "Q\n"))
    }

    private fun line(a: AnnotInput, g: PdfAnnotWriter.Geom): PdfAnnotWriter.Built? {
        if (a.pts.size < 4) return null
        val n = a.pts.size / 2
        val p = FloatArray(2)
        g.toPdf(a.pts[0], a.pts[1], p)
        val x0 = p[0]; val y0 = p[1]
        g.toPdf(a.pts[2 * (n - 1)], a.pts[2 * (n - 1) + 1], p)
        val x1 = p[0]; val y1 = p[1]
        val lw = lineWidth(a, g, 0.5f)
        val arrow = a.kind == AnnotInput.ARROW
        val c = rgb(a.color)
        val alpha = alphaOf(a)
        val sb = StringBuilder()
        if (alpha < 0.999f) sb.append("/G0 gs\n")
        sb.append(nums(c)).append(" RG ").append(f(lw)).append(" w 1 J 1 j\n")
        sb.append(f(x0)).append(' ').append(f(y0)).append(" m ").append(f(x1)).append(' ').append(f(y1)).append(" l S\n")
        val pts = arrayListOf(x0, y0, x1, y1)
        if (arrow) {
            // 箭头张角与屏幕上一致（尖端两侧各 2.65 弧度），长度随线宽与页面大小变化
            val ang = atan2(y1 - y0, x1 - x0)
            val len = max(lw * 5f + g.unit * 0.018f, g.unit * 0.02f)
            for (da in floatArrayOf(2.65f, -2.65f)) {
                val hx = x1 + cos(ang + da) * len
                val hy = y1 + sin(ang + da) * len
                sb.append(f(x1)).append(' ').append(f(y1)).append(" m ")
                    .append(f(hx)).append(' ').append(f(hy)).append(" l S\n")
                pts.add(hx); pts.add(hy)
            }
        }
        val b = bounds(pts.toFloatArray(), lw / 2f + 0.5f)
        val le = if (arrow) "[/None /OpenArrow]" else "[/None /None]"
        val dict = "/Subtype /Line /Rect [${nums(b)}] /L [${nums(floatArrayOf(x0, y0, x1, y1))}] /LE $le " +
            "/C [${nums(c)}] /CA ${f(alpha)} /BS << /W ${f(lw)} /S /S >> /F 4"
        return PdfAnnotWriter.Built(dict, PdfAnnotWriter.Ap(b, gsResources(alpha, false), "q\n$sb" + "Q\n"))
    }

    private fun box(a: AnnotInput, g: PdfAnnotWriter.Geom): PdfAnnotWriter.Built? {
        if (a.pts.size < 4) return null
        val n = a.pts.size / 2
        val p = FloatArray(2)
        g.toPdf(a.pts[0], a.pts[1], p)
        val xa = p[0]; val ya = p[1]
        g.toPdf(a.pts[2 * (n - 1)], a.pts[2 * (n - 1) + 1], p)
        val l = min(xa, p[0]); val r = max(xa, p[0])
        val bt = min(ya, p[1]); val t = max(ya, p[1])
        val lw = lineWidth(a, g, 0.5f)
        val c = rgb(a.color)
        val alpha = alphaOf(a)
        val sb = StringBuilder()
        if (alpha < 0.999f) sb.append("/G0 gs\n")
        sb.append(nums(c)).append(" RG ").append(f(lw)).append(" w 1 j\n")
        if (a.kind == AnnotInput.RECT) {
            sb.append(f(l)).append(' ').append(f(bt)).append(' ')
                .append(f(r - l)).append(' ').append(f(t - bt)).append(" re S\n")
        } else {
            val k = 0.5523f
            val cx = (l + r) / 2f; val cy = (bt + t) / 2f
            val rx = (r - l) / 2f; val ry = (t - bt) / 2f
            fun pt(x: Float, y: Float) = f(x) + " " + f(y)
            sb.append(pt(cx + rx, cy)).append(" m\n")
            sb.append(pt(cx + rx, cy + k * ry)).append(' ').append(pt(cx + k * rx, cy + ry)).append(' ').append(pt(cx, cy + ry)).append(" c\n")
            sb.append(pt(cx - k * rx, cy + ry)).append(' ').append(pt(cx - rx, cy + k * ry)).append(' ').append(pt(cx - rx, cy)).append(" c\n")
            sb.append(pt(cx - rx, cy - k * ry)).append(' ').append(pt(cx - k * rx, cy - ry)).append(' ').append(pt(cx, cy - ry)).append(" c\n")
            sb.append(pt(cx + k * rx, cy - ry)).append(' ').append(pt(cx + rx, cy - k * ry)).append(' ').append(pt(cx + rx, cy)).append(" c S\n")
        }
        val pad = lw / 2f + 0.5f
        val b = floatArrayOf(l - pad, bt - pad, r + pad, t + pad)
        val sub = if (a.kind == AnnotInput.RECT) "Square" else "Circle"
        val dict = "/Subtype /$sub /Rect [${nums(b)}] /C [${nums(c)}] /CA ${f(alpha)} " +
            "/BS << /W ${f(lw)} /S /S >> /F 4"
        return PdfAnnotWriter.Built(dict, PdfAnnotWriter.Ap(b, gsResources(alpha, false), "q\n$sb" + "Q\n"))
    }

    private fun note(a: AnnotInput, g: PdfAnnotWriter.Geom): PdfAnnotWriter.Built? {
        val txt = a.text ?: return null
        if (a.pts.size < 2) return null
        val p = FloatArray(2)
        g.toPdf(a.pts[0], a.pts[1], p)
        val s = 18f
        val b = floatArrayOf(p[0], p[1] - s, p[0] + s, p[1])
        // 便签图标：黄底深边的小方块 + 三条「文字」横线
        val ink = rgb(a.color)
        val sb = StringBuilder()
        sb.append("1 0.86 0.2 rg ").append(nums(ink)).append(" RG 1 w\n")
        sb.append(f(b[0] + 0.5f)).append(' ').append(f(b[1] + 0.5f)).append(' ')
            .append(f(s - 1f)).append(' ').append(f(s - 1f)).append(" re B\n")
        for (k in 1..3) {
            val y = b[1] + s * (0.25f * k) + 0.5f
            sb.append(f(b[0] + 3.5f)).append(' ').append(f(y)).append(" m ")
                .append(f(b[2] - 3.5f)).append(' ').append(f(y)).append(" l S\n")
        }
        val dict = "/Subtype /Text /Rect [${nums(b)}] /Contents ${pdfString(txt)} /Name /Comment " +
            "/C [1 0.86 0.2] /Open false /F 28"
        return PdfAnnotWriter.Built(dict, PdfAnnotWriter.Ap(b, "<< >>", "q\n$sb" + "Q\n"))
    }

    /**
     * 文字批注框 → FreeText。外观流里画的是字形轮廓（填充），不依赖任何字体，中文也一样；
     * /Contents 放原文，别的阅读器里还能点开看 / 改文字。
     */
    private fun freeText(a: AnnotInput, g: PdfAnnotWriter.Geom): PdfAnnotWriter.Built? {
        val txt = a.text ?: return null
        val cs = a.contours ?: return null
        if (a.pts.size < 4) return null
        val p = FloatArray(2); val q = FloatArray(2)
        g.toPdf(a.pts[0], a.pts[1], p); g.toPdf(a.pts[2], a.pts[3], q)
        val b = floatArrayOf(min(p[0], q[0]), min(p[1], q[1]), max(p[0], q[0]), max(p[1], q[1]))
        val ink = rgb(a.color)
        val sb = StringBuilder()
        if (a.fill) {
            sb.append("q /G0 gs 1 1 1 rg ").append(f(b[0])).append(' ').append(f(b[1])).append(' ')
                .append(f(b[2] - b[0])).append(' ').append(f(b[3] - b[1])).append(" re f Q\n")
            sb.append("q ").append(nums(ink)).append(" RG 0.6 w ").append(f(b[0])).append(' ')
                .append(f(b[1])).append(' ').append(f(b[2] - b[0])).append(' ')
                .append(f(b[3] - b[1])).append(" re S Q\n")
        }
        sb.append(nums(ink)).append(" rg\n")
        val t = FloatArray(2)
        for (c in cs) {
            val n = c.size / 2
            if (n < 3) continue
            for (i in 0 until n) {
                g.toPdf(c[2 * i], c[2 * i + 1], t)
                sb.append(f(t[0])).append(' ').append(f(t[1])).append(if (i == 0) " m\n" else " l\n")
            }
            sb.append("h\n")
        }
        sb.append("f\n")
        val da = "(/Helv 12 Tf ${nums(ink)} rg)"
        val dict = "/Subtype /FreeText /Rect [${nums(b)}] /Contents ${pdfString(txt)} /DA $da /F 4"
        val res = "<< /ExtGState << /G0 << /CA 0.88 /ca 0.88 >> >> >>"
        return PdfAnnotWriter.Built(dict, PdfAnnotWriter.Ap(b, res, "q\n$sb" + "Q\n"))
    }

    private fun textMarkup(a: AnnotInput, g: PdfAnnotWriter.Geom): PdfAnnotWriter.Built? {
        val rects = a.pts.size / 4
        if (rects == 0) return null
        val c = rgb(a.color)
        val alpha = alphaOf(a)
        val lw = lineWidth(a, g, 0.6f)
        val quads = ArrayList<Float>()
        val all = ArrayList<Float>()
        val sb = StringBuilder()
        val highlight = a.kind == AnnotInput.HIGHLIGHT
        if (highlight) sb.append("/G0 gs ").append(nums(c)).append(" rg\n")
        else sb.append(nums(c)).append(" RG ").append(f(lw)).append(" w 0 J\n")
        val p = FloatArray(2)
        val q = FloatArray(2)
        for (k in 0 until rects) {
            val u0 = a.pts[4 * k]; val v0 = a.pts[4 * k + 1]
            val u1 = a.pts[4 * k + 2]; val v1 = a.pts[4 * k + 3]
            g.toPdf(u0, v0, p); g.toPdf(u1, v1, q)
            val l = min(p[0], q[0]); val r = max(p[0], q[0])
            val bt = min(p[1], q[1]); val t = max(p[1], q[1])
            quads.addAll(listOf(l, t, r, t, l, bt, r, bt))
            all.addAll(listOf(l, bt, r, t))
            if (highlight) {
                sb.append(f(l)).append(' ').append(f(bt)).append(' ')
                    .append(f(r - l)).append(' ').append(f(t - bt)).append(" re f\n")
            } else {
                // 下划线在「展示」意义上的行底、删除线在行中。又高又窄的框说明文字是竖排
                // （页面被转过 90°，或竖排中文）：线沿文字方向画，下划线放在左边。
                val vertical = (maxOf(v0, v1) - minOf(v0, v1)) * g.dispH >
                    1.5f * (maxOf(u0, u1) - minOf(u0, u1)) * g.dispW
                if (vertical) {
                    val ux = if (a.kind == AnnotInput.UNDERLINE) minOf(u0, u1) else (u0 + u1) / 2f
                    g.toPdf(ux, minOf(v0, v1), p); g.toPdf(ux, maxOf(v0, v1), q)
                } else {
                    val vy = if (a.kind == AnnotInput.UNDERLINE) maxOf(v0, v1) else (v0 + v1) / 2f
                    g.toPdf(minOf(u0, u1), vy, p); g.toPdf(maxOf(u0, u1), vy, q)
                }
                sb.append(f(p[0])).append(' ').append(f(p[1])).append(" m ")
                    .append(f(q[0])).append(' ').append(f(q[1])).append(" l S\n")
            }
        }
        val b = bounds(all.toFloatArray(), if (highlight) 0.5f else lw + 0.5f)
        val sub = when (a.kind) {
            AnnotInput.HIGHLIGHT -> "Highlight"
            AnnotInput.UNDERLINE -> "Underline"
            else -> "StrikeOut"
        }
        val dict = "/Subtype /$sub /Rect [${nums(b)}] /QuadPoints [${nums(quads.toFloatArray())}] " +
            "/C [${nums(c)}] /CA ${f(alpha)} /F 4" +
            (if (!a.text.isNullOrBlank()) " /Contents ${pdfString(a.text)}" else "")
        val res = if (highlight) gsResources(alpha, true) else gsResources(alpha, false)
        val content = if (!highlight && alpha < 0.999f) "q\n/G0 gs\n$sb" + "Q\n" else "q\n$sb" + "Q\n"
        return PdfAnnotWriter.Built(dict, PdfAnnotWriter.Ap(b, res, content))
    }

    private fun bounds(xy: FloatArray, pad: Float): FloatArray {
        var l = Float.MAX_VALUE; var b = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var t = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < xy.size) {
            l = min(l, xy[i]); r = max(r, xy[i])
            b = min(b, xy[i + 1]); t = max(t, xy[i + 1])
            i += 2
        }
        return floatArrayOf(l - pad, b - pad, r + pad, t + pad)
    }
}
