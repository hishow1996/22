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
        var visible: Boolean, var distance: Float
    )

    private data class PlanSignature(
        val page: Int,
        val bucket: Float,
        val logicalW: Int,
        val logicalH: Int,
        val cols: IntRange,
        val rows: IntRange,
        val previewCols: IntRange,
        val previewRows: IntRange,
        val transitionBaseW: Int,
        val transitionBaseH: Int,
        val transitionCols: IntRange,
        val transitionRows: IntRange
    )

    private data class RenderPlan(
        val signature: PlanSignature,
        val specs: MutableList<TileSpec>,
        val previewSpecs: MutableList<TileSpec>,
        val transitionPreviewSpecs: MutableList<TileSpec>,
        val keepKeys: Set<String>,
        val previewW: Int,
        val previewH: Int,
        val transitionScaleX: Float,
        val transitionScaleY: Float,
        val transitionViewportFactorX: Float,
        val transitionViewportFactorY: Float
    ) {
        var lastSortedLeft = Float.NaN
        var lastSortedTop = Float.NaN
    }

    var viewport: Viewport? = null

    /** 手势期间只维护预览与现有清晰块，松手后再补齐当前高清视口。 */
    var deferHighResolution: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

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
    /** 页面尺寸变化时保留上一尺寸的低清图块，作为新尺寸的临时底图。 */
    private var transitionPreviewGrid: List<TileSpec> = emptyList()
    private var transitionBaseW = 0
    private var transitionBaseH = 0
    private var renderPlan: RenderPlan? = null
    private val missingPreview = ArrayList<TileSpec>()
    private val missingCurrent = ArrayList<TileSpec>()
    private val tileOrder = Comparator<TileSpec> { a, b ->
        when {
            a.visible && !b.visible -> -1
            !a.visible && b.visible -> 1
            else -> a.distance.compareTo(b.distance)
        }
    }
    private val retryAfter = HashMap<String, Long>()
    private val retryCount = HashMap<String, Int>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val background = Paint().apply { color = Color.WHITE }

    fun bindTiled(
        r: PdfRepo, p: Int, w: Int, h: Int, t: Int, b: Float,
        pageRotation: Int = 0, isCurrentPage: Boolean = true
    ) {
        if (w <= 0 || h <= 0) return
        val normalizedRotation = PdfTileTransform.normalizeRotation(pageRotation)
        val sameContent = tiled && repo === r && page == p && tint == t &&
            kotlin.math.abs(brightness - b) < 0.0001f &&
            rotation == normalizedRotation && epoch == TileCache.epoch
        val sameSize = baseW == w && baseH == h

        if (sameContent && sameSize) {
            setCurrentPage(isCurrentPage)
            invalidate()
            return
        }

        if (sameContent && previewGrid.isNotEmpty() && !sameSize) {
            // Keep the oldest usable preview until the current-size grid has filled in;
            // rapid hide/show toggles must not replace a populated fallback with an empty grid.
            if (transitionPreviewGrid.isEmpty()) {
                transitionPreviewGrid = previewGrid
                transitionBaseW = baseW
                transitionBaseH = baseH
            }
        } else if (!sameContent) {
            transitionPreviewGrid = emptyList()
            transitionBaseW = 0
            transitionBaseH = 0
        }

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
        rotation = normalizedRotation
        currentPage = isCurrentPage
        epoch = TileCache.epoch
        tiled = true
        renderPlan = null
        missingPreview.clear()
        missingCurrent.clear()
        rebuildPreviewGrid()
        background.color = when (t) {
            PageFilter.NIGHT -> 0xFF1B2430.toInt()
            PageFilter.WARM -> 0xFFF2E1C4.toInt()
            else -> Color.WHITE
        }
        if (!sameContent) setImageBitmap(null)
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
        previewGrid = emptyList()
        transitionPreviewGrid = emptyList()
        transitionBaseW = 0
        transitionBaseH = 0
        renderPlan = null
        missingPreview.clear()
        missingCurrent.clear()
        deferHighResolution = false
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

    private fun isVisible(
        spec: TileSpec, left: Float, top: Float, width: Int, height: Int
    ): Boolean = spec.x < left + width && spec.x + spec.width > left &&
        spec.y < top + height && spec.y + spec.height > top

    private fun updateDistance(
        spec: TileSpec, left: Float, top: Float, width: Int, height: Int
    ) {
        val dx = spec.x + spec.width * 0.5f - (left + width * 0.5f)
        val dy = spec.y + spec.height * 0.5f - (top + height * 0.5f)
        spec.distance = abs(dx) + abs(dy)
    }

    private fun buildRenderPlan(
        signature: PlanSignature,
        cols: IntRange,
        rows: IntRange,
        previewCols: IntRange,
        previewRows: IntRange,
        transitionCols: IntRange,
        transitionRows: IntRange,
        transitionFactorX: Float,
        transitionFactorY: Float,
        logicalW: Int,
        logicalH: Int,
        previewW: Int,
        previewH: Int,
        transitionW: Int,
        transitionH: Int
    ): RenderPlan {
        val specs = ArrayList<TileSpec>()
        if (!cols.isEmpty() && !rows.isEmpty()) {
            for (row in rows) {
                val y = row * TILE_H
                val tileH = minOf(TILE_H, logicalH - y)
                if (tileH <= 0) continue
                for (col in cols) {
                    val x = col * TILE_H
                    val tileW = minOf(TILE_H, logicalW - x)
                    if (tileW <= 0) continue
                    val key = cacheKey(col, row, signature.bucket, logicalW, logicalH)
                    specs.add(TileSpec(
                        col, row, x, y, tileW, tileH, key, false, Float.MAX_VALUE
                    ))
                }
            }
        }

        val previewSpecs = ArrayList<TileSpec>()
        if (!previewCols.isEmpty() && !previewRows.isEmpty()) {
            val columns = (previewW + TILE_H - 1) / TILE_H
            for (row in previewRows) {
                for (col in previewCols) {
                    val index = row * columns + col
                    if (index in previewGrid.indices) previewSpecs.add(previewGrid[index])
                }
            }
        }

        val transitionSpecs = ArrayList<TileSpec>()
        if (!transitionCols.isEmpty() && !transitionRows.isEmpty()) {
            val columns = (transitionW + TILE_H - 1) / TILE_H
            for (row in transitionRows) {
                for (col in transitionCols) {
                    val index = row * columns + col
                    if (index in transitionPreviewGrid.indices) {
                        transitionSpecs.add(transitionPreviewGrid[index])
                    }
                }
            }
        }

        val keep = HashSet<String>(previewSpecs.size + specs.size)
        previewSpecs.forEach { keep.add(it.cacheKey) }
        specs.forEach { keep.add(it.cacheKey) }
        return RenderPlan(
            signature, specs, previewSpecs, transitionSpecs, keep, previewW, previewH,
            if (transitionFactorX > 0f) 1f / transitionFactorX else 1f,
            if (transitionFactorY > 0f) 1f / transitionFactorY else 1f,
            transitionFactorX, transitionFactorY
        )
    }

    private fun updatePlanPriorities(plan: RenderPlan, vp: Viewport) {
        val left = vp.visLeft
        val top = vp.visTop
        val width = vp.visW.coerceAtLeast(1)
        val height = vp.visH.coerceAtLeast(1)
        var visibilityChanged = false

        fun updateVisibility(
            specs: List<TileSpec>, x: Float, y: Float, w: Int, h: Int
        ) {
            for (spec in specs) {
                val visible = isVisible(spec, x, y, w, h)
                if (visible != spec.visible) visibilityChanged = true
                spec.visible = visible
            }
        }

        val factor = PREVIEW_BUCKET / vp.bucket.coerceAtLeast(PREVIEW_BUCKET)
        val previewLeft = left * factor
        val previewTop = top * factor
        val previewWidth = (width * factor).toInt().coerceAtLeast(1)
        val previewHeight = (height * factor).toInt().coerceAtLeast(1)
        val transitionLeft = left * plan.transitionViewportFactorX
        val transitionTop = top * plan.transitionViewportFactorY
        val transitionWidth = (width * plan.transitionViewportFactorX)
            .toInt().coerceAtLeast(1)
        val transitionHeight = (height * plan.transitionViewportFactorY)
            .toInt().coerceAtLeast(1)

        updateVisibility(plan.specs, left, top, width, height)
        updateVisibility(plan.previewSpecs, previewLeft, previewTop, previewWidth, previewHeight)
        if (plan.transitionPreviewSpecs.isNotEmpty()) {
            updateVisibility(
                plan.transitionPreviewSpecs,
                transitionLeft, transitionTop, transitionWidth, transitionHeight
            )
        }

        // 同一图块集合内，小幅移动不必每帧重算距离和排序；只有可见集合变化
        // 或视口移动达到一段距离时才刷新优先级，减少横屏滑动时的 CPU 开销。
        val movedEnough = plan.lastSortedLeft.isNaN() ||
            abs(left - plan.lastSortedLeft) >= TILE_H * 0.2f ||
            abs(top - plan.lastSortedTop) >= TILE_H * 0.2f
        if (!visibilityChanged && !movedEnough) return

        fun updateAndSort(
            specs: MutableList<TileSpec>, x: Float, y: Float, w: Int, h: Int
        ) {
            specs.forEach { updateDistance(it, x, y, w, h) }
            specs.sortWith(tileOrder)
        }

        updateAndSort(plan.specs, left, top, width, height)
        updateAndSort(plan.previewSpecs, previewLeft, previewTop, previewWidth, previewHeight)
        if (plan.transitionPreviewSpecs.isNotEmpty()) {
            updateAndSort(
                plan.transitionPreviewSpecs,
                transitionLeft, transitionTop, transitionWidth, transitionHeight
            )
        }
        plan.lastSortedLeft = left
        plan.lastSortedTop = top
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
            transitionPreviewGrid = emptyList()
            transitionBaseW = 0
            transitionBaseH = 0
            renderPlan = null
            missingPreview.clear()
            missingCurrent.clear()
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

        val previewW = (baseW * PREVIEW_BUCKET).toInt().coerceAtLeast(1)
        val previewH = (baseH * PREVIEW_BUCKET).toInt().coerceAtLeast(1)
        val previewViewportFactor = PREVIEW_BUCKET / bucket.coerceAtLeast(PREVIEW_BUCKET)
        val previewLeft = left * previewViewportFactor
        val previewTop = top * previewViewportFactor
        val previewVisW = (visW * previewViewportFactor).toInt().coerceAtLeast(1)
        val previewVisH = (visH * previewViewportFactor).toInt().coerceAtLeast(1)
        val previewCols = tileRange(previewW, previewLeft, previewVisW)
        val previewRows = tileRange(previewH, previewTop, previewVisH)

        val hasTransition = transitionPreviewGrid.isNotEmpty() &&
            transitionBaseW > 0 && transitionBaseH > 0
        val transitionFactorX = if (hasTransition) {
            PREVIEW_BUCKET * transitionBaseW.toFloat() /
                (baseW.toFloat() * bucket.coerceAtLeast(PREVIEW_BUCKET))
        } else 0f
        val transitionFactorY = if (hasTransition) {
            PREVIEW_BUCKET * transitionBaseH.toFloat() /
                (baseH.toFloat() * bucket.coerceAtLeast(PREVIEW_BUCKET))
        } else 0f
        val transitionW = if (hasTransition) {
            (transitionBaseW * PREVIEW_BUCKET).toInt().coerceAtLeast(1)
        } else 0
        val transitionH = if (hasTransition) {
            (transitionBaseH * PREVIEW_BUCKET).toInt().coerceAtLeast(1)
        } else 0
        val transitionLeft = left * transitionFactorX
        val transitionTop = top * transitionFactorY
        val transitionVisW = (visW * transitionFactorX).toInt().coerceAtLeast(1)
        val transitionVisH = (visH * transitionFactorY).toInt().coerceAtLeast(1)
        val transitionCols = if (hasTransition) {
            tileRange(transitionW, transitionLeft, transitionVisW)
        } else IntRange.EMPTY
        val transitionRows = if (hasTransition) {
            tileRange(transitionH, transitionTop, transitionVisH)
        } else IntRange.EMPTY

        val signature = PlanSignature(
            page, bucket, logicalW, logicalH, cols, rows, previewCols, previewRows,
            if (hasTransition) transitionBaseW else 0,
            if (hasTransition) transitionBaseH else 0,
            transitionCols, transitionRows
        )
        var plan = renderPlan
        if (plan == null || plan.signature != signature) {
            plan = buildRenderPlan(
                signature, cols, rows, previewCols, previewRows,
                transitionCols, transitionRows, transitionFactorX, transitionFactorY,
                logicalW, logicalH, previewW, previewH, transitionW, transitionH
            )
            renderPlan = plan

            // 仅当可见图块集合变化时遍历全局队列并裁剪订阅。
            PdfTileScheduler.retain(ownerId, generation, plan.keepKeys)
            pending.retainAll(plan.keepKeys)
            retryAfter.keys.retainAll(plan.keepKeys)
            retryCount.keys.retainAll(plan.keepKeys)
        }

        // 同一组图块范围内只更新优先级；不再逐帧重建 TileSpec / HashSet。
        updatePlanPriorities(plan, vp)
        missingPreview.clear()
        missingCurrent.clear()

        canvas.save()
        canvas.translate(-originX, -originY)
        canvas.scale(displayFactor, displayFactor)

        canvas.drawRect(left, top, left + visW, top + visH, background)

        if (plan.transitionPreviewSpecs.isNotEmpty()) {
            canvas.save()
            canvas.scale(plan.transitionScaleX, plan.transitionScaleY)
            for (spec in plan.transitionPreviewSpecs) {
                val bitmap = TileCache.get(spec.cacheKey)
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, spec.x.toFloat(), spec.y.toFloat(), paint)
                }
            }
            canvas.restore()
        }

        canvas.save()
        canvas.scale(previewFactor, previewFactor)
        for (spec in plan.previewSpecs) {
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
        if (!sameAsPreview) {
            for (spec in plan.specs) {
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

        var slots = (MAX_PENDING_TILES - pending.size).coerceAtLeast(0)
        for (spec in missingPreview) {
            if (slots <= 0) break
            if (spec.visible) {
                request(spec, PREVIEW_BUCKET, previewW, previewH)
                slots--
            }
        }

        if (!deferHighResolution) {
            for (spec in missingCurrent) {
                if (slots <= 0) break
                if (spec.visible) {
                    request(spec, bucket, logicalW, logicalH)
                    slots--
                }
            }
        }

        for (spec in missingPreview) {
            if (slots <= 0) break
            if (!spec.visible) {
                request(spec, PREVIEW_BUCKET, previewW, previewH)
                slots--
            }
        }

        if (slots > 0 && !deferHighResolution) {
            for (spec in missingCurrent) {
                if (slots <= 0) break
                if (!spec.visible) {
                    request(spec, bucket, logicalW, logicalH)
                    slots--
                }
            }
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
