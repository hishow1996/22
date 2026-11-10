package com.moye.pdfink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import kotlin.math.min
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 系统 PdfRenderer 封装：串行访问 + raw 页缓存 + 滤镜与旋转输出。 */
class PdfRepo(private val ctx: Context, uri: Uri) : Closeable {

    private val pfd: ParcelFileDescriptor =
        ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("cannot open uri")

    private val renderer = PdfRenderer(pfd)
    private val ratios = ConcurrentHashMap<Int, Float>()
    val cacheId: String = "doc-${nextId.incrementAndGet()}"

    companion object {
        private val nextId = AtomicLong()
    }

    val count: Int get() = renderer.pageCount

    fun ratio(page: Int): Float = synchronized(renderer) {
        ratios[page] ?: run {
            val p = renderer.openPage(page)
            val r = try {
                p.width.toFloat() / p.height.coerceAtLeast(1).toFloat()
            } finally {
                p.close()
            }
            ratios[page] = r
            r
        }
    }

    /** 缓存命中版本只读内存，不打开 PdfRenderer 页面。 */
    fun cachedRatioWithRotation(page: Int, rotation: Int): Float? =
        ratios[page]?.let {
            if (PdfTileTransform.normalizeRotation(rotation) in setOf(90, 270)) 1f / it else it
        }

    /** 90 / 270 度时页面宽高互换，容器比例要取倒数。 */
    fun ratioWithRotation(page: Int, rotation: Int): Float {
        val r = ratio(page)
        return if (PdfTileTransform.normalizeRotation(rotation) in setOf(90, 270)) 1f / r else r
    }

    private fun raw(page: Int, w: Int, h: Int): Bitmap = synchronized(renderer) {
        // 按可用堆分档：屏幕 WIDTH 走分块（不经过这里），
        // 导出 / 缩略图才走整页，尽量接近显示宽度但避免 OOM
        val mem = Runtime.getRuntime().maxMemory()
        val maxSide = when {
            mem >= 256L * 1024 * 1024 -> 3200f
            mem >= 192L * 1024 * 1024 -> 2600f
            mem >= 128L * 1024 * 1024 -> 2200f
            else -> 1800f
        }
        val s = min(1f, maxSide / maxOf(w, h).coerceAtLeast(1).toFloat())
        val rw = (w * s).toInt().coerceAtLeast(1)
        val rh = (h * s).toInt().coerceAtLeast(1)
        val key = "raw:$cacheId:$page-${rw}x$rh"
        val hit = PdfBitmapCache.get(key)
        if (hit != null) {
            hit
        } else {
            val bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            try {
                val p = renderer.openPage(page)
                try {
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } finally {
                    p.close()
                }
                PdfBitmapCache.put(key, bmp)
                bmp
            } catch (failure: Throwable) {
                if (!bmp.isRecycled) bmp.recycle()
                throw failure
            }
        }
    }

    /**
     * 只渲染逻辑画布上 (left,top) 起 tw×th 的一块，横向严格 1:1。
     * A = 逻辑画布宽 / PDF 页宽（已含放大倍率），矩阵 = 缩放 A + 平移 -(块左上/A)。
     * PdfRenderer 按 bitmap 边界裁剪，所以只分配 tw×th 的位图，
     * 4× 放大时可见窗口仍是屏幕大小，块数不随倍率增长。
     */
    fun renderTile(
        page: Int, lw: Int, lh: Int, left: Int, top: Int,
        tw: Int, th: Int, tint: Int, brightness: Float,
        rotation: Int = 0
    ): Bitmap {
        if (lw <= 0 || tw <= 0 || th <= 0 || left >= lw || top >= lh) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        try {
            synchronized(renderer) {
                val p = renderer.openPage(page)
                try {
                    val values = PdfTileTransform.matrixValues(
                        p.width.toFloat(), p.height.toFloat(), lw, left, top, rotation
                    )
                    val m = Matrix().apply { setValues(values) }
                    p.render(out, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } finally {
                    p.close()
                }
            }
            val filtered = PageFilter.apply(out, tint, brightness)
            if (filtered !== out) out.recycle()
            return filtered
        } catch (failure: Throwable) {
            if (!out.isRecycled) out.recycle()
            throw failure
        }
    }

    fun render(
        page: Int, w: Int, h: Int, tint: Int, brightness: Float,
        rotation: Int = 0
    ): Bitmap {
        val normalizedRotation = PdfTileTransform.normalizeRotation(rotation)
        val swap = normalizedRotation == 90 || normalizedRotation == 270
        val rw = if (swap) h else w
        val rh = if (swap) w else h
        val src = raw(page, rw, rh)
        val filtered = PageFilter.apply(src, tint, brightness)
        if (normalizedRotation == 0) return filtered
        val m = Matrix().apply { postRotate(normalizedRotation.toFloat()) }
        val out = Bitmap.createBitmap(
            filtered, 0, 0, filtered.width, filtered.height, m, true
        )
        // filtered 可能就是 raw 缓存对象，绝不能回收它
        if (out !== filtered && out !== src) filtered.recycle()
        return out
    }

    /** 只填缓存，结果丢弃，用于相邻页预渲染。 */
    fun prefetch(page: Int, w: Int, h: Int) {
        if (page < 0 || page >= count) return
        raw(page, w, h)
    }

    fun thumb(page: Int, w: Int): Bitmap {
        val h = (w / ratio(page)).toInt().coerceAtLeast(1)
        return render(page, w, h, PageFilter.WHITE, 0f)
    }

    override fun close() {
        PdfTileScheduler.cancelRepository(cacheId)
        try {
            synchronized(renderer) { renderer.close() }
        } finally {
            pfd.close()
            PdfBitmapCache.clearPrefix("raw:$cacheId:")
            PdfBitmapCache.clearPrefix("tile:$cacheId:")
            PdfBitmapCache.clearPrefix("page:$cacheId:")
        }
    }
}
