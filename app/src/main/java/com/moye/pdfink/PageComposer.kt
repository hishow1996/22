package com.moye.pdfink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path

/**
 * 不依赖任何已绑定 View 的离屏合成：题目 + 批注（+ 草稿）。
 * 页被回收、缩放、后台导出都能正确出图；配色与屏幕渲染共用 InkPalette。
 */
object PageComposer {

    fun compose(
        repo: PdfRepo,
        store: InkStore,
        page: Int,
        w: Int,
        h: Int,
        tint: Int,
        brightness: Float,
        layers: Set<Int>,
        spread: Boolean,
        rotation: Int = 0
    ): Bitmap {
        val gap = if (spread) (w * 0.035f).toInt() else 0
        val totalW = if (spread) w * 2 + gap else w
        val out = Bitmap.createBitmap(totalW, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFFFFFFF.toInt())

        val pdf = try {
            repo.render(page, w, h, tint, brightness, rotation)
        } catch (e: Exception) {
            return out
        }
        // render 在 WHITE/0 时可能直接返回缓存 bitmap，绝不修改它
        val m = Matrix().apply {
            setScale(w.toFloat() / pdf.width, h.toFloat() / pdf.height)
        }
        c.drawBitmap(pdf, m, null)

        val transform: (Int) -> Int = { InkPalette.mapColor(it, tint) }
        drawMarks(c, store.load("p$page"), 0f, w, h, layers, transform)
        if (spread) {
            drawMarks(c, store.load("d$page"), (w + gap).toFloat(), w, h, layers, transform)
        }
        return out
    }

    private fun drawMarks(
        c: Canvas, marks: List<Mark>, offsetX: Float, w: Int, h: Int,
        layers: Set<Int>, transform: (Int) -> Int
    ) {
        if (offsetX != 0f) {
            c.drawRect(
                offsetX, 0f, offsetX + w, h.toFloat(),
                Paint().apply { color = 0xFFFFFFFF.toInt() }
            )
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        c.save()
        c.translate(offsetX, 0f)
        c.clipRect(0f, 0f, w.toFloat(), h.toFloat())
        for (mk in marks) {
            if (mk.layer !in layers) continue
            InkGeometry.drawMark(c, mk, w.toFloat(), h.toFloat(),
                p, path, tp, bg, transform)
        }
        c.restore()
    }
}
