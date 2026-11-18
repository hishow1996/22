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

    private val appCtx: Context = ctx.applicationContext

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

    // ----- v46：图片 OCR（与 PdfRepo 的扫描页 OCR 同一套引擎与缓存） -----
    // 一张图就是一页：识别结果转成字框层，搜索 / 命中高亮 / 长按选字 / 复制直接可用；
    // 按内容指纹落盘到 ocr-cache，重开同一张图不用再识别。

    private val ocrLock = Any()
    private var ocrLayout: PageTextLayout? = null
    private var ocrLoaded = false          // 已尝试从磁盘读过
    @Volatile private var fp: String? = null

    override fun attachFingerprint(fp: String) { this.fp = fp }

    private fun ocrFile(): java.io.File? = fp?.let { OcrCache.fileFor(appCtx.cacheDir, it) }

    /** 调用方须持有 ocrLock；null = 还没识别过（空文字的识别结果是 EMPTY，不是 null）。 */
    private fun ocrLocked(): PageTextLayout? {
        if (!ocrLoaded) {
            ocrLayout = ocrFile()?.let { OcrCache.read(it, 1) }?.getOrNull(0)
            if (ocrLayout != null || fp != null) ocrLoaded = true
        }
        return ocrLayout
    }

    override fun pageTextLayout(page: Int): PageTextLayout? =
        if (page != 0) null
        else synchronized(ocrLock) { ocrLocked()?.takeIf { it.text.isNotBlank() } }

    override fun pageText(page: Int): String? = pageTextLayout(page)?.text

    override fun extractAllText(): List<String> = listOf(pageText(0) ?: "")

    override fun ocrPending(): Int = synchronized(ocrLock) { if (ocrLocked() == null) 1 else 0 }

    /** 长边最多约 2200px（内存紧时 1800），不放大小图；调用方负责回收。 */
    private fun renderForOcr(): Bitmap {
        val side = if (Runtime.getRuntime().maxMemory() >= 192L * 1024 * 1024) 2200f else 1800f
        val a = minOf(1f, side / maxOf(srcW, srcH).toFloat())
        val cw = (srcW * a).toInt().coerceAtLeast(1)
        val ch = (srcH * a).toInt().coerceAtLeast(1)
        return drawTile(0, cw, ch, 0, 0, cw, ch)
    }

    override fun runOcr(progress: (Int, Int) -> Unit, cancelled: () -> Boolean): Int {
        if (ocrPending() == 0) return 0
        progress(0, 1)
        if (cancelled()) return 0
        val bmp = try { renderForOcr() } catch (t: Throwable) { null } ?: return 0
        val layout = try {
            OcrEngine.recognize(bmp)?.let { OcrLayoutBuilder.build(it, bmp.width, bmp.height) }
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
        if (layout == null) return 0         // 引擎失败：留给下次重试
        synchronized(ocrLock) {
            ocrLayout = layout
            ocrLoaded = true
            ocrFile()?.let { OcrCache.write(it, arrayOf<PageTextLayout?>(layout)) }
        }
        progress(1, 1)
        return 1
    }

    override fun close() {
        try { decoder?.recycle() } catch (_: Throwable) { }
        decoder = null
        fallback = null
        super.close()
    }
}
