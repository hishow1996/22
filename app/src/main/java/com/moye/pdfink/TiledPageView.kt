package com.moye.pdfink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.ImageView
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.floor

/** PDF 分块缓存只是统一位图缓存的命名空间，并提供失效代数。 */
object TileCache {
    @Volatile var epoch = 0
        private set

    fun get(key: String): Bitmap? = PdfBitmapCache.get(key)
    fun put(key: String, bitmap: Bitmap) = PdfBitmapCache.put(key, bitmap)

    fun clear() {
        epoch++
        PdfTileScheduler.cancelAll()
        PdfBitmapCache.clear()
    }

    fun trim(level: Int) = PdfBitmapCache.trim(level)
    fun restore() = PdfBitmapCache.restore()
}

/**
 * 按真实缩放倍率绘制页面，以 bucket 决定 PDF 栅格清晰度。
 * 视口变化时只保留仍然需要的任务；各页面通过 PdfTileScheduler 全局排序。
 */
class TiledPageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    companion object {
        const val TILE_H = 400
        const val PREFETCH = 1
        const val MAX_PENDING_TILES = 4
        private val owners = AtomicLong()

        /** 栅格分辨率档位不再决定页面实际显示倍率。 */
        fun bucket(s: Float): Float = when {
            s < 0.60f -> 0.5f
            s < 0.74f -> 0.67f
            s < 0.89f -> 0.8f
            s < 1.25f -> 1f
            s < 1.75f -> 1.5f
            s < 2.5f -> 2f
            s < 3.5f -> 3f
            else -> 4f
        }

        /** 覆盖完整可见范围与一圈预取范围，横屏不按固定块数截断。 */
        fun tileRange(pageSize: Int, start: Float, size: Int): IntRange {
            if (pageSize <= 0 || size <= 0) return IntRange.EMPTY
            val count = (pageSize + TILE_H - 1) / TILE_H
            val first = (floor(start / TILE_H).toInt() - PREFETCH).coerceAtLeast(0)
            val last = (floor((start + size) / TILE_H).toInt() + PREFETCH)
                .coerceAtMost(count - 1)
            return if (last < first) IntRange.EMPTY else first..last
        }
    }

    private data class TileSpec(
        val col: Int, val row: Int, val x: Int, val y: Int,
        val width: Int, val height: Int, val cacheKey: String,
        val visible: Boolean, val distance: Float
    )

    var viewport: Viewport? = null

    private val ownerId = owners.incrementAndGet()
    private var repo: PdfRepo? = null
    private var page = -1
    private var baseW = 0
    private var baseH = 0
    private var tint = 0
    private var brightness = 0f
    private var rotation = 0
    private var epoch = 0
    private var generation = 0
    private var tiled = false
    private var currentPage = true
    private val pending = HashSet<String>()
    private val retryAfter = HashMap<String, Long>()
    private val retryCount = HashMap<String, Int>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val background = Paint().apply { color = Color.WHITE }

    fun bindTiled(
        r: PdfRepo, p: Int, w: Int, h: Int, t: Int, b: Float,
        pageRotation: Int = 0, isCurrentPage: Boolean = true
    ) {
        if (w <= 0 || h <= 0) return
        PdfTileScheduler.cancelOwner(ownerId)
        generation++
        pending.clear()
        retryAfter.clear()
        retryCount.clear()
        repo = r
        page = p
        baseW = w
        baseH = h
        tint = t
        brightness = b
        rotation = PdfTileTransform.normalizeRotation(pageRotation)
        currentPage = isCurrentPage
        epoch = TileCache.epoch
        tiled = true
        background.color = when (t) {
            PageFilter.NIGHT -> 0xFF1B2430.toInt()
            PageFilter.WARM -> 0xFFF2E1C4.toInt()
            else -> Color.WHITE
        }
        setImageBitmap(null)
        invalidate()
    }

    fun setCurrentPage(current: Boolean) {
        if (currentPage == current) return
        currentPage = current
        PdfTileScheduler.reprioritize(
            ownerId, generation, if (current) 0 else 2
        )
        invalidate()
    }

    fun unbindTiled() {
        tiled = false
        generation++
        PdfTileScheduler.cancelOwner(ownerId)
        pending.clear()
        retryAfter.clear()
        retryCount.clear()
        repo = null
    }

    private fun cacheKey(
        col: Int, row: Int, bucket: Float, logicalW: Int, logicalH: Int
    ): String {
        val id = repo?.cacheId ?: "closed"
        return "tile:$id:$epoch:$page:${logicalW}x$logicalH:$rotation:$col:$row:$bucket:$tint:$brightness"
    }

    override fun onDraw(canvas: Canvas) {
        val cacheEpoch = TileCache.epoch
        if (cacheEpoch != epoch) {
            PdfTileScheduler.cancelOwner(ownerId)
            generation++
            epoch = cacheEpoch
            pending.clear()
            retryAfter.clear()
            retryCount.clear()
        }

        val vp = viewport
        if (!tiled || baseW <= 0 || baseH <= 0 || vp == null ||
            vp.lw <= 0 || vp.lh <= 0 || vp.scale <= 0f
        ) {
            super.onDraw(canvas)
            return
        }

        val bucket = vp.bucket
        val logicalW = vp.lw
        val logicalH = vp.lh
        val left = vp.visLeft
        val top = vp.visTop
        val originX = vp.originX
        val originY = vp.originY
        val visW = vp.visW.coerceAtLeast(1)
        val visH = vp.visH.coerceAtLeast(1)
        val displayFactor = ZoomTransform.renderToDisplayFactor(vp.scale, bucket)
        paint.colorFilter = colorFilter

        val cols = tileRange(logicalW, left, visW)
        val rows = tileRange(logicalH, top, visH)
        if (cols.isEmpty() || rows.isEmpty()) return

        val centerX = left + visW * 0.5f
        val centerY = top + visH * 0.5f
        val specs = ArrayList<TileSpec>(
            (cols.last - cols.first + 1) * (rows.last - rows.first + 1)
        )
        val keep = HashSet<String>()
        for (row in rows) {
            val y = row * TILE_H
            val tileH = minOf(TILE_H, logicalH - y)
            if (tileH <= 0) continue
            for (col in cols) {
                val x = col * TILE_H
                val tileW = minOf(TILE_H, logicalW - x)
                if (tileW <= 0) continue
                val isVisible = x < left + visW && x + tileW > left &&
                    y < top + visH && y + tileH > top
                val dx = x + tileW * 0.5f - centerX
                val dy = y + tileH * 0.5f - centerY
                val key = cacheKey(col, row, bucket, logicalW, logicalH)
                keep.add(key)
                specs.add(TileSpec(
                    col, row, x, y, tileW, tileH, key,
                    isVisible, abs(dx) + abs(dy)
                ))
            }
        }

        // 当前视口之外的旧排队任务立即撤销，不影响其他页面对同一块的订阅。
        PdfTileScheduler.retain(ownerId, generation, keep)
        pending.retainAll(keep)
        retryAfter.keys.retainAll(keep)
        retryCount.keys.retainAll(keep)

        canvas.save()
        canvas.translate(-originX, -originY)
        canvas.scale(displayFactor, displayFactor)
        val missing = ArrayList<TileSpec>()
        for (spec in specs) {
            val bitmap = TileCache.get(spec.cacheKey)
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, spec.x.toFloat(), spec.y.toFloat(), paint)
            } else {
                canvas.drawRect(
                    spec.x.toFloat(), spec.y.toFloat(),
                    (spec.x + spec.width).toFloat(),
                    (spec.y + spec.height).toFloat(), background
                )
                val retry = retryAfter[spec.cacheKey] ?: 0L
                if (spec.cacheKey !in pending && SystemClock.uptimeMillis() >= retry) {
                    missing.add(spec)
                }
            }
        }
        canvas.restore()

        // 当前页可见块 priority=0；当前页预取=1；非当前页面分别为 2 / 3。
        val slots = (MAX_PENDING_TILES - pending.size).coerceAtLeast(0)
        if (slots > 0) {
            missing.sortedWith(
                compareBy<TileSpec> { !it.visible }.thenBy { it.distance }
            ).take(slots).forEach { spec -> request(spec, bucket, logicalW, logicalH) }
        }
    }

    private fun request(
        spec: TileSpec, bucket: Float, logicalW: Int, logicalH: Int
    ) {
        val r = repo ?: return
        val key = spec.cacheKey
        if (pending.size >= MAX_PENDING_TILES || !pending.add(key)) return
        val bindGeneration = generation
        val bindEpoch = epoch
        val pageToRender = page
        val pageRotation = rotation
        val tintToRender = tint
        val brightnessToRender = brightness
        val priority = PdfTilePriority.value(currentPage, spec.visible)

        PdfTileScheduler.submit(
            key = key,
            owner = ownerId,
            generation = bindGeneration,
            priority = priority,
            render = {
                r.renderTile(
                    pageToRender, logicalW, logicalH,
                    spec.x, spec.y, spec.width, spec.height,
                    tintToRender, brightnessToRender, pageRotation
                )
            }
        ) { success ->
            if (bindGeneration != generation || bindEpoch != epoch) return@submit
            pending.remove(key)
            if (success) {
                retryAfter.remove(key)
                retryCount.remove(key)
            } else {
                val attempts = (retryCount[key] ?: 0) + 1
                retryCount[key] = attempts
                val delay = when (attempts) {
                    1 -> 1_000L
                    2 -> 2_500L
                    else -> 5_000L
                }
                retryAfter[key] = SystemClock.uptimeMillis() + delay
                postDelayed({
                    if (bindGeneration == generation && tiled && isAttachedToWindow) {
                        invalidate()
                    }
                }, delay)
            }
            invalidate()
        }
    }
}
