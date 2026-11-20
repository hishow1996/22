package com.moye.pdfink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

/**
 * v53：文字批注框（[Shapes.TEXTBOX]）的排版 / 绘制 / 命中 / 导出轮廓。
 * 用 [StaticLayout] 自动换行，和编辑时的 EditText 同一套断行规则，所见即所得。
 */
object TextBoxGeometry {

    /** 一个框排版后的结果。像素是页面（InkView）布局像素。 */
    class Laid(
        val layout: StaticLayout,
        /** 框外框像素矩形 l,t,r,b。 */
        val box: FloatArray,
        /** 文字起点（框内边距之后）。 */
        val textX: Float,
        val textY: Float,
        val pad: Float,
        val size: Float
    )

    fun padOf(size: Float): Float = size * 0.4f

    fun lay(m: Mark, w: Float, h: Float, paint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)): Laid? {
        val txt = m.text
        if (txt.isNullOrEmpty() || m.pts.size < 4) return null
        val unit = min(w, h).coerceAtLeast(1f)
        val size = (m.width.coerceAtLeast(0.004f) * unit).coerceAtLeast(9f)
        paint.textSize = size
        val pad = padOf(size)
        val boxW = ((m.pts[2] - m.pts[0]) * w).coerceIn(size * 3f, w)
        val inner = (boxW - pad * 2f).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(txt, 0, txt.length, paint, inner)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        val boxH = layout.height + pad * 2f
        val x = (m.pts[0] * w).coerceIn(0f, (w - boxW).coerceAtLeast(0f))
        val y = (m.pts[1] * h).coerceIn(0f, (h - boxH).coerceAtLeast(0f))
        return Laid(layout, floatArrayOf(x, y, x + boxW, y + boxH), x + pad, y + pad, pad, size)
    }

    /** 框的像素矩形 [l,t,r,b]；没有文字返回 null。 */
    fun rect(m: Mark, w: Float, h: Float): FloatArray? = lay(m, w, h)?.box

    fun draw(
        c: Canvas, m: Mark, w: Float, h: Float, argb: Int,
        bgPaint: Paint, colorTransform: (Int) -> Int
    ) {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val laid = lay(m, w, h, tp) ?: return
        val b = laid.box
        val r = RectF(b[0], b[1], b[2], b[3])
        if (TextBoxMath.hasBg(m.ws)) {
            bgPaint.style = Paint.Style.FILL
            bgPaint.color = colorTransform(Color.argb(225, 255, 255, 255))
            c.drawRoundRect(r, laid.pad, laid.pad, bgPaint)
            bgPaint.style = Paint.Style.STROKE
            bgPaint.strokeWidth = max(1f, min(w, h) * 0.0012f)
            bgPaint.color = argb
            c.drawRoundRect(r, laid.pad, laid.pad, bgPaint)
        }
        tp.color = argb
        tp.style = Paint.Style.FILL
        c.save()
        c.translate(laid.textX, laid.textY)
        laid.layout.draw(c)
        c.restore()
    }

    /**
     * 导出 PDF 用：每个字形的轮廓折线（归一化页面坐标，闭合，不重复首点）+ 框的归一化外框。
     * 参考页面大小由调用方给（宽高比对就行：排版随页面等比放缩）。
     */
    fun outline(m: Mark, refW: Float, refH: Float): TextBoxOutline? {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val laid = lay(m, refW, refH, tp) ?: return null
        val txt = m.text ?: return null
        val contours = ArrayList<FloatArray>()
        val path = Path()
        val pm = PathMeasure()
        val pos = FloatArray(2)
        val step = max(0.6f, laid.size / 24f)
        for (i in 0 until laid.layout.lineCount) {
            val s = laid.layout.getLineStart(i)
            var e = laid.layout.getLineEnd(i)
            while (e > s && (txt[e - 1] == '\n' || txt[e - 1] == ' ')) e--
            if (e <= s) continue
            path.rewind()
            tp.getTextPath(txt, s, e, laid.textX + laid.layout.getLineLeft(i),
                laid.textY + laid.layout.getLineBaseline(i), path)
            pm.setPath(path, false)
            do {
                val len = pm.length
                if (len <= 0.01f) continue
                val n = max(6, (len / step).toInt())
                val out = FloatArray(n * 2)
                for (k in 0 until n) {
                    pm.getPosTan(len * k / n, pos, null)
                    out[2 * k] = pos[0] / refW
                    out[2 * k + 1] = pos[1] / refH
                }
                contours.add(out)
            } while (pm.nextContour())
        }
        val b = laid.box
        return TextBoxOutline(
            floatArrayOf(b[0] / refW, b[1] / refH, b[2] / refW, b[3] / refH),
            contours
        )
    }
}
