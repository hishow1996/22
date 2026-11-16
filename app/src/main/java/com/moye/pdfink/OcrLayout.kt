package com.moye.pdfink

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** OCR 识别出的一个词 / 字段及其在渲染位图里的像素外框（与 ML Kit 解耦，便于纯 JVM 测试）。 */
data class OcrElement(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** 一行：按阅读顺序排好的若干元素。 */
data class OcrLine(val elements: List<OcrElement>)

/**
 * v45：把 OCR 结果转成与 PDF 文字层同构的 [PageTextLayout]，
 * 这样搜索命中高亮、页内长按选字、复制都对扫描页同样可用。
 *
 * 字框是「元素外框按字数横向均分」得到的近似值：ML Kit 只给到词 / 行级外框，
 * 选字的起止位置会比真实字符略有偏差，但不影响命中定位与高亮。
 */
object OcrLayoutBuilder {
    private fun isAsciiWord(c: Char) = c.code < 128 && c.isLetterOrDigit()

    fun build(lines: List<OcrLine>, bmpW: Int, bmpH: Int): PageTextLayout {
        if (bmpW <= 0 || bmpH <= 0) return PageTextLayout.EMPTY
        val sb = StringBuilder()
        val boxes = ArrayList<Float>()
        val real = ArrayList<Boolean>()
        fun synthetic(c: Char) {
            sb.append(c)
            boxes.add(0f); boxes.add(0f); boxes.add(0f); boxes.add(0f)
            real.add(false)
        }
        fun norm(v: Float, size: Int) = (v / size).coerceIn(0f, 1f)

        for (line in lines) {
            val els = line.elements.filter { it.text.isNotEmpty() }
            if (els.isEmpty()) continue
            if (sb.isNotEmpty()) synthetic('\n')
            var prevLast = '\u0000'
            for (e in els) {
                val t = e.text.replace('\n', ' ')
                // 英文 / 数字词之间补一个空格，中文之间不补
                if (prevLast != '\u0000' && isAsciiWord(prevLast) && isAsciiWord(t.first())) synthetic(' ')
                val w = (e.right - e.left).coerceAtLeast(0f)
                val n = t.length
                for (k in 0 until n) {
                    sb.append(t[k])
                    val x0 = e.left + w * k / n
                    val x1 = e.left + w * (k + 1) / n
                    boxes.add(norm(x0, bmpW)); boxes.add(norm(e.top, bmpH))
                    boxes.add(norm(x1, bmpW)); boxes.add(norm(e.bottom, bmpH))
                    real.add(!t[k].isWhitespace())
                }
                prevLast = t.last()
            }
        }
        if (sb.isEmpty()) return PageTextLayout.EMPTY
        return PageTextLayout(sb.toString(), boxes.toFloatArray(), real.toBooleanArray())
    }
}

/**
 * v45：OCR 结果的磁盘缓存，按文档指纹一份，支持断点续识（null = 这一页还没识别过）。
 * 识别一页要 1–2 秒，几十页的扫描件不该每次重来，也不该中途退出就全丢。
 * 格式（整体 gzip）：魔数 · 版本 · 页数 · 每页「0 | 1 + 字数 + UTF-8 + 字框 + 真字标记」。
 */
internal object OcrCache {
    const val VERSION = 1
    private const val MAGIC = 0x4D4F5843          // "MOXC"
    private const val MAX_CHARS = 200_000

    fun fileFor(dir: File, fingerprint: String): File {
        val safe = fingerprint.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)
        return File(File(dir, "ocr-cache"), "$safe.v$VERSION.gz")
    }

    fun read(file: File, expectedPages: Int): Array<PageTextLayout?>? {
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(GZIPInputStream(file.inputStream()))).use { s ->
                if (s.readInt() != MAGIC || s.readInt() != VERSION) return null
                val n = s.readInt()
                if (n != expectedPages || n < 0) return null
                val out = arrayOfNulls<PageTextLayout>(n)
                for (i in 0 until n) {
                    if (s.readByte().toInt() == 0) continue
                    val chars = s.readInt()
                    val bytes = s.readInt()
                    if (chars < 0 || chars > MAX_CHARS || bytes < 0 || bytes > MAX_CHARS * 4) return null
                    val tb = ByteArray(bytes)
                    s.readFully(tb)
                    val text = String(tb, Charsets.UTF_8)
                    if (text.length != chars) return null
                    val boxes = FloatArray(chars * 4) { s.readFloat() }
                    val real = BooleanArray(chars) { s.readByte().toInt() != 0 }
                    out[i] = if (chars == 0) PageTextLayout.EMPTY else PageTextLayout(text, boxes, real)
                }
                file.setLastModified(System.currentTimeMillis())
                out
            }
        } catch (e: Exception) {
            null
        }
    }

    fun write(file: File, pages: Array<PageTextLayout?>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(tmp.outputStream()))).use { s ->
                s.writeInt(MAGIC)
                s.writeInt(VERSION)
                s.writeInt(pages.size)
                for (l in pages) {
                    if (l == null) { s.writeByte(0); continue }
                    s.writeByte(1)
                    val tb = l.text.toByteArray(Charsets.UTF_8)
                    s.writeInt(l.text.length)
                    s.writeInt(tb.size)
                    s.write(tb)
                    for (k in 0 until l.text.length * 4) s.writeFloat(l.boxes[k])
                    for (k in 0 until l.text.length) s.writeByte(if (l.real[k]) 1 else 0)
                }
            }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            file.parentFile?.let { TextCache.prune(it) }
        } catch (e: Exception) {
            // 缓存只是加速 / 续识，失败静默
        }
    }
}
