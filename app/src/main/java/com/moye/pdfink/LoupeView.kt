package com.moye.pdfink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * v48：局部放大书写窗。底部一条，显示原页上的一小块（放大），在里面写，
 * 笔迹按比例落回原页（作为一笔普通笔迹，进撤销历史）。
 * 底图用 renderTile 只渲窗口附近一块；笔迹用矢量按放大后的尺寸画，保持清晰。
 * 未编译，待 CI / 真机。
 */
class LoupeView(context: Context) : View(context) {

    interface Source {
        val marks: List<Mark>
        val tint: Int
        val visibleLayers: Set<Int>
        val inkColor: Int
        val inkWidth: Float
        val inkAlpha: Int
        val activeLayer: Int
        val stylusOnly: Boolean
        /** 在后台线程调用。lw×lh 是放大后的整页逻辑尺寸。 */
        fun renderTile(lw: Int, lh: Int, left: Int, top: Int, tw: Int, th: Int): Bitmap?
        fun onStroke(m: Mark)
        /** 窗口位置变了（用来记住 / 刷新按钮状态）。 */
        fun onWindowMoved() {}
        /** v52：倍数档变了（用来记住用户选的倍数）。 */
        fun onZoomChanged(frac: Float) {}
    }

    var source: Source? = null
    var scope: CoroutineScope? = null

    private var pageRatio = 0.7f
    var frac = LoupeMath.DEFAULT_FRAC
        private set
    var winX = 0.04f
        private set
    var winY = 0.04f
        private set
    private var rowStartX = 0.04f

    private var tile: Bitmap? = null
    private var tileLeft = 0f
    private var tileTop = 0f
    private var rendering = false
    private var renderAgain = false
    private var gen = 0

    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val miniPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guide = Paint().apply {
        color = 0x33000000; strokeWidth = 1f; style = Paint.Style.STROKE
    }

    // ----- 正在写的一笔（页上归一化坐标） -----
    private var live = ArrayList<Float>()
    private var activeId = -1
    private var miniDrag = false
    private var lastPx = 0f
    private var lastPy = 0f
    private var lastStylusAt = 0L

    private fun sw() = width.toFloat()
    private fun sh() = height.toFloat()
    private fun ww() = frac
    private fun wh() = LoupeMath.winH(frac, pageRatio, sw(), sh())
    private fun lw() = if (frac > 0f) sw() / frac else sw()
    private fun lh() = lw() / pageRatio

    /** 换页 / 换文档时调用。 */
    fun setPage(ratio: Float) {
        pageRatio = ratio.coerceIn(0.2f, 5f)
        gen++
        tile?.recycle(); tile = null
        live.clear()
        winX = LoupeMath.clampX(winX, ww())
        winY = LoupeMath.clampY(winY, wh())
        ensureTile()
        invalidate()
    }

    fun refresh() { invalidate() }

    fun release() {
        gen++
        tile?.recycle(); tile = null
        live.clear()
    }

    fun move(x: Float, y: Float, newRow: Boolean = true) {
        winX = LoupeMath.clampX(x, ww())
        winY = LoupeMath.clampY(y, wh())
        if (newRow) rowStartX = winX
        ensureTile()
        invalidate()
        source?.onWindowMoved()
    }

    fun shiftX(dir: Int) = move(winX + dir * ww() * LoupeMath.STEP, winY, false)
    fun shiftY(dir: Int) = move(winX, winY + dir * wh() * LoupeMath.STEP, false)
    fun newLine() {
        val (x, y) = LoupeMath.nextLine(rowStartX, winY, ww(), wh())
        winX = x; winY = y
        ensureTile(); invalidate(); source?.onWindowMoved()
    }

    /** v52：设置倍数（窗口宽占整页宽的比例），保持窗口中心不动。 */
    fun setFrac(f: Float) {
        val cx = winX + ww() / 2f
        val cy = winY + wh() / 2f
        frac = f.coerceIn(LoupeMath.MIN_FRAC, LoupeMath.MAX_FRAC)
        gen++
        tile?.recycle(); tile = null
        val (x, y) = LoupeMath.centerOn(cx, cy, ww(), wh())
        winX = x; winY = y; rowStartX = x
        ensureTile(); invalidate(); source?.onWindowMoved()
        source?.onZoomChanged(frac)
    }

    /** v52：2× → 3× → 4× 轮换，返回新倍数的文字。 */
    fun cycleZoom(): String {
        setFrac(LoupeMath.nextFrac(frac))
        return LoupeMath.zoomLabel(frac)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gen++
        tile?.recycle(); tile = null
        winX = LoupeMath.clampX(winX, ww())
        winY = LoupeMath.clampY(winY, wh())
        ensureTile()
    }

    // ----- 底图：窗口附近渲一块（四周各留半个窗口，小幅移动不用重渲） -----
    private fun ensureTile() {
        val s = source ?: return
        val sc = scope ?: return
        if (width <= 0 || height <= 0) return
        val lw = lw(); val lh = lh()
        val left = winX * lw
        val top = winY * lh
        val t = tile
        if (t != null && LoupeMath.covered(left, top, sw(), sh(),
                tileLeft, tileTop, t.width.toFloat(), t.height.toFloat())) return
        if (rendering) { renderAgain = true; return }
        rendering = true
        val myGen = gen
        val tl = max(0f, left - sw() / 2f)
        val tt = max(0f, top - sh() / 2f)
        val tw = min(lw - tl, sw() * 2f).toInt().coerceAtLeast(1)
        val th = min(lh - tt, sh() * 2f).toInt().coerceAtLeast(1)
        val lwi = lw.toInt(); val lhi = lh.toInt()
        sc.launch(Dispatchers.IO) {
            val bmp = try {
                s.renderTile(lwi, lhi, tl.toInt(), tt.toInt(), tw, th)
            } catch (e: Throwable) { null }
            withContext(Dispatchers.Main) {
                rendering = false
                if (myGen != gen || !isAttachedToWindow) {
                    bmp?.recycle()
                } else {
                    if (bmp != null) {
                        tile?.recycle()
                        tile = bmp; tileLeft = tl.toInt().toFloat(); tileTop = tt.toInt().toFloat()
                    }
                    invalidate()
                }
                if (renderAgain) { renderAgain = false; ensureTile() }
            }
        }
    }

    // ----- 绘制 -----
    override fun onDraw(c: Canvas) {
        val s = source
        c.drawColor(Color.WHITE)
        if (s == null || width <= 0) return
        val lw = lw(); val lh = lh()
        val left = winX * lw
        val top = winY * lh
        tile?.let { c.drawBitmap(it, tileLeft - left, tileTop - top, null) }

        c.save()
        c.translate(-left, -top)
        c.clipRect(left, top, left + sw(), top + sh())
        val vis = s.visibleLayers
        val tint = s.tint
        val tr: (Int) -> Int = { InkPalette.mapColor(it, tint) }
        for (m in s.marks) {
            if (m.layer !in vis) continue
            InkGeometry.drawMark(c, m, lw, lh, paint, path, tp, bg, tr)
        }
        if (live.size >= 2) {
            val lm = Mark(s.inkColor, s.inkWidth, s.inkAlpha, Shapes.FREE,
                live.toFloatArray(), FloatArray(live.size / 2) { 1f }, s.activeLayer)
            InkGeometry.drawMark(c, lm, lw, lh, paint, path, tp, bg, tr)
        }
        c.restore()

        // 右侧「该换窗口了」的提示线
        val gx = sw() * LoupeMath.ADVANCE_AT
        c.drawLine(gx, 0f, gx, sh(), guide)

        drawMini(c)
    }

    private fun miniRect(): RectF {
        val mw = 34f * dp
        val mh = mw / pageRatio
        val m = 6f * dp
        return RectF(sw() - m - mw, m, sw() - m, m + mh)
    }

    private fun drawMini(c: Canvas) {
        val r = miniRect()
        miniPaint.style = Paint.Style.FILL
        miniPaint.color = 0xE6FFFFFF.toInt()
        c.drawRect(r, miniPaint)
        miniPaint.style = Paint.Style.STROKE
        miniPaint.strokeWidth = 1f * dp
        miniPaint.color = 0x88000000.toInt()
        c.drawRect(r, miniPaint)
        miniPaint.style = Paint.Style.FILL
        miniPaint.color = 0x662F6FED
        c.drawRect(
            r.left + winX * r.width(), r.top + winY * r.height(),
            r.left + (winX + ww()) * r.width(), r.top + (winY + wh()) * r.height(),
            miniPaint)
    }

    private fun inMini(x: Float, y: Float): Boolean {
        val r = miniRect()
        val pad = 8f * dp
        return x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad
    }

    private fun miniMoveTo(x: Float, y: Float) {
        val r = miniRect()
        val nx = ((x - r.left) / r.width()).coerceIn(0f, 1f)
        val ny = ((y - r.top) / r.height()).coerceIn(0f, 1f)
        val (wx, wy) = LoupeMath.centerOn(nx, ny, ww(), wh())
        move(wx, wy, true)
    }

    // ----- 触摸 -----
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val s = source ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (inMini(ev.x, ev.y)) {
                    miniDrag = true
                    miniMoveTo(ev.x, ev.y)
                    return true
                }
                val isStylus = ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                if (isStylus) lastStylusAt = ev.eventTime
                // 只用触控笔时，手指落在条上什么都不做（也不穿透到页面）
                if (s.stylusOnly && !isStylus) return true
                // v52：防手掌——用过笔 1.5 秒内的手指、接触面过大（手掌 / 手侧）的触摸不出墨
                if (!isStylus && (LoupeMath.fingerIgnored(ev.eventTime, lastStylusAt) ||
                        LoupeMath.isPalm(ev.getTouchMajor(0), dp))) return true
                activeId = ev.getPointerId(0)
                live.clear()
                addPoint(ev.x, ev.y, true)
                invalidate()
                return true
            }
            // v52：写字时又有一根手指 / 手掌压上来：这一笔作废（避免混进一道乱线）
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (activeId >= 0 && !miniDrag) { live.clear(); activeId = -1; invalidate() }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (miniDrag) { miniMoveTo(ev.x, ev.y); return true }
                val idx = ev.findPointerIndex(activeId)
                if (idx < 0) return true
                if (!ev.getToolType(idx).let { it == MotionEvent.TOOL_TYPE_STYLUS } &&
                    LoupeMath.isPalm(ev.getTouchMajor(idx), dp)) {
                    live.clear(); activeId = -1; invalidate(); return true      // 写到一半手掌压实了
                }
                if (ev.getToolType(idx) == MotionEvent.TOOL_TYPE_STYLUS) lastStylusAt = ev.eventTime
                for (i in 0 until ev.historySize) {
                    addPoint(ev.getHistoricalX(idx, i), ev.getHistoricalY(idx, i), false)
                }
                addPoint(ev.getX(idx), ev.getY(idx), false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (miniDrag) { miniDrag = false; return true }
                val idx = ev.findPointerIndex(activeId)
                if (idx >= 0) addPoint(ev.getX(idx), ev.getY(idx), false)
                finishStroke(s)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                miniDrag = false
                live.clear(); activeId = -1
                invalidate()
                return true
            }
        }
        return true
    }

    private fun addPoint(px: Float, py: Float, first: Boolean) {
        if (!first && hypot(px - lastPx, py - lastPy) < 1.5f * dp) return
        lastPx = px; lastPy = py
        live.add(LoupeMath.toPageX(px, sw(), winX, ww()).coerceIn(0f, 1f))
        live.add(LoupeMath.toPageY(py, sh(), winY, wh()).coerceIn(0f, 1f))
    }

    private fun finishStroke(s: Source) {
        activeId = -1
        if (live.size >= 2) {
            val pts = live.toFloatArray()
            val n = pts.size / 2
            s.onStroke(Mark(s.inkColor, s.inkWidth, s.inkAlpha, Shapes.FREE,
                pts, FloatArray(n) { 1f }, s.activeLayer))
            val lastX = pts[pts.size - 2]
            live.clear()
            LoupeMath.advance(winX, winY, rowStartX, ww(), wh(), lastX)?.let { (nx, ny) ->
                // 换行时 rowStartX 不变；只是右移时也不改行起点
                winX = LoupeMath.clampX(nx, ww()); winY = LoupeMath.clampY(ny, wh())
                ensureTile(); source?.onWindowMoved()
            }
        }
        live.clear()
        invalidate()
    }
}
