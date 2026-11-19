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

        if (Shapes.isTextMarkup(m.shape)) {
            drawTextMarkup(c, m, w, h, base, argb, paint)
            return
        }

        if (m.shape == Shapes.COVER) {
            drawCover(c, m, w, h, unit, argb, paint, textPaint)
            return
        }

        if (Shapes.isStamp(m.shape)) {
            drawStamp(c, m, w, h, argb, paint, path, textPaint)
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

    /** v46 答案遮罩：盖着时是实心圆角块 + 提示字；揭开后只剩虚线框和右上角小圆标（点它再盖上）。 */
    private fun drawCover(
        c: Canvas, m: Mark, w: Float, h: Float, unit: Float, argb: Int,
        paint: Paint, textPaint: Paint
    ) {
        val r = CoverGeometry.rect(m.pts) ?: return
        val l = r[0] * w; val t = r[1] * h; val rt = r[2] * w; val b = r[3] * h
        val rad = min(unit * 0.012f, min(rt - l, b - t) / 4f)
        if (!m.revealed) {
            paint.style = Paint.Style.FILL
            paint.color = argb
            c.drawRoundRect(l, t, rt, b, rad, rad, paint)
            if (rt - l > unit * 0.16f && b - t > unit * 0.035f) {
                val ts = min((b - t) * 0.4f, unit * 0.03f)
                val oldAlign = textPaint.textAlign
                val oldStyle = textPaint.style
                textPaint.style = Paint.Style.FILL
                textPaint.color = 0xCCFFFFFF.toInt()
                textPaint.textSize = ts
                textPaint.textAlign = Paint.Align.CENTER
                c.drawText("点一下揭开", (l + rt) * 0.5f, (t + b) * 0.5f + ts * 0.35f, textPaint)
                textPaint.textAlign = oldAlign
                textPaint.style = oldStyle
            }
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (unit * 0.002f).coerceAtLeast(1.2f)
            paint.color = (argb and 0x00FFFFFF) or (150 shl 24)
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
            c.drawRoundRect(l, t, rt, b, rad, rad, paint)
            paint.pathEffect = null
            val bc = CoverGeometry.badgeCenter(m.pts, w, h, unit * CoverGeometry.INSET_FRAC)
            if (bc != null) {
                val br = unit * CoverGeometry.RADIUS_FRAC
                paint.style = Paint.Style.FILL
                paint.color = argb
                c.drawCircle(bc[0], bc[1], br, paint)
                paint.color = 0xFFFFFFFF.toInt()
                c.drawCircle(bc[0], bc[1], br * 0.4f, paint)
            }
        }
    }

    /** v46 批改章：在外接矩形里居中画 ✓ / ✗ / ？。 */
    private fun drawStamp(
        c: Canvas, m: Mark, w: Float, h: Float, argb: Int,
        paint: Paint, path: Path, textPaint: Paint
    ) {
        val r = CoverGeometry.rect(m.pts) ?: return
        val l = r[0] * w; val t = r[1] * h; val rt = r[2] * w; val b = r[3] * h
        val cx = (l + rt) * 0.5f; val cy = (t + b) * 0.5f
        val s = min(rt - l, b - t)
        if (m.shape == Shapes.STAMP_Q) {
            val oldAlign = textPaint.textAlign
            val oldStyle = textPaint.style
            val oldBold = textPaint.isFakeBoldText
            textPaint.style = Paint.Style.FILL
            textPaint.color = argb
            textPaint.textSize = s * 1.05f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.isFakeBoldText = true
            c.drawText("?", cx, cy + s * 0.36f, textPaint)
            textPaint.textAlign = oldAlign
            textPaint.style = oldStyle
            textPaint.isFakeBoldText = oldBold
            return
        }
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = (s * 0.14f).coerceAtLeast(1.5f)
        paint.color = argb
        path.reset()
        if (m.shape == Shapes.STAMP_OK) {
            path.moveTo(cx - 0.30f * s, cy + 0.02f * s)
            path.lineTo(cx - 0.08f * s, cy + 0.26f * s)
            path.lineTo(cx + 0.34f * s, cy - 0.26f * s)
        } else {
            path.moveTo(cx - 0.26f * s, cy - 0.26f * s); path.lineTo(cx + 0.26f * s, cy + 0.26f * s)
            path.moveTo(cx + 0.26f * s, cy - 0.26f * s); path.lineTo(cx - 0.26f * s, cy + 0.26f * s)
        }
        c.drawPath(path, paint)
    }

    /** 高亮 = 整行半透明色块；下划线 = 行底一条线；删除线 = 行中一条线。 */
    private fun drawTextMarkup(
        c: Canvas, m: Mark, w: Float, h: Float, base: Float, argb: Int, paint: Paint
    ) {
        paint.color = argb
        paint.strokeCap = Paint.Cap.BUTT
        if (m.shape == Shapes.TEXT_HL) paint.style = Paint.Style.FILL
        else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = base.coerceAtLeast(1.2f)
        }
        var i = 0
        while (i + 3 < m.pts.size) {
            val x0 = minOf(m.pts[i], m.pts[i + 2]) * w
            val x1 = maxOf(m.pts[i], m.pts[i + 2]) * w
            val y0 = minOf(m.pts[i + 1], m.pts[i + 3]) * h
            val y1 = maxOf(m.pts[i + 1], m.pts[i + 3]) * h
            // 又高又窄的框 = 竖排文字（页面转过 90°或竖排中文）：线沿文字方向画，下划线放左边
            val vertical = (y1 - y0) > 1.5f * (x1 - x0)
            when (m.shape) {
                Shapes.TEXT_HL -> c.drawRect(x0, y0, x1, y1, paint)
                Shapes.TEXT_UL ->
                    if (vertical) c.drawLine(x0, y0, x0, y1, paint) else c.drawLine(x0, y1, x1, y1, paint)
                else -> {
                    if (vertical) { val mx = (x0 + x1) * 0.5f; c.drawLine(mx, y0, mx, y1, paint) }
                    else { val my = (y0 + y1) * 0.5f; c.drawLine(x0, my, x1, my, paint) }
                }
            }
            i += 4
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
