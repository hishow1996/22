package com.moye.pdfink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object InkGeometry {

    /** 委托给纯逻辑核心，保证单测覆盖的就是运行时用的那一份。 */
    fun crop(
        marks: List<Mark>, erasePx: FloatArray, radiusPx: Float,
        w: Float, h: Float, layerFilter: (Int) -> Boolean
    ): List<Mark> = GeometryCore.crop(marks, erasePx, radiusPx, w, h, layerFilter)

    private fun widthAt(m: Mark, i: Int): Float = m.ws?.getOrElse(i) { 1f } ?: 1f

    fun drawMark(
        c: Canvas, m: Mark, w: Float, h: Float,
        paint: Paint, path: Path, textPaint: Paint = paint,
        bgPaint: Paint = paint,
        colorTransform: (Int) -> Int = { it }
    ) {
        val n = m.pts.size / 2
        if (n == 0) return
        val unit = min(w, h).coerceAtLeast(1f)
        val base = (m.width * unit).coerceAtLeast(0.8f)
        val argb = colorTransform(
            Color.argb(m.alpha, Color.red(m.color), Color.green(m.color), Color.blue(m.color))
        )

        if (m.shape == Shapes.NOTE) {
            drawNote(c, m, w, h, unit, argb, textPaint, bgPaint, colorTransform)
            return
        }

        paint.color = argb
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        if (m.shape != Shapes.FREE) {
            path.reset()
            val x0 = m.pts[0] * w
            val y0 = m.pts[1] * h
            val x1 = m.pts[(n - 1) * 2] * w
            val y1 = m.pts[(n - 1) * 2 + 1] * h
            val l = minOf(x0, x1); val t = minOf(y0, y1)
            val r = maxOf(x0, x1); val b = maxOf(y0, y1)
            when (m.shape) {
                Shapes.LINE -> { path.moveTo(x0, y0); path.lineTo(x1, y1) }
                Shapes.RECT -> path.addRect(l, t, r, b, Path.Direction.CW)
                Shapes.OVAL -> path.addOval(l, t, r, b, Path.Direction.CW)
                Shapes.ARROW -> {
                    path.moveTo(x0, y0); path.lineTo(x1, y1)
                    val ang = atan2(y1 - y0, x1 - x0)
                    val len = (base * 5f + unit * 0.018f).coerceAtLeast(unit * 0.02f)
                    val a1 = ang + 2.65f; val a2 = ang - 2.65f
                    path.moveTo(x1, y1)
                    path.lineTo(x1 + cos(a1) * len, y1 + sin(a1) * len)
                    path.moveTo(x1, y1)
                    path.lineTo(x1 + cos(a2) * len, y1 + sin(a2) * len)
                }
                else -> { path.moveTo(x0, y0); path.lineTo(x1, y1) }
            }
            paint.strokeWidth = base
            c.drawPath(path, paint)
            return
        }

        if (n == 1) {
            paint.strokeWidth = base * widthAt(m, 0)
            c.drawPoint(m.pts[0] * w, m.pts[1] * h, paint)
            return
        }

        var prevX = m.pts[0] * w
        var prevY = m.pts[1] * h
        for (i in 1 until n) {
            val cx = m.pts[2 * i] * w
            val cy = m.pts[2 * i + 1] * h
            val endX: Float
            val endY: Float
            if (i < n - 1) {
                endX = (cx + m.pts[2 * (i + 1)] * w) * 0.5f
                endY = (cy + m.pts[2 * (i + 1) + 1] * h) * 0.5f
            } else {
                endX = cx; endY = cy
            }
            path.reset()
            path.moveTo(prevX, prevY)
            if (i < n - 1) path.quadTo(cx, cy, endX, endY) else path.lineTo(cx, cy)
            paint.strokeWidth = base * (widthAt(m, i - 1) + widthAt(m, i)) * 0.5f
            c.drawPath(path, paint)
            prevX = endX; prevY = endY
        }
    }

    private fun drawNote(
        c: Canvas, m: Mark, w: Float, h: Float, unit: Float, argb: Int,
        textPaint: Paint, bgPaint: Paint, colorTransform: (Int) -> Int
    ) {
        val txt = m.text ?: return
        val lines = txt.split('\n')
        val size = (unit * 0.028f).coerceAtLeast(11f)
        textPaint.textSize = size
        textPaint.color = argb
        textPaint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.LEFT
        val widest = lines.maxOf { textPaint.measureText(it) }
        val pad = size * 0.4f
        val boxW = widest + pad * 2f
        val boxH = size * 1.35f * lines.size + pad * 2f
        val x = (m.pts[0] * w).coerceIn(0f, (w - boxW).coerceAtLeast(0f))
        val y = (m.pts[1] * h).coerceIn(0f, (h - boxH).coerceAtLeast(0f))
        bgPaint.color = colorTransform(Color.argb(225, 255, 255, 255))
        bgPaint.style = Paint.Style.FILL
        c.drawRoundRect(RectF(x, y, x + boxW, y + boxH), pad, pad, bgPaint)
        bgPaint.style = Paint.Style.STROKE
        bgPaint.strokeWidth = maxOf(1f, unit * 0.0015f)
        bgPaint.color = argb
        c.drawRoundRect(RectF(x, y, x + boxW, y + boxH), pad, pad, bgPaint)
        var by = y + pad + size
        for (line in lines) {
            c.drawText(line, x + pad, by, textPaint)
            by += size * 1.35f
        }
    }

    /**
     * 便签实际占的像素矩形 [l, t, r, b]；没有文本返回 null。
     * 供 InkView 的分块层判断便签会碰到哪几块。算法必须与 [drawNote] 保持一致。
     */
    fun noteBox(m: Mark, w: Float, h: Float, textPaint: Paint): FloatArray? {
        val txt = m.text ?: return null
        if (m.pts.size < 2) return null
        val lines = txt.split('\n')
        val unit = min(w, h).coerceAtLeast(1f)
        val size = (unit * 0.028f).coerceAtLeast(11f)
        textPaint.textSize = size
        val widest = lines.maxOf { textPaint.measureText(it) }
        val pad = size * 0.4f
        val boxW = widest + pad * 2f
        val boxH = size * 1.35f * lines.size + pad * 2f
        val x = (m.pts[0] * w).coerceIn(0f, (w - boxW).coerceAtLeast(0f))
        val y = (m.pts[1] * h).coerceIn(0f, (h - boxH).coerceAtLeast(0f))
        return floatArrayOf(x, y, x + boxW, y + boxH)
    }

    fun shapeMark(
        color: Int, width: Float, alpha: Int, shape: Int,
        layer: Int, x0: Float, y0: Float, x1: Float, y1: Float
    ): Mark = Mark(
        color, width, alpha, shape,
        floatArrayOf(x0, y0, x1, y1), floatArrayOf(1f, 1f), layer
    )
}
