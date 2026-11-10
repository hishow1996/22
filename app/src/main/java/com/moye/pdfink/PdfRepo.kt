package com.moye.pdfink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.Closeable
import kotlin.math.min

/** 系统 PdfRenderer 封装：串行访问 + raw 页缓存 + 滤镜与旋转输出。 */
class PdfRepo(private val ctx: Context, uri: Uri) : Closeable {

    private val pfd: ParcelFileDescriptor =
        ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("cannot open uri")

    private val renderer = PdfRenderer(pfd)
    private val ratios = HashMap<Int, Float>()

    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 6L).toInt().coerceAtLeast(8 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    val count: Int get() = renderer.pageCount

    fun ratio(page: Int): Float = synchronized(renderer) {
        ratios[page] ?: run {
            val p = renderer.openPage(page)
            val r = p.width.toFloat() / p.height.coerceAtLeast(1).toFloat()
            p.close()
            ratios[page] = r
            r
        }
    }

    /** 90 / 270 度时页面宽高互换，容器比例要取倒数。 */
    fun ratioWithRotation(page: Int, rotation: Int): Float {
        val r = ratio(page)
        return if (rotation == 90 || rotation == 270) 1f / r else r
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
        val key = "$page-${rw}x$rh"
        val hit = cache.get(key)
        if (hit != null) {
            hit
        } else {
            val bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val p = renderer.openPage(page)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            p.close()
            cache.put(key, bmp)
            bmp
        }
    }

    /**
     * 只渲染第 index 条纵向条带（目标像素坐标），横向严格 1:1。
     * matrix = 缩放 s=目标宽/原页宽 + 平移 -条带顶部，
     * PdfRenderer 会按 bitmap 边界裁剪，所以只分配 w×tileH 的位图。
     */
    fun renderTile(
        page: Int, w: Int, h: Int, index: Int, tileH: Int,
        tint: Int, brightness: Float
    ): Bitmap {
        val top = index * tileH
        if (top >= h || w <= 0) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        val th = minOf(tileH, h - top)
        val out = Bitmap.createBitmap(w, th, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        synchronized(renderer) {
            val p = renderer.openPage(page)
            val s = w.toFloat() / p.width.coerceAtLeast(1).toFloat()
            // preTranslate：M = T * S，点变换 = S·p + (0,-top)。
            // 若把平移写在缩放之后右乘，平移量会被 s 再乘一次，条带整体偏移。
            val m = Matrix().apply {
                setScale(s, s)
                preTranslate(0f, -top.toFloat())
            }
            p.render(out, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            p.close()
        }
        val filtered = PageFilter.apply(out, tint, brightness)
        // apply 可能就是 out 本身，绝不能回收它
        if (filtered !== out) out.recycle()
        return filtered
    }

    fun render(
        page: Int, w: Int, h: Int, tint: Int, brightness: Float,
        rotation: Int = 0
    ): Bitmap {
        val swap = rotation == 90 || rotation == 270
        val rw = if (swap) h else w
        val rh = if (swap) w else h
        val src = raw(page, rw, rh)
        val filtered = PageFilter.apply(src, tint, brightness)
        if (rotation == 0) return filtered
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
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
        renderer.close()
        pfd.close()
        cache.evictAll()
    }
}
