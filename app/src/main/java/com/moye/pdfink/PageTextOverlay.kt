package com.moye.pdfink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.max

/**
 * 页面文字选区层：盖在页面上，只画选中高亮和两个把手，不接触摸事件
 * （触摸由 [ZoomHost] 统一判定：长按选词、拖把手、点空白取消）。
 *
 * 坐标：本 View 与页面容器同大（基准页宽 W×H）。当前缩放 scale、平移 origin
 * 来自 [Viewport]，与底图 / 笔迹同一套：屏幕局部 = 归一化 × (W·scale) − origin。
 * 选区计算一律在这个「屏幕像素 + origin」的空间里做，所以缩放后依然精确。
 */
class PageTextOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var viewport: Viewport? = null

    /** 已含用户旋转的文字层；null = 还没读到 / 本页没有文字。 */
    var layout: PageTextLayout? = null
        set(value) {
            field = value
            clear()
            clearSearch()
        }

    /** 第一次需要文字层时由 ZoomHost 调用，适配器在这里启动后台读取。 */
    var loader: (() -> Unit)? = null

    private var selStart = -1
    private var selEnd = -1

    // v44 全文搜索高亮：本页所有命中（[start, end) 为文字层下标），cur 是当前命中的序号
    private var searchRanges: List<IntArray> = emptyList()
    private var searchCur = -1

    /** 选区或搜索高亮存在时，视口变化都要重画。 */
    val needsRedraw: Boolean get() = hasSelection || searchRanges.isNotEmpty()

    fun setSearch(ranges: List<IntArray>, current: Int) {
        searchRanges = ranges
        searchCur = current
        invalidate()
    }

    fun clearSearch() {
        if (searchRanges.isEmpty() && searchCur == -1) return
        searchRanges = emptyList()
        searchCur = -1
        invalidate()
    }

    /**
     * 第 [index] 处搜索命中的外接矩形（归一化 0..1，左上原点，已含页面旋转），
     * 用来把它平移进可视区；没有文字层或下标越界返回 null。
     */
    fun searchHitNormalizedBounds(index: Int, out: FloatArray): Boolean {
        val l = layout ?: return false
        val r = searchRanges.getOrNull(index) ?: return false
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        var any = false
        for (i in r[0].coerceAtLeast(0) until r[1].coerceAtMost(l.length)) {
            if (!l.real[i]) continue
            x0 = minOf(x0, l.boxes[4 * i]); y0 = minOf(y0, l.boxes[4 * i + 1])
            x1 = maxOf(x1, l.boxes[4 * i + 2]); y1 = maxOf(y1, l.boxes[4 * i + 3])
            any = true
        }
        if (!any) return false
        out[0] = x0; out[1] = y0; out[2] = x1; out[3] = y1
        return true
    }


    val hasSelection: Boolean get() = selStart in 0 until selEnd

    private val density = resources.displayMetrics.density
    private val handleR = 9f * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(84, 41, 121, 255)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(41, 121, 255)
    }
    // 搜索：所有命中淡黄，当前这一处橙色加深（与 WPS 同样的两档）
    private val hitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 214, 10)
    }
    private val hitCurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 138, 0)
    }

    init {
        isClickable = false
        isFocusable = false
    }

    // ----- 坐标换算 -----

    private fun pageW(): Float = width * (viewport?.scale ?: 1f)
    private fun pageH(): Float = height * (viewport?.scale ?: 1f)
    private fun ox(): Float = viewport?.originX ?: 0f
    private fun oy(): Float = viewport?.originY ?: 0f

    private fun ready(): Boolean =
        layout != null && width > 0 && height > 0 && viewport != null

    // ----- 选区操作 -----

    /** 长按处选一个词。命中返回 true。lx/ly 是本 View 局部坐标。 */
    fun selectWordAt(lx: Float, ly: Float): Boolean {
        val l = layout ?: return false
        if (!ready()) return false
        val idx = TextSelectionMath.hitChar(
            l, lx + ox(), ly + oy(), pageW(), pageH(), 28f * density
        )
        if (idx < 0) return false
        val w = TextSelectionMath.wordAt(l, idx) ?: return false
        selStart = w[0]
        selEnd = w[1]
        invalidate()
        return true
    }

    fun selectAll() {
        val l = layout ?: return
        val t = TextSelectionMath.trim(l, 0, l.length) ?: return
        selStart = t[0]
        selEnd = t[1]
        invalidate()
    }

    fun clear() {
        if (selStart == -1 && selEnd == -1) return
        selStart = -1
        selEnd = -1
        invalidate()
    }

    fun selectedText(): String {
        val l = layout ?: return ""
        if (!hasSelection) return ""
        return l.text.substring(selStart, selEnd)
    }

    /**
     * 选区每一行的矩形，归一化到页面（x0,y0,x1,y1…），与笔迹同一坐标系，
     * 用来生成高亮 / 下划线 / 删除线；没有选区返回 null。
     */
    fun selectionRectsNormalized(): FloatArray? {
        val l = layout ?: return null
        if (!hasSelection) return null
        val r = TextSelectionMath.lineRects(l, selStart, selEnd, 1f, 1f)
        return if (r.isEmpty()) null else r
    }

    // ----- 把手 -----

    private fun anchor(handle: Int, out: FloatArray): Boolean {
        val l = layout ?: return false
        if (!hasSelection) return false
        val i = if (handle == HANDLE_START) selStart else selEnd - 1
        if (i !in 0 until l.length) return false
        val x = (if (handle == HANDLE_START) l.boxes[4 * i] else l.boxes[4 * i + 2]) * pageW() - ox()
        val y = l.boxes[4 * i + 3] * pageH() - oy()
        out[0] = x
        out[1] = y
        return true
    }

    /** 手指落在哪个把手上：0 无，1 起点，2 终点。 */
    fun hitHandle(lx: Float, ly: Float): Int {
        if (!hasSelection || !ready()) return 0
        val reach = max(handleR * 2.2f, 26f * density)
        val a = FloatArray(2)
        var best = 0
        var bestD = Float.MAX_VALUE
        for (h in intArrayOf(HANDLE_START, HANDLE_END)) {
            if (!anchor(h, a)) continue
            val cx = if (h == HANDLE_START) a[0] - handleR else a[0] + handleR
            val cy = a[1] + handleR
            val d = hypot(lx - cx, ly - cy)
            if (d <= reach && d < bestD) { bestD = d; best = h }
        }
        return best
    }

    /**
     * 开始拖把手：返回手指相对「该字所在行中线」的垂直偏移，之后每次移动
     * 把手指位置减掉它，就能认出手指指向的那一行，而不是把手下面那一行。
     */
    fun beginDrag(handle: Int, ly: Float): Float {
        val l = layout ?: return 0f
        val i = if (handle == HANDLE_START) selStart else selEnd - 1
        if (i !in 0 until l.length) return 0f
        val midY = (l.boxes[4 * i + 1] + l.boxes[4 * i + 3]) * 0.5f * pageH() - oy()
        return ly - midY
    }

    /** 拖把手到 (lx, ly)；yOffset 来自 [beginDrag]。 */
    fun dragTo(handle: Int, lx: Float, ly: Float, yOffset: Float) {
        val l = layout ?: return
        if (!hasSelection || !ready()) return
        val idx = TextSelectionMath.nearestChar(
            l, lx + ox(), ly - yOffset + oy(), pageW(), pageH()
        )
        if (idx < 0) return
        if (handle == HANDLE_START) {
            val s = TextSelectionMath.trim(l, idx, selEnd) ?: return
            selStart = s[0]
        } else {
            val e = TextSelectionMath.trim(l, selStart, idx + 1) ?: return
            selEnd = e[1]
        }
        invalidate()
    }

    /** 选区（含把手）在本 View 局部坐标里的外接矩形，给浮动菜单定位。 */
    fun selectionBounds(out: RectF): Boolean {
        val l = layout ?: return false
        if (!hasSelection || !ready()) return false
        val r = TextSelectionMath.lineRects(l, selStart, selEnd, pageW(), pageH())
        if (r.isEmpty()) return false
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        var k = 0
        while (k + 3 < r.size) {
            x0 = minOf(x0, r[k]); y0 = minOf(y0, r[k + 1])
            x1 = maxOf(x1, r[k + 2]); y1 = maxOf(y1, r[k + 3])
            k += 4
        }
        out.set(x0 - ox(), y0 - oy(), x1 - ox(), y1 - oy() + handleR * 2.4f)
        return true
    }

    // ----- 绘制 -----

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        if (!ready()) return
        if (searchRanges.isNotEmpty()) drawSearch(canvas, l)
        if (!hasSelection) return
        val rects = TextSelectionMath.lineRects(l, selStart, selEnd, pageW(), pageH())
        val dx = ox()
        val dy = oy()
        var k = 0
        while (k + 3 < rects.size) {
            canvas.drawRect(
                rects[k] - dx, rects[k + 1] - dy, rects[k + 2] - dx, rects[k + 3] - dy, fill
            )
            k += 4
        }
        val a = FloatArray(2)
        if (anchor(HANDLE_START, a)) drawHandle(canvas, a[0], a[1], true)
        if (anchor(HANDLE_END, a)) drawHandle(canvas, a[0], a[1], false)
    }

    private fun drawSearch(canvas: Canvas, l: PageTextLayout) {
        val dx = ox()
        val dy = oy()
        val w = pageW()
        val h = pageH()
        // 先画普通命中，再画当前命中，保证当前这一处不被相邻的盖住
        for (pass in 0..1) {
            for ((i, r) in searchRanges.withIndex()) {
                if ((i == searchCur) != (pass == 1)) continue
                val rects = TextSelectionMath.lineRects(l, r[0], r[1], w, h)
                val paint = if (pass == 1) hitCurPaint else hitPaint
                var k = 0
                while (k + 3 < rects.size) {
                    canvas.drawRect(
                        rects[k] - dx, rects[k + 1] - dy, rects[k + 2] - dx, rects[k + 3] - dy, paint
                    )
                    k += 4
                }
            }
        }
    }

    /** 水滴形把手：圆在锚点下方，尖角顶住锚点（左把手尖角朝右上，右把手朝左上）。 */
    private fun drawHandle(canvas: Canvas, ax: Float, ay: Float, left: Boolean) {
        val r = handleR
        val cx = if (left) ax - r else ax + r
        val cy = ay + r
        canvas.drawCircle(cx, cy, r, handlePaint)
        if (left) canvas.drawRect(cx, cy - r, cx + r, cy, handlePaint)
        else canvas.drawRect(cx - r, cy - r, cx, cy, handlePaint)
    }

    companion object {
        const val HANDLE_START = 1
        const val HANDLE_END = 2
    }
}
