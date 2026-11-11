package com.moye.pdfink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import kotlinx.coroutines.Job
import kotlin.math.floor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlin.math.hypot
import kotlin.math.min

/** MotionEvent.CLASSIFICATION_PALM：API 34 的 flag-gated 常量，公开 android.jar 不含该字段，按 AOSP 取字面值 0x2 */
private const val CLASSIFICATION_PALM = 0x2

/** 题目页 / 草稿页之上的笔迹层：三缓冲（已落笔 / 当前笔 / 预测） + 可重放 Mark 序列。 */
class InkView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Host {
        val tool: Int
        val inkColor: Int
        val inkWidth: Float
        val inkAlpha: Int
        val inkShape: Int
        val eraserWidth: Float
        val browseMode: Boolean
        val stylusOnly: Boolean
        val palmGuard: Boolean
        val activeLayer: Int
        val visibleLayers: Set<Int>
        val store: InkStore
        val ioScope: CoroutineScope
        fun onFocus(key: String)
        fun onCommit(key: String, before: List<Mark>, after: List<Mark>)
        fun onSelectionChanged(active: Boolean)
        fun onRequestNote(x: Float, y: Float, existing: String?, cb: (String?) -> Unit)
    }

    private data class Selection(
        val indices: List<Int>,
        val originals: List<Mark>,
        val cx: Float,
        val cy: Float,
        var dx: Float = 0f,
        var dy: Float = 0f,
        var scale: Float = 1f,
        var startDx: Float = 0f,
        var startDy: Float = 0f,
        var startScale: Float = 1f,
        var startDist: Float = 1f
    )

    private class FloatBuffer(initialCapacity: Int = 64) {
        private var values = FloatArray(initialCapacity)
        var size: Int = 0
            private set

        fun add(value: Float) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        operator fun get(index: Int): Float = values[index]
        operator fun set(index: Int, value: Float) { values[index] = value }
        fun clear() { size = 0 }
        fun getOrElse(index: Int, defaultValue: () -> Float): Float =
            if (index in 0 until size) values[index] else defaultValue()
        fun toFloatArray(): FloatArray = values.copyOf(size)
    }

    private var host: Host? = null
    private var key = ""
    private var loadJob: Job? = null
    private var loadGeneration = 0
    private var loadingMarks = false
    private val marks = ArrayList<Mark>()
    private val spatialIndex = HashMap<Int, MutableList<Int>>()
    private var spatialColumns = 1
    private var spatialReady = false
    private val spatialCellPx = 256f
    private var before: List<Mark> = emptyList()

    private var base: Bitmap? = null
    private var baseC: Canvas? = null
    private var live: Bitmap? = null
    private var liveC: Canvas? = null
    /** 预测墨迹复用固定线段缓冲，不在每次 MOVE 中分配新数组。 */
    private val predSeg = FloatArray(4)
    private val predictionBounds = FloatArray(4)
    private var predictionBoundsValid = false
    private var predictionVisible = false

    private var drawing = false
    private var erasing = false
    private var lassoing = false
    private var dragMode = 0 // 0 无 1 移动 2 缩放
    private var selection: Selection? = null

    private val livePts = FloatBuffer(128)
    private val liveWs = FloatBuffer(64)
    private val erasePts = FloatBuffer(64)
    private val lassoPts = FloatBuffer(128)
    private val liveShapePoints = FloatArray(4)
    private val liveShapeWidths = floatArrayOf(1f, 1f)
    private var liveMark: Mark? = null
    private var liveShape = Shapes.FREE
    private var liveLayer = Layers.ANSWER
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var lastW = 1f
    private var tailX = 0f
    private var tailY = 0f
    private var nextSeg = 1
    private var dragStartX = 0f
    private var dragStartY = 0f

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scratch = Path()
    private val touchMatrix = Matrix()
    private val touchMatrixValues = FloatArray(9)
    private val clearStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val clearRect = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2F6FED.toInt()
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF2F6FED.toInt()
    }
    private val predPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        alpha = 110
    }
    private var liveDirty: FloatArray? = null

    /** 底图、矢量批注和触摸事件共用同一连续缩放 viewport。 */
    var viewport: Viewport? = null
    /** 倍率偏离 1× 时切矢量重绘，避免笔迹位图跟不上连续缩放。 */
    var hiResMode: Boolean = false
        set(v) {
            if (v != field) { field = v; invalidate() }
        }
    private var pageTint = InkPalette.WHITE
    private fun mapInk(argb: Int): Int = InkPalette.mapColor(argb, pageTint)

    private val unit: Float get() = min(width, height).toFloat().coerceAtLeast(1f)
    private val density: Float get() = resources.displayMetrics.density

    fun attach(h: Host, k: String) {
        loadJob?.cancel()
        loadGeneration++
        val generation = loadGeneration
        host = h
        key = k
        loadingMarks = true
        marks.clear()
        selection = null
        h.onSelectionChanged(false)
        cancelGesture()
        rebuild()
        loadJob = h.store.loadAsync(k, h.ioScope) { loaded ->
            if (generation != loadGeneration || host !== h || key != k) return@loadAsync
            marks.clear()
            marks.addAll(loaded)
            loadingMarks = false
            rebuild()
        }
    }

    /** 换卷前提交当前手势并排入原文档写入队列，不阻塞主线程等待磁盘。 */
    fun persistForDocumentSwitch() {
        if (loadingMarks || key.isEmpty()) return
        finish()
        persist()
    }

    /** RecyclerView 回收页面时取消尚未完成的旧笔迹读取。 */
    fun cancelPendingLoad() {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        loadingMarks = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { rebuild() }

    private fun ensureBuffers() {
        if (width <= 0 || height <= 0) return
        if (base == null || base!!.width != width || base!!.height != height) {
            base?.recycle(); live?.recycle()
            base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            baseC = Canvas(base!!)
            live = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            liveC = Canvas(live!!)
        }
    }

    private fun visible(m: Mark): Boolean = m.layer in (host?.visibleLayers ?: setOf(0))

    private fun indexMark(index: Int, mark: Mark) {
        if (width <= 0 || height <= 0) return
        val bounds = GeometryCore.markBounds(mark)
        val left = (bounds[0] * width).toInt().coerceIn(0, width - 1)
        val top = (bounds[1] * height).toInt().coerceIn(0, height - 1)
        val right = (bounds[2] * width).toInt().coerceIn(left, width - 1)
        val bottom = (bounds[3] * height).toInt().coerceIn(top, height - 1)
        val x0 = (left / spatialCellPx).toInt()
        val x1 = (right / spatialCellPx).toInt()
        val y0 = (top / spatialCellPx).toInt()
        val y1 = (bottom / spatialCellPx).toInt()
        for (gy in y0..y1) for (gx in x0..x1) {
            val cell = gy * spatialColumns + gx
            spatialIndex.getOrPut(cell) { ArrayList() }.add(index)
        }
    }

    private fun rebuildSpatialIndex() {
        spatialIndex.clear()
        spatialColumns = ((width / spatialCellPx).toInt() + 1).coerceAtLeast(1)
        if (width <= 0 || height <= 0) {
            spatialReady = false
            return
        }
        marks.forEachIndexed(::indexMark)
        spatialReady = true
    }

    private fun indicesInRect(rect: FloatArray): List<Int> {
        if (marks.size < 80 || width <= 0 || height <= 0) return marks.indices.toList()
        if (!spatialReady) rebuildSpatialIndex()
        if (!spatialReady) return marks.indices.toList()
        val left = rect[0].toInt().coerceIn(0, width - 1)
        val top = rect[1].toInt().coerceIn(0, height - 1)
        val right = rect[2].toInt().coerceIn(left, width - 1)
        val bottom = rect[3].toInt().coerceIn(top, height - 1)
        val x0 = (left / spatialCellPx).toInt()
        val x1 = (right / spatialCellPx).toInt()
        val y0 = (top / spatialCellPx).toInt()
        val y1 = (bottom / spatialCellPx).toInt()
        val found = LinkedHashSet<Int>()
        for (gy in y0..y1) for (gx in x0..x1) {
            spatialIndex[gy * spatialColumns + gx]?.let(found::addAll)
        }
        return found.sorted()
    }

    private fun drawAllBase(c: Canvas) {
        rebuildSpatialIndex()
        base?.eraseColor(Color.TRANSPARENT)
        for (m in marks) {
            if (!visible(m)) continue
            InkGeometry.drawMark(c, m, width.toFloat(), height.toFloat(),
                strokePaint, scratch, textPaint, bgPaint, ::mapInk)
        }
    }

    private fun clearRegion(c: Canvas?, r: FloatArray?) {
        if (c == null || r == null) return
        if (r[2] <= r[0] || r[3] <= r[1]) return
        c.drawRect(RectF(r[0], r[1], r[2], r[3]), clearRect)
    }

    private fun rebuild() {
        ensureBuffers()
        drawAllBase(baseC ?: return)
        live?.eraseColor(Color.TRANSPARENT)
        predictionVisible = false
        predictionBoundsValid = false
        liveDirty = null
        invalidate()
    }

    /** 局部重绘：橡皮抬手后只刷被扫过的矩形，不再重建整页位图。 */
    private fun rebuildRegion(dirty: FloatArray) {
        val c = baseC ?: return
        val r = GeometryCore.clampRect(dirty, width.toFloat(), height.toFloat())
        rebuildSpatialIndex()
        if (r[2] <= r[0] || r[3] <= r[1]) { rebuild(); return }
        clearRegion(c, r)
        c.save()
        c.clipRect(r[0], r[1], r[2], r[3])
        for (index in indicesInRect(r)) {
            val m = marks[index]
            if (!visible(m)) continue
            val b = GeometryCore.markBounds(m)
            val pb = floatArrayOf(b[0] * width, b[1] * height, b[2] * width, b[3] * height)
            if (!GeometryCore.overlaps(pb, r)) continue
            InkGeometry.drawMark(c, m, width.toFloat(), height.toFloat(),
                strokePaint, scratch, textPaint, bgPaint, ::mapInk)
        }
        c.restore()
        clearRegion(liveC, r)
        invalidate(r[0].toInt(), r[1].toInt(), r[2].toInt() + 1, r[3].toInt() + 1)
    }

    override fun onDraw(canvas: Canvas) {
        val vp = viewport
        if (hiResMode && vp != null && vp.lw > 0 && vp.lh > 0) {
            // 与题目底图共用同一组 ZoomHost 下发的逻辑坐标，像素级对齐
            val sc = vp.bucket
            val displayFactor = ZoomTransform.renderToDisplayFactor(vp.scale, sc)
            val lw = vp.lw.toFloat()
            val lh = vp.lh.toFloat()
            // bbox 粗筛用可见逻辑窗，绘制定位用 originX/originY
            val r = floatArrayOf(vp.visLeft, vp.visTop,
                vp.visLeft + vp.visW.coerceAtLeast(1),
                vp.visTop + vp.visH.coerceAtLeast(1))
            canvas.save()
            canvas.translate(-vp.originX, -vp.originY)
            canvas.scale(displayFactor, displayFactor)
            // 栅格清晰度与页面实际显示倍率分开，书写矢量坐标使用同一显示变换。
            // 空间索引先筛出与当前视口相交的批注，再执行精确 bbox 检查。
            val pageRect = floatArrayOf(
                r[0] / sc, r[1] / sc, r[2] / sc, r[3] / sc
            )
            for (index in indicesInRect(pageRect)) {
                val m = marks[index]
                if (!visible(m)) continue
                val b = GeometryCore.markBounds(m)
                val pb = floatArrayOf(b[0] * lw, b[1] * lh, b[2] * lw, b[3] * lh)
                if (!GeometryCore.overlaps(pb, r)) continue
                InkGeometry.drawMark(canvas, m, lw, lh,
                    strokePaint, scratch, textPaint, bgPaint, ::mapInk)
            }
            // 正在写的一笔仍走局部位图，按逻辑画布缩放铺开；抬手并入 marks 后即锐利
            canvas.save()
            canvas.scale(sc, sc)
            live?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            if (predictionVisible) {
                scratch.reset()
                scratch.moveTo(predSeg[0], predSeg[1])
                scratch.lineTo(predSeg[2], predSeg[3])
                canvas.drawPath(scratch, predPaint)
            }
            canvas.restore()
            canvas.restore()
            return
        }
        if (base == null) {
            ensureBuffers()
            drawAllBase(baseC ?: return)
        }
        val panViewport = viewport
        val panOnly = panViewport != null &&
            kotlin.math.abs(panViewport.scale - 1f) <= 0.0001f &&
            (kotlin.math.abs(panViewport.originX) > 0.0001f ||
                kotlin.math.abs(panViewport.originY) > 0.0001f)
        if (panOnly) {
            canvas.save()
            canvas.translate(-panViewport!!.originX, -panViewport!!.originY)
        }
        base?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        live?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (predictionVisible) {
            scratch.reset()
            scratch.moveTo(predSeg[0], predSeg[1])
            scratch.lineTo(predSeg[2], predSeg[3])
            canvas.drawPath(scratch, predPaint)
        }
        if (panOnly) canvas.restore()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val h = host ?: return false
        if (h.browseMode || !isEnabled) return false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isPalm(ev: MotionEvent, i: Int): Boolean =
        ev.getSize(i) > 0.34f ||
            (ev.getTouchMajor(i) > 0f && ev.getTouchMajor(i) > 26f * density)

    private fun blocked(ev: MotionEvent): Boolean {
        val h = host ?: return false
        if (h.stylusOnly && ev.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return true
        if (h.palmGuard && isPalm(ev, 0)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ev.classification == CLASSIFICATION_PALM
        ) return true
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val h = host ?: return false
        if (h.browseMode) return false
        if (loadingMarks) return true
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && blocked(ev)) return false
        // 低内存回收后，下一次编辑前重建笔迹缓冲，避免 liveC/baseC 为空导致笔迹不能提交。
        if (base == null || live == null) {
            ensureBuffers()
            if (baseC != null) drawAllBase(baseC!!)
        }
        val vp = viewport
        val needsViewportMap = vp != null && vp.scale > 0f &&
            (hiResMode || kotlin.math.abs(vp.originX) > 0.0001f ||
                kotlin.math.abs(vp.originY) > 0.0001f)
        val mapped = if (needsViewportMap && vp != null) {
            MotionEvent.obtain(ev).apply {
                val inv = 1f / vp.scale
                touchMatrixValues[0] = inv
                touchMatrixValues[1] = 0f
                touchMatrixValues[2] = ZoomTransform.pageCoordinate(0f, vp.originX, vp.scale)
                touchMatrixValues[3] = 0f
                touchMatrixValues[4] = inv
                touchMatrixValues[5] = ZoomTransform.pageCoordinate(0f, vp.originY, vp.scale)
                touchMatrixValues[6] = 0f
                touchMatrixValues[7] = 0f
                touchMatrixValues[8] = 1f
                touchMatrix.setValues(touchMatrixValues)
                transform(touchMatrix)
            }
        } else ev
        return try {
            handleTouchEvent(mapped, h)
        } finally {
            if (mapped !== ev) mapped.recycle()
        }
    }

    private fun handleTouchEvent(ev: MotionEvent, h: Host): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                h.onFocus(key)
                when {
                    h.tool == Tools.NOTE -> {
                        before = ArrayList(marks)
                        requestNote(ev, h)
                    }
                    // 套索内部决定是否沿用「圈选前」快照；拖动选区不应覆盖它
                    h.tool == Tools.LASSO -> startLassoOrDrag(ev, h)
                    h.tool == Tools.ERASER -> {
                        before = ArrayList(marks)
                        startErase(ev, h)
                    }
                    else -> {
                        before = ArrayList(marks)
                        startMark(ev, h)
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragMode != 0) { dragMode = 0; commitSelection() }
                cancelGesture()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawing) addPoints(ev)
                else if (erasing) erasePoints(ev)
                else if (lassoing) addLassoPoints(ev)
                else if (dragMode != 0) moveSelection(ev)
                return drawing || erasing || lassoing || dragMode != 0
            }
            MotionEvent.ACTION_UP -> {
                finish()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelGesture()
                return true
            }
            else -> return false
        }
    }

    // ---------- 自由笔：增量段绘制 + 低延迟预测 ----------
    private fun startMark(ev: MotionEvent, h: Host) {
        val x = (ev.x / width).coerceIn(0f, 1f)
        val y = (ev.y / height).coerceIn(0f, 1f)
        lastX = x; lastY = y; lastT = ev.eventTime; lastW = 1f
        tailX = x * width; tailY = y * height
        nextSeg = 1
        liveShape = if (h.tool == Tools.SHAPE) h.inkShape else Shapes.FREE
        liveLayer = h.activeLayer
        livePts.clear(); liveWs.clear()
        liveMark = if (liveShape == Shapes.FREE) {
            livePts.add(x); livePts.add(y); liveWs.add(1f)
            null
        } else {
            liveShapePoints[0] = x
            liveShapePoints[1] = y
            liveShapePoints[2] = x
            liveShapePoints[3] = y
            liveShapeWidths[0] = 1f
            liveShapeWidths[1] = 1f
            Mark(h.inkColor, h.inkWidth, h.inkAlpha, liveShape,
                liveShapePoints, liveShapeWidths, liveLayer)
        }
        drawing = true
        erasing = false
        live?.eraseColor(Color.TRANSPARENT)
        predictionVisible = false
        predictionBoundsValid = false; liveDirty = null
        strokePaint.color = mapInk(Color.argb(h.inkAlpha, Color.red(h.inkColor),
            Color.green(h.inkColor), Color.blue(h.inkColor)))
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeWidth = h.inkWidth * unit
        if (liveShape == Shapes.FREE) {
            liveC?.drawPoint(tailX, tailY, strokePaint)
        }
        invalidate()
    }

    private fun addPoints(ev: MotionEvent) {
        val h = host ?: return
        val stylus = ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        fun take(x: Float, y: Float, t: Long, pressure: Float) {
            val nx = (x / width).coerceIn(0f, 1f)
            val ny = (y / height).coerceIn(0f, 1f)
            if (liveShape != Shapes.FREE) {
                liveShapePoints[2] = nx
                liveShapePoints[3] = ny
                return
            }
            val distPx = hypot((nx - lastX) * width, (ny - lastY) * height)
            if (distPx < 1.2f) return
            val dt = (t - lastT).coerceAtLeast(1L)
            val speed = distPx / dt
            val speedF = when {
                speed < 0.15f -> 1.15f
                speed > 1.6f -> 0.82f
                else -> 1.15f - (speed - 0.15f) * 0.227f
            }
            val pressF = if (stylus) (0.65f + 0.5f * pressure).coerceIn(0.7f, 1.3f) else 1f
            val wv = 0.65f * lastW + 0.35f * (speedF * pressF)
            livePts.add(nx); livePts.add(ny); liveWs.add(wv)
            lastX = nx; lastY = ny; lastT = t; lastW = wv
        }
        for (i in 0 until ev.historySize) {
            take(ev.getHistoricalX(i), ev.getHistoricalY(i),
                ev.getHistoricalEventTime(i), ev.getHistoricalPressure(i))
        }
        take(ev.x, ev.y, ev.eventTime, ev.pressure)
        if (liveShape == Shapes.FREE) drawNewSegments(h) else drawShapeLive(h)
        drawPrediction(h)
    }

    private fun drawNewSegments(h: Host) {
        val c = liveC ?: return
        val n = livePts.size / 2
        val base = (h.inkWidth * unit).coerceAtLeast(0.8f)
        strokePaint.color = mapInk(Color.argb(h.inkAlpha, Color.red(h.inkColor),
            Color.green(h.inkColor), Color.blue(h.inkColor)))
        strokePaint.style = Paint.Style.STROKE
        var dirtyLeft = Float.MAX_VALUE
        var dirtyTop = Float.MAX_VALUE
        var dirtyRight = -Float.MAX_VALUE
        var dirtyBottom = -Float.MAX_VALUE
        var dirtyPadding = 0f
        // 历史采样仍逐点保留，但每批新增曲线只请求局部重绘。
        var i = nextSeg
        while (i <= n - 2) {
            val startX = tailX
            val startY = tailY
            val cx = livePts[2 * i] * width
            val cy = livePts[2 * i + 1] * height
            val ex = (livePts[2 * i] + livePts[2 * (i + 1)]) * 0.5f * width
            val ey = (livePts[2 * i + 1] + livePts[2 * (i + 1) + 1]) * 0.5f * height
            scratch.reset()
            scratch.moveTo(startX, startY)
            scratch.quadTo(cx, cy, ex, ey)
            strokePaint.strokeWidth = base *
                (liveWs.getOrElse(i - 1) { 1f } + liveWs.getOrElse(i) { 1f }) * 0.5f
            c.drawPath(scratch, strokePaint)
            val pad = strokePaint.strokeWidth * 0.75f + 2f * density
            dirtyPadding = maxOf(dirtyPadding, pad)
            dirtyLeft = minOf(dirtyLeft, startX, cx, ex)
            dirtyTop = minOf(dirtyTop, startY, cy, ey)
            dirtyRight = maxOf(dirtyRight, startX, cx, ex)
            dirtyBottom = maxOf(dirtyBottom, startY, cy, ey)
            tailX = ex; tailY = ey
            i++
        }
        nextSeg = n - 1
        if (dirtyLeft != Float.MAX_VALUE) {
            invalidateBounds(
                dirtyLeft - dirtyPadding, dirtyTop - dirtyPadding,
                dirtyRight + dirtyPadding, dirtyBottom + dirtyPadding
            )
        }
    }

    private fun drawShapeLive(h: Host) {
        val c = liveC ?: return
        val mark = liveMark ?: return
        val nextDirty = shapeBounds(mark)
        val previousDirty = liveDirty

        // 只清除上一帧和当前形状覆盖的区域，不再清空横屏整页实时画布。
        clearRegion(c, previousDirty)
        clearRegion(c, nextDirty)
        c.save()
        c.clipRect(nextDirty[0], nextDirty[1], nextDirty[2], nextDirty[3])
        InkGeometry.drawMark(c, mark, width.toFloat(), height.toFloat(),
            strokePaint, scratch, textPaint, bgPaint, ::mapInk)
        c.restore()

        liveDirty = nextDirty
        invalidateRect(previousDirty)
        invalidateRect(nextDirty)
    }

    private fun shapeBounds(mark: Mark): FloatArray {
        val x0 = mark.pts[0] * width
        val y0 = mark.pts[1] * height
        val x1 = mark.pts[2] * width
        val y1 = mark.pts[3] * height
        val base = mark.width * unit
        val padding = if (mark.shape == Shapes.ARROW) {
            base * 6f + unit * 0.02f + 4f * density
        } else {
            base * 1.5f + 4f * density
        }
        return floatArrayOf(
            (minOf(x0, x1) - padding).coerceAtLeast(0f),
            (minOf(y0, y1) - padding).coerceAtLeast(0f),
            (maxOf(x0, x1) + padding).coerceAtMost(width.toFloat()),
            (maxOf(y0, y1) + padding).coerceAtMost(height.toFloat())
        )
    }

    private fun invalidateBounds(leftF: Float, topF: Float, rightF: Float, bottomF: Float) {
        val left = leftF.toInt().coerceIn(0, width)
        val top = topF.toInt().coerceIn(0, height)
        val right = (rightF.toInt() + 1).coerceIn(left, width)
        val bottom = (bottomF.toInt() + 1).coerceIn(top, height)
        if (right > left && bottom > top) invalidate(left, top, right, bottom)
    }

    private fun invalidateRect(rect: FloatArray?) {
        if (rect != null) invalidateBounds(rect[0], rect[1], rect[2], rect[3])
    }

    /** 用最后一段速度外推一小截，只记录线段，onDraw 直画，抬手即清。 */
    private fun drawPrediction(h: Host) {
        if (liveShape != Shapes.FREE || livePts.size < 4) return
        val n = livePts.size / 2
        val x0 = livePts[2 * (n - 2)] * width
        val y0 = livePts[2 * (n - 2) + 1] * height
        val x1 = livePts[2 * (n - 1)] * width
        val y1 = livePts[2 * (n - 1) + 1] * height
        val vx = x1 - x0
        val vy = y1 - y0
        val px = (x1 + vx.coerceIn(-24f * density, 24f * density))
            .coerceIn(0f, width.toFloat())
        val py = (y1 + vy.coerceIn(-24f * density, 24f * density))
            .coerceIn(0f, height.toFloat())
        predPaint.color = mapInk(Color.argb(
            110, Color.red(h.inkColor), Color.green(h.inkColor), Color.blue(h.inkColor)
        ))
        predPaint.strokeWidth = (h.inkWidth * unit).coerceAtLeast(0.8f) * 0.9f
        val pad = (h.inkWidth * unit).coerceAtLeast(0.8f) * 0.75f + 2f * density
        val nextLeft = minOf(x1, px) - pad
        val nextTop = minOf(y1, py) - pad
        val nextRight = maxOf(x1, px) + pad
        val nextBottom = maxOf(y1, py) + pad
        val dirtyLeft = if (predictionBoundsValid) minOf(predictionBounds[0], nextLeft)
            else nextLeft
        val dirtyTop = if (predictionBoundsValid) minOf(predictionBounds[1], nextTop)
            else nextTop
        val dirtyRight = if (predictionBoundsValid) maxOf(predictionBounds[2], nextRight)
            else nextRight
        val dirtyBottom = if (predictionBoundsValid) maxOf(predictionBounds[3], nextBottom)
            else nextBottom
        predictionBounds[0] = nextLeft
        predictionBounds[1] = nextTop
        predictionBounds[2] = nextRight
        predictionBounds[3] = nextBottom
        predictionBoundsValid = true
        predSeg[0] = x1
        predSeg[1] = y1
        predSeg[2] = px
        predSeg[3] = py
        predictionVisible = true
        invalidateBounds(dirtyLeft, dirtyTop, dirtyRight, dirtyBottom)
    }

    // ---------- 橡皮 ----------
    private fun startErase(ev: MotionEvent, h: Host) {
        val x = (ev.x / width).coerceIn(0f, 1f)
        val y = (ev.y / height).coerceIn(0f, 1f)
        lastX = x; lastY = y
        erasePts.clear(); erasePts.add(x); erasePts.add(y)
        erasing = true; drawing = false
        clearStroke.strokeWidth = h.eraserWidth * unit
        val c = baseC ?: return
        c.drawPoint(x * width, y * height, clearStroke)
        invalidate()
    }

    private fun erasePoints(ev: MotionEvent) {
        val c = baseC ?: return
        fun take(x: Float, y: Float) {
            val nx = (x / width).coerceIn(0f, 1f)
            val ny = (y / height).coerceIn(0f, 1f)
            if (hypot((nx - lastX) * width, (ny - lastY) * height) < 1.2f) return
            scratch.reset()
            scratch.moveTo(lastX * width, lastY * height)
            scratch.lineTo(nx * width, ny * height)
            c.drawPath(scratch, clearStroke)
            erasePts.add(nx); erasePts.add(ny)
            lastX = nx; lastY = ny
        }
        for (i in 0 until ev.historySize) {
            take(ev.getHistoricalX(i), ev.getHistoricalY(i))
        }
        take(ev.x, ev.y)
        invalidate()
    }

    private fun finishErase() {
        erasing = false
        val h = host ?: return
        val norm = erasePts.toFloatArray()
        val px = FloatArray(norm.size) { i ->
            if (i % 2 == 0) norm[i] * width else norm[i] * height
        }
        val radius = h.eraserWidth * unit * 0.5f
        val layers = h.visibleLayers
        val kept = InkGeometry.crop(ArrayList(marks), px, radius,
            width.toFloat(), height.toFloat()) { it in layers }
        val pad = radius + h.inkWidth * unit * 2f + 6f
        val dirty = GeometryCore.eraseBounds(px, pad)
        marks.clear(); marks.addAll(kept)
        rebuildRegion(dirty)
        commit()
    }

    // ---------- 套索 / 选区 ----------
    private fun startLassoOrDrag(ev: MotionEvent, h: Host) {
        val x = (ev.x / width).coerceIn(0f, 1f)
        val y = (ev.y / height).coerceIn(0f, 1f)
        val s = selection
        if (s != null) {
            val b = selectionBox(s)
            val handle = 26f * density
            if (hypot((x * width - b[2]), (y * height - b[3])) < handle) {
                dragMode = 2
                s.startDist = hypot(x - s.cx, y - s.cy).coerceAtLeast(0.01f)
                s.startScale = s.scale
                return
            }
            if (x * width >= b[0] && x * width <= b[2] &&
                y * height >= b[1] && y * height <= b[3]
            ) {
                dragMode = 1
                dragStartX = x; dragStartY = y
                s.startDx = s.dx; s.startDy = s.dy
                return
            }
            restoreSelection()
        }
        before = ArrayList(marks)
        lassoPts.clear(); lassoPts.add(x); lassoPts.add(y)
        lastX = x; lastY = y
        lassoing = true
        live?.eraseColor(Color.TRANSPARENT)
        liveDirty = null
    }

    private fun addLassoPoints(ev: MotionEvent) {
        fun take(x: Float, y: Float) {
            val nx = (x / width).coerceIn(0f, 1f)
            val ny = (y / height).coerceIn(0f, 1f)
            if (hypot((nx - lastX) * width, (ny - lastY) * height) < 2f) return
            lassoPts.add(nx); lassoPts.add(ny)
            lastX = nx; lastY = ny
        }
        for (i in 0 until ev.historySize) {
            take(ev.getHistoricalX(i), ev.getHistoricalY(i))
        }
        take(ev.x, ev.y)
        drawLassoLive()
    }

    private fun drawLassoLive() {
        val c = liveC ?: return
        clearRegion(c, liveDirty)
        val poly = lassoPts.toFloatArray()
        liveDirty = floatArrayOf(0f, 0f, width.toFloat(), height.toFloat())
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (poly.size >= 4) {
            scratch.reset()
            scratch.moveTo(poly[0] * width, poly[1] * height)
            var i = 2
            while (i + 1 < poly.size) {
                scratch.lineTo(poly[i] * width, poly[i + 1] * height)
                i += 2
            }
            scratch.close()
            c.drawPath(scratch, boxPaint)
        }
        invalidate()
    }

    private fun finishLasso() {
        lassoing = false
        val poly = lassoPts.toFloatArray()
        val h = host ?: return
        if (poly.size < 6) { cancelGesture(); return }
        val idx = ArrayList<Int>()
        val orig = ArrayList<Mark>()
        for (i in marks.indices) {
            val m = marks[i]
            if (m.layer in h.visibleLayers && GeometryCore.markInside(m, poly)) {
                idx.add(i); orig.add(m)
            }
        }
        if (orig.isEmpty()) { cancelGesture(); return }
        val b = GeometryCore.bounds(poly)
        val cx = (b[0] + b[2]) * 0.5f
        val cy = (b[1] + b[3]) * 0.5f
        for (i in idx.asReversed()) marks.removeAt(i)
        selection = Selection(idx, orig, cx, cy)
        drawAllBase(baseC ?: return)
        drawSelectionLive()
        h.onSelectionChanged(true)
        invalidate()
    }

    private fun transformed(s: Selection): List<Mark> = s.originals.map {
        GeometryCore.transform(it, s.cx, s.cy, s.dx, s.dy, s.scale)
    }

    private fun selectionBox(s: Selection): FloatArray {
        val ts = transformed(s)
        if (ts.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (m in ts) {
            val q = GeometryCore.markBounds(m)
            l = minOf(l, q[0] * width); t = minOf(t, q[1] * height)
            r = maxOf(r, q[2] * width); b = maxOf(b, q[3] * height)
        }
        val pad = 10f * density
        return floatArrayOf(l - pad, t - pad, r + pad, b + pad)
    }

    private fun drawSelectionLive() {
        val c = liveC ?: return
        val s = selection ?: return
        clearRegion(c, liveDirty)
        liveDirty = floatArrayOf(0f, 0f, width.toFloat(), height.toFloat())
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for (m in transformed(s)) {
            InkGeometry.drawMark(c, m, width.toFloat(), height.toFloat(),
                strokePaint, scratch, textPaint, bgPaint, ::mapInk)
        }
        val b = selectionBox(s)
        c.drawRect(RectF(b[0], b[1], b[2], b[3]), boxPaint)
        c.drawCircle(b[2], b[3], 7f * density, handlePaint)
        invalidate()
    }

    private fun moveSelection(ev: MotionEvent) {
        val s = selection ?: return
        val x = (ev.x / width).coerceIn(0f, 1f)
        val y = (ev.y / height).coerceIn(0f, 1f)
        if (dragMode == 1) {
            s.dx = s.startDx + (x - dragStartX)
            s.dy = s.startDy + (y - dragStartY)
        } else if (dragMode == 2) {
            val d = hypot(x - s.cx, y - s.cy)
            s.scale = (s.startScale * d / s.startDist).coerceIn(0.3f, 3f)
        }
        drawSelectionLive()
    }

    private fun commitSelection() {
        val s = selection ?: return
        marks.addAll(transformed(s))
        selection = null
        host?.onSelectionChanged(false)
        live?.eraseColor(Color.TRANSPARENT)
        liveDirty = null
        drawAllBase(baseC ?: return)
        invalidate()
        commit()
    }

    private fun restoreSelection() {
        val s = selection ?: return
        val list = marks.toMutableList()
        s.indices.forEachIndexed { i, at ->
            if (at <= list.size) list.add(at, s.originals[i]) else list.add(s.originals[i])
        }
        marks.clear(); marks.addAll(list)
        selection = null
        host?.onSelectionChanged(false)
        live?.eraseColor(Color.TRANSPARENT)
        liveDirty = null
        rebuild()
    }

    fun deleteSelection() {
        val s = selection ?: return
        drawAllBase(baseC ?: return)
        selection = null
        host?.onSelectionChanged(false)
        live?.eraseColor(Color.TRANSPARENT)
        liveDirty = null
        invalidate()
        commit()
    }

    fun cancelSelection() {
        if (selection == null) return
        restoreSelection()
    }

    fun hasSelection(): Boolean = selection != null

    // ---------- 便签 ----------
    private fun noteAt(x: Float, y: Float): Int {
        var best = -1
        var bd = 40f * density
        for (i in marks.indices) {
            val m = marks[i]
            if (m.shape != Shapes.NOTE || !visible(m)) continue
            val d = hypot((m.pts[0] - x) * width, (m.pts[1] - y) * height)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    private fun requestNote(ev: MotionEvent, h: Host) {
        val x = (ev.x / width).coerceIn(0f, 1f)
        val y = (ev.y / height).coerceIn(0f, 1f)
        val idx = noteAt(x, y)
        h.onRequestNote(x, y, if (idx >= 0) marks[idx].text else null) { txt ->
            if (txt.isNullOrBlank()) return@onRequestNote
            val m = Mark(h.inkColor, h.inkWidth, 255, Shapes.NOTE,
                floatArrayOf(x, y), null, h.activeLayer, txt)
            if (idx >= 0) marks[idx] = m else marks.add(m)
            rebuild()
            commit()
        }
    }

    // ---------- 收尾 / 历史 ----------
    private fun finish() {
        when {
            erasing -> finishErase()
            lassoing -> finishLasso()
            dragMode != 0 -> {
                dragMode = 0
                commitSelection()
            }
            drawing -> finishMark()
        }
    }

    private fun finishMark() {
        if (!drawing) return
        drawing = false
        val h = host
        val m = if (liveShape == Shapes.FREE && h != null) {
            Mark(h.inkColor, h.inkWidth, h.inkAlpha, liveShape,
                livePts.toFloatArray(), liveWs.toFloatArray(), liveLayer)
        } else {
            liveMark?.let {
                it.copyWith(pts = it.pts.copyOf(), ws = it.ws?.copyOf())
            }
        }
        val c = liveC
        // 补最后一段：从 tail 直接连到末点
        if (m != null && liveShape == Shapes.FREE && c != null && livePts.size >= 4) {
            val n = livePts.size / 2
            scratch.reset()
            scratch.moveTo(tailX, tailY)
            scratch.lineTo(livePts[2 * (n - 1)] * width, livePts[2 * (n - 1) + 1] * height)
            strokePaint.strokeWidth = (host?.inkWidth ?: 0.007f) * unit *
                (liveWs.getOrElse(n - 2) { 1f } + liveWs.getOrElse(n - 1) { 1f }) * 0.5f
            c.drawPath(scratch, strokePaint)
        }
        live?.eraseColor(Color.TRANSPARENT)
        predictionVisible = false
        predictionBoundsValid = false; liveDirty = null
        var changed = false
        if (m != null) {
            val tiny = m.shape != Shapes.FREE && m.shape != Shapes.NOTE && m.pts.size >= 4 &&
                hypot((m.pts[2] - m.pts[0]) * width, (m.pts[3] - m.pts[1]) * height) < 4f
            if (!tiny) {
                marks.add(m)
                val index = marks.lastIndex
                val canvas = baseC
                if (canvas != null && visible(m)) {
                    InkGeometry.drawMark(canvas, m, width.toFloat(), height.toFloat(),
                        strokePaint, scratch, textPaint, bgPaint, ::mapInk)
                }
                if (spatialReady) indexMark(index, m)
                changed = true
            }
        }
        if (changed) commit()
        invalidate()
    }

    private fun cancelGesture() {
        drawing = false; erasing = false; lassoing = false; dragMode = 0
        liveMark = null
        live?.eraseColor(Color.TRANSPARENT)
        predictionVisible = false
        predictionBoundsValid = false; liveDirty = null
        if (selection != null) drawSelectionLive()
        invalidate()
    }

    private fun commit() {
        val h = host ?: return
        h.onCommit(key, before, ArrayList(marks))
        persist()
    }

    private fun persist() {
        val h = host ?: return
        if (key.isEmpty()) return
        // InkStore 自身是单线程串行队列，不必再经 Activity 的协程作用域
        h.store.save(key, ArrayList(marks))
    }

    fun flush() {
        val h = host ?: return
        if (loadingMarks || key.isEmpty()) return
        h.store.saveNow(key, ArrayList(marks))
    }

    /** 由全局时间轴驱动，不产生新历史。 */
    fun restoreLocal(l: List<Mark>) {
        selection = null
        marks.clear(); marks.addAll(l)
        rebuild()
        flush()
    }

    fun clearPage(): Boolean {
        if (marks.isEmpty()) return false
        before = ArrayList(marks)
        val vis = host?.visibleLayers ?: setOf(0)
        val removed = marks.removeAll { it.layer in vis }
        if (!removed) return false
        selection = null
        rebuild()
        commit()
        return true
    }

    fun clearLocal() {
        selection = null
        marks.clear()
        rebuild()
    }

    fun hasMarks(): Boolean = marks.isNotEmpty()

    /** 页面底色切换后重绘全部笔迹，保证笔迹与底图同步反相。 */
    fun setPageLook(tint: Int) {
        if (pageTint == tint) return
        pageTint = tint
        rebuild()
    }

    /** 低内存时释放两张全屏位图，下次 onDraw 按需重建。 */
    fun releaseBuffers() {
        base?.recycle()
        live?.recycle()
        base = null
        baseC = null
        live = null
        liveC = null
        predictionVisible = false
        predictionBoundsValid = false
        invalidate()
    }
}
