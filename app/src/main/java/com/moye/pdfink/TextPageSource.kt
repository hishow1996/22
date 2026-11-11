package com.moye.pdfink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 纯文本按行分页的内容源。文字用 Canvas.drawText 直接画到块上，
 * 放大时是矢量重绘而不是位图插值，4× 依然锐利。
 */
class TextPageSource(ctx: Context, uri: Uri) : RasterPageSource() {

    companion object {
        const val MAX_BYTES = 512 * 1024
        /** 委托纯 JVM 实现，便于单测。 */
        fun paginate(
            text: String, charsPerLine: Int = TextLayout.CHAR_PER_LINE,
            linesPerPage: Int = TextLayout.LINES_PER_PAGE
        ): List<List<String>> = TextLayout.paginate(text, charsPerLine, linesPerPage)
    }

    private val pages: List<List<String>>
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TextLayout.TEXT_SP
        color = 0xFF2B2F36.toInt()
    }

    init {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use {
            val buf = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            var left = MAX_BYTES
            while (left > 0) {
                val n = it.read(tmp, 0, minOf(tmp.size, left))
                if (n <= 0) break
                buf.write(tmp, 0, n)
                left -= n
            }
            buf.toByteArray()
        } ?: throw IllegalStateException("cannot open text uri")
        pages = paginate(String(bytes, Charsets.UTF_8))
    }

    override val count: Int get() = pages.size.coerceAtLeast(1)

    override fun ratio(page: Int): Float = TextLayout.BASE_W / TextLayout.CANVAS_H

    override fun drawTile(
        page: Int, cw: Int, ch: Int, left: Int, top: Int, tw: Int, th: Int
    ): Bitmap {
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        if (tw <= 0 || th <= 0) return out
        val lines = pages.getOrElse(page) { emptyList() }
        val c = Canvas(out)
        val a = cw.toFloat() / TextLayout.BASE_W
        // M = T(-left,-top) · S(a)：基准点 (u,v) → (u*a-left, v*a-top)
        c.translate(-left.toFloat(), -top.toFloat())
        c.scale(a, a)
        val first = floor((top / a - TextLayout.PAD) / TextLayout.LINE_H).toInt() - 1
        val last = ceil(((top + th) / a - TextLayout.PAD) / TextLayout.LINE_H).toInt() + 1
        for (i in maxOf(0, first)..minOf(lines.lastIndex, last)) {
            c.drawText(lines[i], TextLayout.PAD,
                TextLayout.PAD + (i + 1) * TextLayout.LINE_H - 6f, textPaint)
        }
        return out
    }
}
