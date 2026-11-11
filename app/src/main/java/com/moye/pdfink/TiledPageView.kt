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
        const val MAX_PENDING_TILES = 8
        const val PREVIEW_BUCKET = 0.5f
        private val owners = AtomicLong()

        /** 预览图坐标映射到当前栅格坐标；实际显示倍率仍由 viewport.scale 决定。 */
        fun previewToRenderFactor(renderBucket: Float): Float =
            renderBucket.coerceAtLeast(PREVIEW_BUCKET) / PREVIEW_BUCKET

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
    /** 固定 0.5× 整页预览网格，作为所有高清档位的连续显示底图。 */
    private var previewGrid: List<TileSpec> = emptyList()
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
        rebuildPreviewGrid()
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
        )        invalidate()
    }

    fun unbindTiled() {
        tiled = false
        generation++
        PdfTileScheduler.cancelOwner(ownerId)
        pending.clear()
        retryAfter.clear()
        retryCount.clear()
        previewGrid = emptyList()
        repo = null
    }

    private fun cacheKey(
        col: Int, row: Int, bucket: Float, logicalW: Int, logicalH: Int
    ): String {
        val id = repo?.cacheId ?: "closed"
        val namespace = if (kotlin.math.abs(bucket - PREVIEW_BUCKET) < 0.0001f) {
            "tile-preview"
        } else {
            "tile"
        }
        return "$namespace:$id:$epoch:$page:${logicalW}x$logicalH:$rotation:$col:$row:$bucket:$tint:$brightness"
    }

    private fun rebuildPreviewGrid() {
        if (!tiled || repo == null || baseW <= 0 || baseH <= 0) {
            previewGrid = emptyList()
            return
        }
        val previewW = (baseW * PREVIEW_BUCKET).toInt().coerceAtLeast(1)
        val previewH = (baseH * PREVIEW_BUCKET).toInt().coerceAtLeast(1)
        val columns = (previewW + TILE_H - 1) / TILE_H
        val rows = (previewH + TILE_H - 1) / TILE_H
        val next = ArrayList<TileSpec>(columns * rows)
        for (row in 0 until rows) {
            val y = row * TILE_H
            val tileH = minOf(TILE_H, previewH - y)
            for (col in 0 until columns) {
                val x = col * TILE_H
                val tileW = minOf(TILE_H, previewW - x)
                next.add(TileSpec(
                    col, row, x, y, tileW, tileH,
                    cacheKey(col, row, PREVIEW_BUCKET, previewW, previewH),
                    false, Float.MAX_VALUE
                ))
            }
        }
        previewGrid = next
    }

    /** 只返回当前视口和一圈预取范围内的预览图块，避免整页任务占满队列。 */
    private fun previewSpecsFor(vp: Viewport): List<TileSpec> {
        if (previewGrid.isEmpty() || vp.bucket <= 0f) return emptyList()
        val factor = PREVIEW_BUCKET / vp.bucket
        val left = vp.visLeft * factor
        val top = vp.visTop * factor
        val width = (vp.visW * factor).toInt().coerceAtLeast(1)
        val height = (vp.visH * factor).toInt().coerceAtLeast(1)
        val previewW = (baseW * PREVIEW_BUCKET).toInt().coerceAtLeast(1)
        val previewH = (baseH * PREVIEW_BUCKET).toInt().coerceAtLeast(1)
        val cols = tileRange(previewW, left, width)
        val rows = tileRange(previewH, top, height)
        if (cols.isEmpty() || rows.isEmpty()) return emptyList()
        val centerX = left + width * 0.5f
        val centerY = top + height * 0.5f
        return previewGrid.asSequence()
            .filter { it.col in cols && it.row in rows }
            .map { spec ->
                val visible = spec.x < left + width && spec.x + spec.width > left &&
                    spec.y < top + height && spec.y + spec.height > top
                val dx = spec.x + spec.width * 0.5f - centerX
                val dy = spec.y + spec.height * 0.5f - centerY
                spec.copy(visible = visible, distance = kotlin.math.abs(dx) + kotlin.math.abs(dy))
            }
            .sortedWith(compareBy<TileSpec> { !it.visible }.thenBy { it.distance })
            .toList()
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
            rebuildPreviewGrid()
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
        val previewFactor = previewToRenderFactor(bucket)
        paint.colorFilter = colorFilter

        val cols = tileRange(logicalW, left, visW)
        val rows = tileRange(logicalH, top, visH)
        if (cols.isEmpty() || rows.isEmpty()) return

        val centerX = left + visW * 0.5f
        val centerY = top + visH * 0.5f
        val specs = ArrayList<TileSpec>(
            (cols.last - cols.first + 1) * (rows.last - rows.first + 1)
        )
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
                specs.add(TileSpec(
                    col, row, x, y, tileW, tileH, key,
                    isVisible, abs(dx) + abs(dy)
                ))
            }
        }

        val previewSpecs = previewSpecsFor(vp)
        val previewW = (baseW * PREVIEW_BUCKET).toInt().coerceAtLeast(1)
        val previewH = (baseH * PREVIEW_BUCKET).toInt().coerceAtLeast(1)
        val keep = HashSet<String>(previewSpecs.size + specs.size)
        previewSpecs.forEach { keep.add(it.cacheKey) }
        specs.forEach { keep.add(it.cacheKey) }

        // 只撤销离开当前视口和预取范围的任务；低清预览在所有倍率间复用。
        PdfTileScheduler.retain(ownerId, generation, keep)
        pending.retainAll(keep)
        retryAfter.keys.retainAll(keep)
        retryCount.keys.retainAll(keep)

        canvas.save()
        canvas.translate(-originX, -originY)
        canvas.scale(displayFactor, displayFactor)

        // 先绘制页面底色，再用固定低清图块托底。高清块未完成时不画白色占位块。
        canvas.drawRect(left, top, left + visW, top + visH, background)
        val missingPreview = ArrayList<TileSpec>()
        canvas.save()
        canvas.scale(previewFactor, previewFactor)
        for (spec in previewSpecs) {
            val bitmap = TileCache.get(spec.cacheKey)
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, spec.x.toFloat(), spec.y.toFloat(), paint)
            } else {
                val retry = retryAfter[spec.cacheKey] ?: 0L
                if (spec.cacheKey !in pending && SystemClock.uptimeMillis() >= retry) {
                    missingPreview.add(spec)
                }
            }
        }
        canvas.restore()

        val sameAsPreview = kotlin.math.abs(bucket - PREVIEW_BUCKET) < 0.0001f
        val missingCurrent = ArrayList<TileSpec>()
        if (!sameAsPreview) {
            for (spec in specs) {
                val bitmap = TileCache.get(spec.cacheKey)
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, spec.x.toFloat(), spec.y.toFloat(), paint)
                } else {
                    val retry = retryAfter[spec.cacheKey] ?: 0L
                    if (spec.cacheKey !in pending && SystemClock.uptimeMillis() >= retry) {
                        missingCurrent.add(spec)
                    }
                }
            }
        }
        canvas.restore()

        // 先排当前视口的低清预览，再排高清可见块；预取任务始终靠后。
        var slots = (MAX_PENDING_TILES - pending.size).coerceAtLeast(0)
        val visiblePreview = missingPreview.filter { it.visible }
        visiblePreview.take(slots).forEach { spec ->
            request(spec, PREVIEW_BUCKET, previewW, previewH)
            slots--
        }

        val visibleCurrent = missingCurrent
            .filter { it.visible }
            .sortedBy { it.distance }
        visibleCurrent.take(slots).forEach { spec ->
            request(spec, bucket, logicalW, logicalH)
            slots--
        }

        if (slots > 0) {
            missingPreview.filter { !it.visible }.take(slots).forEach { spec ->
                request(spec, PREVIEW_BUCKET, previewW, previewH)
                slots--
            }
        }

        if (slots > 0) {
            missingCurrent.filter { !it.visible }
                .sortedBy { it.distance }
                .take(slots)
                .forEach { spec -> request(spec, bucket, logicalW, logicalH) }
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