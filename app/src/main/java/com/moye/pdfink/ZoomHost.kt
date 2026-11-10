package com.moye.pdfink

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 双指缩放 + 平移 + 双指横滑翻页。
 * 在 dispatch 层截获多指事件，因此不受子 View 的 disallowIntercept 影响。
 * 变换施加在 content 上，ViewGroup 会把触摸坐标逆变换给 InkView，笔迹自动跟随。
 *
 * v7：WIDTH 模式下页面比容器高，panExtraY > 0，scale=1 时也允许上下平移；
 * 抬手不再把 tx/ty 清零，改由 applyTransform 按边界钳制。单指仍照常写字，
 * 平移靠双指同向拖动（spreadDelta 小，只走 tx/ty 分支），零新手势、零冲突。
 */
class ZoomHost @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onZoomChange: ((Float) -> Unit)? = null
    var onFlip: ((Int) -> Unit)? = null
    var onPanChange: ((Float, Float) -> Unit)? = null

    /** 页面超出容器的像素（未缩放时），由 PagesAdapter 在 layout 后写入。 */
    @Volatile var panExtraX = 0f
    @Volatile var panExtraY = 0f

    /** 每页独有：题目底图与笔迹层读同一组 scale / pan，放大后像素级对齐。 */
    val viewport = Viewport()
    /** 变换数值变了就通知各层 invalidate。 */
    var onViewportChanged: (() -> Unit)? = null
    /** 倍率跨过 1× 时通知 InkView 切换矢量重绘模式。 */
    var onScaleCommit: ((Float) -> Unit)? = null
    /** 页面旋转过（低频）仍沿用旧的 View 变换，行为完全不变。 */
    var legacyViewScale = false
    /** 页面基准像素尺寸，由 PagesAdapter 在测量完成后写入。 */
    var baseContentW = 0
    var baseContentH = 0

    private var content: View? = null
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var startDist = 0f
    private var startMidX = 0f
    private var startMidY = 0f
    private var zooming = false
    private var flipDone = false
    private var dp70 = 0f
    private var dp16 = 0f

    override fun onFinishInflate() {
        super.onFinishInflate()
        content = findViewById(R.id.spreadRoot)
        val d = resources.displayMetrics.density
        dp70 = 70f * d
        dp16 = 16f * d
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val multi = ev.pointerCount >= 2
        if (multi) zooming = true
        if (zooming) {
            if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && ev.pointerCount == 2) {
                super.dispatchTouchEvent(ev)
            }
            handle(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                zooming = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun distance(ev: MotionEvent): Float = hypot(
        ev.getX(1) - ev.getX(0), ev.getY(1) - ev.getY(0)
    )

    private fun midX(ev: MotionEvent) = (ev.getX(0) + ev.getX(1)) * 0.5f
    private fun midY(ev: MotionEvent) = (ev.getY(0) + ev.getY(1)) * 0.5f

    private fun handle(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2) {
                    startDist = distance(ev).coerceAtLeast(1f)
                    startMidX = midX(ev)
                    startMidY = midY(ev)
                    flipDone = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount < 2 || startDist <= 0f) return
                val d = distance(ev).coerceAtLeast(1f)
                val mx = midX(ev)
                val my = midY(ev)
                val dx = mx - startMidX
                val dy = my - startMidY
                val spreadDelta = abs(d - startDist)
                if (!flipDone && abs(dx) > dp70 && spreadDelta < dp16 && abs(dx) > abs(dy)) {
                    flipDone = true
                    onFlip?.invoke(if (dx > 0f) -1 else 1)
                } else if (!flipDone) {
                    if (spreadDelta > 3f) {
                        scale = (scale * d / startDist).coerceIn(1f, 4f)
                    }
                    startDist = d
                    tx += dx
                    ty += dy
                    startMidX = mx
                    startMidY = my
                    applyTransform()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scale <= 1.01f) {
                    scale = 1f
                    // 不清零 tx/ty：WIDTH 模式的上下平移要保留，
                    // applyTransform 会按 panExtra 边界把它们钳回合法范围
                    applyTransform()
                }
                startDist = 0f
            }
        }
    }

    /**
     * 把 scale / tx / ty 换算成「逻辑画布 + 可见原点」，一次性写进 viewport。
     * 子 View 只照抄这组数，不各自推算；否则横屏 WIDTH 下页面比宿主高，
     * TiledPageView 按自身高度取块就会只画顶部几行。
     */
    private fun syncViewport(tx0: Float, ty0: Float) {
        val sc = TiledPageView.bucket(scale.coerceIn(1f, 4f))
        val bw = baseContentW.coerceAtLeast(1)
        val bh = baseContentH.coerceAtLeast(1)
        val lw = (bw * sc).toInt().coerceAtLeast(1)
        val lh = (bh * sc).toInt().coerceAtLeast(1)
        // 逻辑画布在宿主视口中居中，再叠加用户平移
        val lx = (width - lw) * 0.5f + tx0
        val ly = (height - lh) * 0.5f + ty0
        // 页面基准框在宿主中居中（gravity=center），子 View 局部 (0,0) 落在这里
        val ox = (width - bw) * 0.5f
        val oy = (height - bh) * 0.5f
        viewport.scale = scale
        viewport.bucket = sc
        viewport.lw = lw
        viewport.lh = lh
        viewport.originX = ox - lx
        viewport.originY = oy - ly
        // 宿主可见区 h∈[0,width] 对应逻辑 X∈[-lx,-lx+width]，与 ox/oy 无关
        viewport.visLeft = -lx
        viewport.visTop = -ly
        viewport.visW = width
        viewport.visH = height
    }

    private fun applyTransform() {
        val v = content ?: return
        // 内容超出量随 scale 放大；scale=1 且 panExtra=0 时上限为 0，等价原行为
        val limX = panExtraX.coerceAtLeast(0f) * scale * 0.5f +
            width * (scale - 1f).coerceAtLeast(0f) * 0.5f
        val limY = panExtraY.coerceAtLeast(0f) * scale * 0.5f +
            height * (scale - 1f).coerceAtLeast(0f) * 0.5f
        tx = tx.coerceIn(-limX, limX)
        ty = ty.coerceIn(-limY, limY)
        syncViewport(tx, ty)
        if (legacyViewScale) {
            // 旋转页：保持 v14 及以前的行为，不进新绘制链路
            v.scaleX = scale
            v.scaleY = scale
            v.translationX = tx
            v.translationY = ty
        } else {
            // v15：不再缩放 View，内容由 TiledPageView / InkView 按 viewport 自绘，
            // 否则底图与笔迹位图会被插值放大到 4× 而糊掉。
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationX = 0f
            v.translationY = 0f
        }
        onViewportChanged?.invoke()
        onScaleCommit?.invoke(scale)
        onZoomChange?.invoke(scale)
        onPanChange?.invoke(tx, ty)
    }

    /**
     * toTop：WIDTH 模式下「1:1 并回页顶」。内容比容器高时 gravity 居中，
     * ty = panExtraY/2（正值向下）正好把页面顶边推到容器顶边。
     */
    fun reset(silent: Boolean = false, toTop: Boolean = false) {
        scale = 1f
        tx = 0f
        ty = if (toTop) panExtraY.coerceAtLeast(0f) * 0.5f else 0f
        val v = content
        if (v != null) {
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationX = 0f
            v.translationY = if (legacyViewScale) ty else 0f
        }
        syncViewport(0f, ty)
        // 即使 silent 也要复位 viewport，否则复用的 holder 会残留上一次的倍率
        onViewportChanged?.invoke()
        onScaleCommit?.invoke(1f)
        if (!silent) {
            onZoomChange?.invoke(1f)
            onPanChange?.invoke(0f, ty)
        }
    }

    val zoomScale: Float get() = scale
    val panX: Float get() = tx
    val panY: Float get() = ty

    /** 会话恢复：还原倍率与平移量，平移归零时也不影响 FIT 模式。 */
    fun setScale(s: Float, px: Float = 0f, py: Float = 0f) {
        scale = s.coerceIn(1f, 4f)
        tx = px
        ty = py
        applyTransform()
    }

    /** panExtra 由外部写入后，按新边界重新钳一次，避免露出空白。 */
    fun refreshPanBounds() { applyTransform() }
}
