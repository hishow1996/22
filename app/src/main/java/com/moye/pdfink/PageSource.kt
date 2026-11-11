package com.moye.pdfink

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * 一页一页的只读内容源。PDF 由 PdfRepo 实现；图片 / 纯文本由
 * RasterPageSource 的子类实现。翻页、缩放、分块、批注、导出只依赖这个
 * 接口，加一种内嵌格式不需要再动 PagesAdapter / TiledPageView / Exporter。
 */
interface PageSource : Closeable {
    val count: Int
    val cacheId: String
    fun ratio(page: Int): Float
    fun cachedRatioWithRotation(page: Int, rotation: Int): Float?
    fun ratioWithRotation(page: Int, rotation: Int): Float

    /** 只渲染逻辑画布 (left,top) 起 tw×th 的一块，横向 1:1。 */
    fun renderTile(
        page: Int, lw: Int, lh: Int, left: Int, top: Int,
        tw: Int, th: Int, tint: Int, brightness: Float,
        rotation: Int = 0
    ): Bitmap

    /** 整页位图：导出 / 缩略图 / 离屏合成走这条。 */
    fun render(
        page: Int, w: Int, h: Int, tint: Int, brightness: Float,
        rotation: Int = 0
    ): Bitmap

    fun prefetch(page: Int, w: Int, h: Int)
    fun thumb(page: Int, w: Int): Bitmap
}

/**
 * 位图类页源的公共胶水：旋转逆映射、滤镜、整页 render、thumb、缓存清理。
 * 子类只需给出页数、比例和「画一块」的实现。
 */
abstract class RasterPageSource : PageSource {

    companion object {
        private val ids = AtomicLong()
        fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
            val d = RasterRotation.normalize(degrees)
            if (d == 0) return bmp
            val m = Matrix().apply { postRotate(d.toFloat()) }
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
    }

    override val cacheId: String = "raster-${ids.incrementAndGet()}"

    /** 未旋转逻辑画布 (cw,ch) 上画 (left,top) 起 tw×th 的一块。 */
    protected abstract fun drawTile(
        page: Int, cw: Int, ch: Int, left: Int, top: Int, tw: Int, th: Int
    ): Bitmap

    override fun cachedRatioWithRotation(page: Int, rotation: Int): Float? =
        ratio(page).let {
            if (RasterRotation.normalize(rotation) in setOf(90, 270)) 1f / it else it
        }

    override fun ratioWithRotation(page: Int, rotation: Int): Float =
        cachedRatioWithRotation(page, rotation) ?: 1f

    override fun renderTile(
        page: Int, lw: Int, lh: Int, left: Int, top: Int,
        tw: Int, th: Int, tint: Int, brightness: Float,
        rotation: Int
    ): Bitmap {
        val r = RasterRotation.inverse(lw, lh, left, top, tw, th, rotation)
        val base = drawTile(page, r.canvasW, r.canvasH,
            r.left, r.top, r.width, r.height)
        val turned = rotate(base, rotation)
        val sized = if (turned.width == tw && turned.height == th) turned
        else Bitmap.createScaledBitmap(turned, tw, th, true)
        if (sized !== turned) turned.recycle()
        // turned 与 base 可能是同一张（0°），不能对同一对象 recycle 两次
        if (sized !== base && base !== turned) base.recycle()
        val filtered = PageFilter.apply(sized, tint, brightness)
        if (filtered !== sized) sized.recycle()
        return filtered
    }

    override fun render(
        page: Int, w: Int, h: Int, tint: Int, brightness: Float,
        rotation: Int
    ): Bitmap {
        val swap = RasterRotation.normalize(rotation) in setOf(90, 270)
        val rw = if (swap) h else w
        val rh = if (swap) w else h
        val base = drawTile(page, rw, rh, 0, 0, rw, rh)
        val turned = rotate(base, rotation)
        if (turned !== base) base.recycle()
        val filtered = PageFilter.apply(turned, tint, brightness)
        if (filtered !== turned) turned.recycle()
        return filtered
    }

    override fun prefetch(page: Int, w: Int, h: Int) { /* 位图源无整页预取 */ }

    override fun thumb(page: Int, w: Int): Bitmap {
        val h = (w / ratio(page)).toInt().coerceAtLeast(1)
        return render(page, w, h, PageFilter.WHITE, 0f)
    }

    override fun close() {
        PdfTileScheduler.cancelRepository(cacheId)
        PdfBitmapCache.clearPrefix("tile:$cacheId:")
        PdfBitmapCache.clearPrefix("tile-preview:$cacheId:")
        PdfBitmapCache.clearPrefix("page:$cacheId:")
    }
}
