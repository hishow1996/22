package com.moye.pdfink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

/**
 * 条带缓存按字节限额。epoch 隔离切文件前后产生的旧块。
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
 * 按 viewport 自绘页面窗口。当前可见块优先，邻近块低优先级预取；
 * 每个 View 限制待处理块数，并在换页/回收时取消尚未开始的任务。
 */
class TiledPageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    companion object {
        const val TILE_H = 400
        const val PREFETCH = 1
        const val MAX_PENDING_TILES = 10

        // PdfRenderer 本身要求串行访问；单通道避免为每个块创建竞争线程。
        private val renderDispatcher = Dispatchers.IO.limitedParallelism(1)

        /** 新增低于 1× 的渲染档位。 */
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

        /** 返回可见区与预取区覆盖的全部块，不对横屏宽视口作固定数量截断。 */
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
        val x: Int, val y: Int, val width: Int, val height: Int,
        val cacheKey: String, val visible: Boolean, val distance: Float
    )

    var viewport: Viewport? = null

    private var repo: PdfRepo? = null
    private var page = -1
    private var baseW = 0
    private var baseH = 0
    private var tint = 0
    private var brightness = 0f
    @Volatile private var ep = 0
    @Volatile private var generation = 0
    @Volatile private var tiled = false
    private var parentScope: CoroutineScope? = null
    private var generationJob: Job? = null
    private var tileScope: CoroutineScope? = null
    // pending / failed 只在主线程访问。
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val white = Paint().apply { color = Color.WHITE }

    private fun createGenerationScope(sc: CoroutineScope) {
        generationJob?.cancel()
        generationJob = SupervisorJob(sc.coroutineContext[Job])
        tileScope = CoroutineScope(sc.coroutineContext + generationJob!!)
    }

    private fun refreshEpoch() {
        if (ep == TileCache.epoch) return
        ep = TileCache.epoch
        generation++
        pending.clear()
        failed.clear()
        parentScope?.let(::createGenerationScope)
    }

    fun bindTiled(
        r: PdfRepo, sc: CoroutineScope, p: Int, w: Int, h: Int,
        t: Int, b: Float
    ) {
        if (w <= 0 || h <= 0) return
        generation++
        parentScope = sc
        createGenerationScope(sc)
        pending.clear()
        failed.clear()
        repo = r
        page = p
        baseW = w
        baseH = h
        tint = t
        brightness = b
        ep = TileCache.epoch
        tiled = true
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
        generationJob?.cancel()
        generationJob = null
        tileScope = null
        parentScope = null
        pending.clear()
        failed.clear()
    }

    private fun key(col: Int, row: Int, s: Float, lw: Int, lh: Int) =
        "$ep-$page-${lw}x$lh-$col-$row-$s-$tint-$brightness"

    override fun onDraw(canvas: Canvas) {
        refreshEpoch()
        val vp = viewport
        if (!tiled || baseW <= 0 || baseH <= 0 || vp == null ||
            vp.lw <= 0 || vp.lh <= 0) {
            super.onDraw(canvas)
            return
        }

        val s = vp.bucket
        val lw = vp.lw
        val lh = vp.lh
        val left = vp.visLeft
        val top = vp.visTop
        val originX = vp.originX
        val originY = vp.originY
        val visW = vp.visW.coerceAtLeast(1)
        val visH = vp.visH.coerceAtLeast(1)
        paint.colorFilter = colorFilter

        val cols = tileRange(lw, left, visW)
        val rows = tileRange(lh, top, visH)
        if (cols.isEmpty() || rows.isEmpty()) return

        val centerX = left + visW * 0.5f
        val centerY = top + visH * 0.5f
        val specs = ArrayList<TileSpec>(
            (cols.last - cols.first + 1) * (rows.last - rows.first + 1)
        )
        for (row in rows) {
            val y = row * TILE_H
            val tileH = minOf(TILE_H, lh - y)
            if (tileH <= 0) continue
            for (col in cols) {
                val x = col * TILE_H
                val tileW = minOf(TILE_H, lw - x)
                if (tileW <= 0) continue
                val isVisible = x < left + visW && x + tileW > left &&
                    y < top + visH && y + tileH > top
                val dx = x + tileW * 0.5f - centerX
                val dy = y + tileH * 0.5f - centerY
                specs.add(TileSpec(
                    x, y, tileW, tileH, key(col, row, s, lw, lh),
                    isVisible, abs(dx) + abs(dy)
                ))
            }
        }

        canvas.save()
        canvas.translate(-originX, -originY)
        val missing = ArrayList<TileSpec>()
        for (spec in specs) {
            val bitmap = TileCache.get(spec.cacheKey)
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, spec.x.toFloat(), spec.y.toFloat(), paint)
            } else {
                canvas.drawRect(
                    spec.x.toFloat(), spec.y.toFloat(),
                    (spec.x + spec.width).toFloat(),
                    (spec.y + spec.height).toFloat(), white
                )
                if (spec.cacheKey !in pending && spec.cacheKey !in failed) {
                    missing.add(spec)
                }
            }
        }
        canvas.restore()

        // 可见块先提交，邻近预取块后提交；完成一批后再提交下一批。
        val slots = (MAX_PENDING_TILES - pending.size).coerceAtLeast(0)
        if (slots > 0) {
            missing.sortedWith(
                compareBy<TileSpec> { !it.visible }.thenBy { it.distance }
            ).take(slots).forEach(::request)
        }
    }

    private fun request(spec: TileSpec) {
        val r = repo ?: return
        val sc = tileScope ?: return
        val k = spec.cacheKey
        if (pending.size >= MAX_PENDING_TILES || !pending.add(k)) return
        val gen = generation
        val epoch = ep
        val pageToRender = page
        val renderW = viewport?.lw ?: baseW
        val renderH = viewport?.lh ?: baseH
        val tintToRender = tint
        val brightnessToRender = brightness
        var renderFailed = false
        val job = sc.launch(renderDispatcher) {
            var bitmap: Bitmap? = null
            try {
                currentCoroutineContext().ensureActive()
                bitmap = r.renderTile(
                    pageToRender, renderW, renderH,
                    spec.x, spec.y, spec.width, spec.height,
                    tintToRender, brightnessToRender
                )
                currentCoroutineContext().ensureActive()
                if (gen == generation && tiled && epoch == TileCache.epoch) {
                    TileCache.put(k, bitmap!!)
                    bitmap = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                renderFailed = true
            } finally {
                bitmap?.let { if (!it.isRecycled) it.recycle() }
            }
        }
        job.invokeOnCompletion {
            mainHandler.post {
                if (gen == generation) {
                    pending.remove(k)
                    if (renderFailed) failed.add(k)
                    if (tiled) invalidate()
                }
            }
        }
    }
}
