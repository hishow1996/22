package com.moye.pdfink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 单张图片作为一个 1 页的内容源。
 *
 * 用 BitmapRegionDecoder 按可见块解码，不做「整页缩到大图再放大」——
 * 横屏铺满 / 4× 放大时只分配当前条带，和 PDF 分块同一套内存口径。
 * 少数不支持区域解码的格式（部分 GIF / WEBP）回退到受限整图。
 */
class ImagePageSource(ctx: Context, uri: Uri) : RasterPageSource() {

    private var decoder: BitmapRegionDecoder? = null
    private var fallback: Bitmap? = null
    private val srcW: Int
    private val srcH: Int
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        // 区域解码不支持时 d 为 null，继续走受限整图回退，不能直接抛。
        val d = ctx.contentResolver.openInputStream(uri)?.use { ins ->
            try {
                BitmapRegionDecoder.newInstance(ins, false)
            } catch (_: Throwable) { null }
        }
        if (d != null) {
            decoder = d
            srcW = d.width
            srcH = d.height
        } else {
            // 回退：按最长边 1800 限制整图，避免大图直接 OOM。
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, probe)
            }
            val longest = maxOf(probe.outWidth, probe.outHeight).coerceAtLeast(1)
            val sample = (longest / 1800f).let { ceil(it).toInt().coerceAtLeast(1) }
            val b = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            } ?: throw IllegalStateException("cannot decode image uri")
            fallback = b
            srcW = b.width
            srcH = b.height
        }
        if (srcW <= 0 || srcH <= 0) throw IllegalStateException("empty image")
    }

    override val count: Int = 1

    override fun ratio(page: Int): Float =
        srcW.toFloat() / srcH.coerceAtLeast(1).toFloat()

    override fun drawTile(
        page: Int, cw: Int, ch: Int, left: Int, top: Int, tw: Int, th: Int
    ): Bitmap {
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        if (tw <= 0 || th <= 0) return out
        // A = 逻辑画布宽 / 图片宽；tile 像素 x 对应图片像素 (left+x)/A
        val a = cw.toFloat() / srcW.toFloat()
        if (a <= 0f) return out
        val x0 = floor(left / a).toInt().coerceIn(0, srcW - 1)
        val y0 = floor(top / a).toInt().coerceIn(0, srcH - 1)
        val x1 = ceil((left + tw) / a).toInt().coerceIn(x0 + 1, srcW)
        val y1 = ceil((top + th) / a).toInt().coerceIn(y0 + 1, srcH)
        val rect = Rect(x0, y0, x1, y1)
        val part: Bitmap? = try {
            val d = decoder
            if (d != null) {
                val sample = if (a >= 1f) 1
                else ceil(1f / a).toInt().coerceIn(1, 8)
                d.decodeRegion(rect, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            } else {
                fallback?.let { Bitmap.createBitmap(it, x0, y0,
                    rect.width().coerceAtMost(it.width - x0),
                    rect.height().coerceAtMost(it.height - y0)) }
            }
        } catch (_: Throwable) { null }
        if (part != null) {
            val dst = RectF(
                x0 * a - left, y0 * a - top,
                x1 * a - left, y1 * a - top
            )
            Canvas(out).drawBitmap(part, null, dst, tilePaint)
            if (part !== fallback) part.recycle()
        }
        return out
    }

    override fun close() {
        try { decoder?.recycle() } catch (_: Throwable) { }
        decoder = null
        fallback = null
        super.close()
    }
}
