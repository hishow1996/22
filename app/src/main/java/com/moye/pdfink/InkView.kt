package com.moye.pdfink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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

    private var host: Host? = null
    private var key = ""
    private val marks = ArrayList<Mark>()
    private var before: List<Mark> = emptyList()

    private var base: Bitmap? = null
    private var baseC: Canvas? = null
    private var live: Bitmap? = null
    private var liveC: Canvas? = null
    /** 预测墨迹只存一段线段，onDraw 直画，不再占一张全屏位图。 */
    private var predSeg: FloatArray? = null

    private var drawing = false
    private var erasing = false
    private var lassoing = false
    private var dragMode = 0 // 0 无 1 移动 2 缩放
    private var selection: Selection? = null

    private val livePts = ArrayList<Float>()
    private val liveWs = ArrayList<Float>()
    private val erasePts = ArrayList<Float>()
    private val lassoPts = ArrayList<Float>()
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

    /** v15：与题目底图共用同一 viewport，放大后两层像素级对齐。 */
    var viewport: Viewport? = null
    /** s>1 时切矢量重绘：位图缓冲不再被父 View 插值放大，笔迹也锐利。 */
    var hiResMode: Boolean = false
        set(v) {
            if (v != field) { field = v; invalidate() }
        }
    private var pageTint = InkPalette.WHITE
    private fun mapInk(argb: Int): Int = InkPalette.mapColor(argb, pageTint)

    private val unit: Float get() = min(width, height).toFloat().coerceAtLeast(1f)
    private val density: Float get() = resources.displayMetrics.density

    fun attach(h: Host, k: String) {
        host = h
        key = k
        marks.clear()
        marks.addAll(h.store.load(k))
        selection = null
        h.onSelectionChanged(false)
        cancelGesture()
        rebuild()
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

    private fun drawAllBase(c: Canvas) {
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
        predSeg = null
        liveDirty = null
        invalidate()
    }

    /** 局部重绘：橡皮抬手后只刷被扫过的矩形，不再重建整页位图。 */
    private fun rebuildRegion(dirty: FloatArray) {
        val c = baseC ?: return
        val r = GeometryCore.clampRect(dirty, width.toFloat(), height.toFloat())
        if (r[2] <= r[0] || r[3] <= r[1]) { rebuild(); return }
        clearRegion(c, r)
        c.save()
        c.clipRect(r[0], r[1], r[2], r[3])
        for (m in marks) {
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
            val lw = vp.lw.toFloat()
            val lh = vp.lh.toFloat()
            // bbox 粗筛用可见逻辑窗，绘制定位用 originX/originY
            val r = floatArrayOf(vp.visLeft, vp.visTop,
                vp.visLeft + vp.visW.coerceAtLeast(1),
                vp.visTop + vp.visH.coerceAtLeast(1))
            canvas.save()
            canvas.translate(-vp.originX, -vp.originY)
            // 按归一化坐标 × 放大后的逻辑画布直绘，不再经过位图缓冲插值。
            // bbox 粗筛：只画与可见窗相交的 marks，放大后也不会全量重绘。
            for (m in marks) {
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
            predSeg?.let { s ->
                scratch.reset()
                scratch.moveTo(s[0], s[1])
                scratch.lineTo(s[2], s[3])
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
        base?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        live?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val s = predSeg
        if (s != null) {
            scratch.reset()
            scratch.moveTo(s[0], s[1])
            scratch.lineTo(s[2], s[3])
            canvas.drawPath(scratch, predPaint)
        }
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
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (blocked(ev)) return false
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
        livePts.add(x); livePts.add(y); liveWs.add(1f)
        liveMark = Mark(h.inkColor, h.inkWidth, h.inkAlpha, liveShape,
            floatArrayOf(x, y), floatArrayOf(1f), liveLayer)
        drawing = true
        erasing = false
        live?.eraseColor(Color.TRANSPARENT)
        predSeg = null; liveDirty = null
        strokePaint.color = mapInk(Color.argb(h.inkAlpha, Color.red(h.inkColor),
            Color.green(h.inkColor), Color.blue(h.inkColor)))
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeWidth = h.inkWidth * unit
        liveC?.drawPoint(tailX, tailY, strokePaint)
        invalidate()
    }

    private fun addPoints(ev: MotionEvent) {
        val h = host ?: return
        val stylus = ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        fun take(x: Float, y: Float, t: Long, pressure: Float) {
            val nx = (x / width).coerceIn(0f, 1f)
            val ny = (y / height).coerceIn(0f, 1f)
            if (liveShape != Shapes.FREE) {
                if (livePts.size >= 4) {
                    livePts[2] = nx; livePts[3] = ny
                } else {
                    livePts.add(nx); livePts.add(ny); liveWs.add(1f)
                }
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
        liveMark = Mark(h.inkColor, h.inkWidth, h.inkAlpha, liveShape,
            livePts.toFloatArray(), liveWs.toFloatArray(), liveLayer)
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
        // 段 i 需要 P[i+1] 才能确定终点，因此只画到 n-2
        var i = nextSeg
        while (i <= n - 2) {
            val cx = livePts[2 * i] * width
            val cy = livePts[2 * i + 1] * height
            val ex = (livePts[2 * i] + livePts[2 * (i + 1)]) * 0.5f * width
            val ey = (livePts[2 * i + 1] + livePts[2 * (i + 1) + 1]) * 0.5f * height
            scratch.reset()
            scratch.moveTo(tailX, tailY)
            scratch.quadTo(cx, cy, ex, ey)
            strokePaint.strokeWidth = base *
                (liveWs.getOrElse(i - 1) { 1f } + liveWs.getOrElse(i) { 1f }) * 0.5f
            c.drawPath(scratch, strokePaint)
            tailX = ex; tailY = ey
            i++
        }
        nextSeg = n - 1
        invalidate()
    }

    private fun drawShapeLive(h: Host) {
        val c = liveC ?: return
        clearRegion(c, liveDirty)
        liveDirty = floatArrayOf(0f, 0f, width.toFloat(), height.toFloat())
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        liveMark?.let {
            InkGeometry.drawMark(c, it, width.toFloat(), height.toFloat(),
                strokePaint, scratch, textPaint, bgPaint, ::mapInk)
        }
        invalidate()
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
        predSeg = floatArrayOf(x1, y1, px, py)
        invalidate()
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
        val m = liveMark
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
        predSeg = null; liveDirty = null
        var changed = false
        if (m != null) {
            val tiny = m.shape != Shapes.FREE && m.shape != Shapes.NOTE && m.pts.size >= 4 &&
                hypot((m.pts[2] - m.pts[0]) * width, (m.pts[3] - m.pts[1]) * height) < 4f
            if (!tiny) { marks.add(m); changed = true }
        }
        if (changed) { drawAllBase(baseC ?: return); commit() }
        invalidate()
    }

    private fun cancelGesture() {
        drawing = false; erasing = false; lassoing = false; dragMode = 0
        liveMark = null
        live?.eraseColor(Color.TRANSPARENT)
        predSeg = null; liveDirty = null
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
        if (key.isNotEmpty()) h.store.saveNow(key, ArrayList(marks))
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
        predSeg = null
        invalidate()
    }
}
