package com.moye.pdfink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff

object PageFilter {
    const val WHITE = 0
    const val WARM = 1
    const val NIGHT = 2

    fun apply(src: Bitmap, mode: Int, brightness: Float): Bitmap {
        if (mode == WHITE && brightness == 0f) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (mode == NIGHT) {
            val cm = ColorMatrix(floatArrayOf(
                -0.88f, 0f, 0f, 0f, 226f,
                0f, -0.88f, 0f, 0f, 220f,
                0f, 0f, -0.88f, 0f, 210f,
                0f, 0f, 0f, 1f, 0f
            ))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(cm)
            }
            c.drawBitmap(src, 0f, 0f, p)
        } else {
            c.drawBitmap(src, 0f, 0f, null)
            if (mode == WARM) {
                c.drawColor(0xFFF2E1C4.toInt(), PorterDuff.Mode.MULTIPLY)
            }
        }
        if (brightness > 0.01f) {
            c.drawColor(Color.argb((brightness * 90f).toInt().coerceAtMost(120), 255, 255, 255))
        } else if (brightness < -0.01f) {
            c.drawColor(Color.argb((-brightness * 130f).toInt().coerceAtMost(160), 0, 0, 0))
        }
        return out
    }
}
