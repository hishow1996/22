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

/**
 * 条带缓存：按字节限额，只保留当前可见块与相邻预取块。
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
 * 继承 ImageView：FIT / DOUBLE 下仍用 setImageBitmap（原逻辑一行不改），
 * WIDTH 下 bindTiled() 切成「按可见 clip 分块绘制」。
 *
 * 坐标说明：View 的局部坐标就是页面目标像素 (0..targetW, 0..targetH)；
 * 父 ZoomHost 的平移 / 缩放由系统 concat 进 canvas，clipBounds 已换算到局部
 * 坐标，所以不需要外部传 visibleRange，天然跟随双指上下平移。
 */
class TiledPageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    companion object {
        const val TILE_H = 400
        const val PREFETCH = 1
        // 防御：个别 ROM 的 clipBounds 可能给整页高度，限制一次最多渲染 6 块，
        // 避免退化成「整页大图」把低堆机器吃满
        const val MAX_TILES = 6
    }

    private var repo: PdfRepo? = null
    private var scope: CoroutineScope? = null
    private var page = -1
    private var targetW = 0
    private var targetH = 0
    private var tint = 0
    private var brightness = 0f
    private var ep = 0
    private var generation = 0
    private var tiled = false
    private val pending = HashSet<Int>()
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
        targetW = w; targetH = h; tint = t; brightness = b
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

    private fun key(i: Int) = "$ep-$page-${targetW}x$targetH-$i-$tint-$brightness"

    override fun onDraw(canvas: Canvas) {
        if (!tiled || targetW <= 0 || targetH <= 0) {
            super.onDraw(canvas)
            return
        }
        // 亮度滑杆的实时预览靠 ImageView.colorFilter，分块绘制时也要带上
        paint.colorFilter = colorFilter
        val clip = canvas.clipBounds
        val count = (targetH + TILE_H - 1) / TILE_H
        var first = (clip.top / TILE_H - PREFETCH).coerceAtLeast(0)
        var last = ((clip.bottom + TILE_H - 1) / TILE_H + PREFETCH)
            .coerceAtMost(count - 1)
        if (last < first) return
        if (last - first + 1 > MAX_TILES) {
            // 以可见区中心为锚保留最需要的几块
            val center = (clip.top + clip.bottom) / 2 / TILE_H
            first = (center - MAX_TILES / 2).coerceAtLeast(0)
            last = (first + MAX_TILES - 1).coerceAtMost(count - 1)
        }
        for (i in first..last) {
            val top = i * TILE_H
            val hh = minOf(TILE_H, targetH - top)
            val bmp = TileCache.get(key(i))
            if (bmp != null) {
                canvas.drawBitmap(bmp, 0f, top.toFloat(), paint)
            } else {
                canvas.drawRect(0f, top.toFloat(), targetW.toFloat(),
                    (top + hh).toFloat(), white)
                request(i)
            }
        }
    }

    private fun request(i: Int) {
        val r = repo ?: return
        val sc = scope ?: return
        if (!pending.add(i)) return
        val gen = generation
        val p = page; val w = targetW; val h = targetH
        val t = tint; val b = brightness; val k = key(i)
        sc.launch(Dispatchers.IO) {
            val bmp = try {
                r.renderTile(p, w, h, i, TILE_H, t, b)
            } catch (e: Exception) { null }
            if (bmp != null) TileCache.put(k, bmp)
            withContext(Dispatchers.Main) {
                pending.remove(i)
                if (gen == generation && tiled) invalidate()
            }
        }
    }
}
