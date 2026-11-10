package com.moye.pdfink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 条带（现升级为二维网格块）缓存：按字节限额，只保留可见块与相邻预取块。
 * clear() 递增 epoch，切文件 / 尺寸变化后旧任务不会把旧文件的块写回新 key。
 */
object TileCache {
    @Volatile var epoch = 0
        private set

    private val lru = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt()
            .coerceIn(12 * 1024, 32 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun get(k: String): Bitmap? = lru.get(k)
    fun put(k: String, v: Bitmap) { lru.put(k, v) }
    fun clear() { epoch++; lru.evictAll() }
}

/**
 * 按 viewport 自绘「放大后页面的可见窗口」。
 *
 * 不再依赖父 View 的 scaleX/scaleY（那会把位图插值放大）。
 * 每个网格块按目标像素 1:1 渲染，PDF 源矩阵 A = logicalW / pageWidth，
 * 所以双指放大到 4× 时仍是原生 PDF 采样，文字锐利。
 *
 * 非分块页（页面旋转过）走 ImageView 的 setImageBitmap 原路径，不加 viewport。
 */
class TiledPageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    companion object {
        const val TILE_H = 400      // 正方形网格边长（同时作 TILE_W）
        const val PREFETCH = 1
        // 每轴最多块数：防御个别 ROM 给出异常可见范围，避免退化成整页大图
        const val MAX_TILES = 6

        /** 缩放分档：档内只换可见块不重渲染，档间才换分辨率，不闪。 */
        fun bucket(s: Float): Float = when {
            s < 1.25f -> 1f
            s < 1.75f -> 1.5f
            s < 2.5f -> 2f
            s < 3.5f -> 3f
            else -> 4f
        }
    }

    var viewport: Viewport? = null

    private var repo: PdfRepo? = null
    private var scope: CoroutineScope? = null
    private var page = -1
    private var baseW = 0
    private var baseH = 0
    private var tint = 0
    private var brightness = 0f
    private var ep = 0
    private var generation = 0
    private var tiled = false
    private val pending = HashSet<String>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val white = Paint().apply { color = Color.WHITE }

    fun bindTiled(
        r: PdfRepo, sc: CoroutineScope, p: Int, w: Int, h: Int,
        t: Int, b: Float
    ) {
        if (w <= 0 || h <= 0) return
        generation++
        pending.clear()
        repo = r; scope = sc; page = p
        baseW = w; baseH = h; tint = t; brightness = b
        ep = TileCache.epoch
        tiled = true
        // 条带加载前先铺与滤镜一致的底色，夜间模式不会闪一下白
        white.color = when (t) {
            PageFilter.NIGHT -> 0xFF1B2430.toInt()
            PageFilter.WARM -> 0xFFF2E1C4.toInt()
            else -> Color.WHITE
        }
        setImageBitmap(null)
        invalidate()
    }

    fun unbindTiled() {
        tiled = false
        generation++
        pending.clear()
    }

    private fun key(col: Int, row: Int, s: Float, lw: Int, lh: Int) =
        "$ep-$page-${lw}x$lh-$col-$row-$s-$tint-$brightness"

    override fun onDraw(canvas: Canvas) {
        val vp = viewport
        if (!tiled || baseW <= 0 || baseH <= 0 || vp == null ||
            vp.lw <= 0 || vp.lh <= 0) {
            super.onDraw(canvas)
            return
        }
        // 逻辑画布与可见原点全部由 ZoomHost 统一下发，两层像素级对齐
        val s = vp.bucket
        val lw = vp.lw
        val lh = vp.lh
        // 挑块用「可见逻辑窗」，定位用「绘制原点」，两者不能混
        val left = vp.visLeft
        val top = vp.visTop
        val originX = vp.originX
        val originY = vp.originY
        val visW = vp.visW.coerceAtLeast(1)
        val visH = vp.visH.coerceAtLeast(1)
        paint.colorFilter = colorFilter   // 亮度滑杆实时预览

        val c0 = max(0, (left / TILE_H).toInt() - PREFETCH)
        val c1 = (((left + visW) / TILE_H).toInt() + PREFETCH)
            .coerceAtMost((lw + TILE_H - 1) / TILE_H - 1)
        val r0 = max(0, (top / TILE_H).toInt() - PREFETCH)
        val r1 = (((top + visH) / TILE_H).toInt() + PREFETCH)
            .coerceAtMost((lh + TILE_H - 1) / TILE_H - 1)
        if (c1 < c0 || r1 < r0) return
        // 防御：一次最多 MAX_TILES×MAX_TILES 块，低堆机器不会退化成整页大图
        val cc1 = c1.coerceAtMost(c0 + MAX_TILES - 1)
        val rr1 = r1.coerceAtMost(r0 + MAX_TILES - 1)

        canvas.save()
        canvas.translate(-originX, -originY)
        for (row in r0..rr1) {
            val ty = row * TILE_H
            val th = minOf(TILE_H, lh - ty)
            if (th <= 0) continue
            for (col in c0..cc1) {
                val txl = col * TILE_H
                val tw = minOf(TILE_H, lw - txl)
                if (tw <= 0) continue
                val k = key(col, row, s, lw, lh)
                val bmp = TileCache.get(k)
                if (bmp != null) {
                    canvas.drawBitmap(bmp, txl.toFloat(), ty.toFloat(), paint)
                } else {
                    canvas.drawRect(txl.toFloat(), ty.toFloat(),
                        (txl + tw).toFloat(), (ty + th).toFloat(), white)
                    request(col, row, s, lw, lh, txl, ty, tw, th, k)
                }
            }
        }
        canvas.restore()
    }

    private fun request(
        col: Int, row: Int, s: Float, lw: Int, lh: Int,
        txl: Int, ty: Int, tw: Int, th: Int, k: String
    ) {
        val r = repo ?: return
        val sc = scope ?: return
        if (!pending.add(k)) return
        val gen = generation
        val p = page; val t = tint; val b = brightness
        sc.launch(Dispatchers.IO) {
            val bmp = try {
                r.renderTile(p, lw, lh, txl, ty, tw, th, t, b)
            } catch (e: Exception) { null }
            if (bmp != null) TileCache.put(k, bmp)
            withContext(Dispatchers.Main) {
                pending.remove(k)
                if (gen == generation && tiled) invalidate()
            }
        }
    }
}
