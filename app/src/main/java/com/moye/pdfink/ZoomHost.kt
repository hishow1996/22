package com.moye.pdfink

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 双击智能放大 + 单指平移 + 双指缩放 / 平移 / 横滑翻页。
 * 在 dispatch 层截获多指事件，因此不受子 View 的 disallowIntercept 影响。
 * 变换施加在 content 上，ViewGroup 会把触摸坐标逆变换给 InkView，笔迹自动跟随。
 *
 * WIDTH 模式下页面比容器高，仍允许 1× 上下平移；
 * 平移范围根据当前显示页面组合的真实边界计算。
 */
class ZoomHost @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onZoomChange: ((Float) -> Unit)? = null
    var onFlip: ((Int) -> Unit)? = null
    var onPanChange: ((Float, Float) -> Unit)? = null
    /** 视口改变后在手势结束或程序化变更完成时保存一次。 */
    var onViewportSettled: (() -> Unit)? = null
    /** 缩放 / 浏览拖动期间暂停高清图块补渲染，结束后再恢复。 */
    var onViewportGestureChanged: ((Boolean) -> Unit)? = null

    /** 主页面底图与笔迹层共用；右页、草稿页各自维护页面局部几何。 */
    val viewport = Viewport()
    val secondViewport = Viewport()
    val draftViewport = Viewport()
    /** 变换数值变了就通知各层 invalidate。 */
    var onViewportChanged: (() -> Unit)? = null
    /** 倍率偏离 1× 时通知 InkView 切换矢量重绘模式。 */
    var onScaleCommit: ((Float) -> Unit)? = null
    /** 页面旋转过（低频）仍沿用旧的 View 变换，行为完全不变。 */
    var legacyViewScale = false
    /** 页面基准像素尺寸，由 PagesAdapter 在测量完成后写入。 */
    var baseContentW = 0
    var baseContentH = 0

    private var content: View? = null
    private var primaryPage: View? = null
    private var secondaryPage: View? = null
    private var draftPage: View? = null
    private var scale = 1f
    private var lastNotifiedScale = Float.NaN
    private var tx = 0f
    private var ty = 0f
    private var startDist = 0f
    private var startMidX = 0f
    private var startMidY = 0f
    private var zooming = false
    private var flipDone = false
    private var dp70 = 0f
    private var dp16 = 0f
    private var doubleTapActive = false
    // 在整页模式暂时没有可移动范围时保留上次的阅读位置比例。
    private var rememberedPanX = 0f
    private var rememberedPanY = 0f
    /** Snapshot captured before a resize can clamp the old pixel translation. */
    private var pendingResizeViewportState: PageViewportState? = null

    private val doubleTapDetector by lazy {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                doubleTapActive = true
                parent?.requestDisallowInterceptTouchEvent(true)
                toggleSmartZoom(e.x, e.y)
                return true
            }
        }).apply { setIsLongpressEnabled(false) }
    }

    /**
     * 连续滚动（竖屏铺满屏幕）：本 View 就是列表里的一页，高度等于页高。
     * 1× 时双指上下拖动改为滚动外层列表；不允许缩到 1× 以下；不做双指横滑翻页。
     */
    var continuousScroll = false
    private val lowestScale: Float
        get() = if (continuousScroll) 1f else ZoomTransform.MIN_SCALE

    /** 由 PagesAdapter 按 state.browse 下发；浏览模式需自己消费 DOWN。 */
    var browseMode = false
    /**
     * 放大后页面会铺到原本页框之外（上下空白区），系统按子 View 原始边界做命中，
     * 那里的触摸到不了笔迹层。DOWN 落在页框外时由这里手动转发给最近的页面笔迹层，
     * 本次手势的后续事件也一直转发给它。
     */
    private var manualInk: View? = null
    private var manualDx = 0f
    private var manualDy = 0f
    private var singleStartX = 0f
    private var singleStartY = 0f
    private var lastSingleX = 0f
    private var lastSingleY = 0f
    private var singlePanning = false
    // v26：竖屏 pager 为 VERTICAL 时，横向单指滑补一次离散翻页；
    // DOWN 后首次 MOVE 即切 0.5x 预览网格，不等 16dp 判定，起步即跟手。
    private var horizontalFlipDone = false
    private var gestureDeferred = false
    private var transformFramePosted = false
    private var transformDirty = false
    private val transformFrame = Runnable {
        transformFramePosted = false
        if (transformDirty) {
            transformDirty = false
            applyTransform()
        }
    }

    private fun scheduleTransform() {
        transformDirty = true
        if (!transformFramePosted) {
            transformFramePosted = true
            postOnAnimation(transformFrame)
        }
    }

    private fun flushTransform() {
        if (transformFramePosted) removeCallbacks(transformFrame)
        transformFramePosted = false
        if (transformDirty) {
            transformDirty = false
            applyTransform()
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        content = findViewById(R.id.spreadRoot)
        primaryPage = findViewById(R.id.pageContainer)
        secondaryPage = findViewById(R.id.secondContainer)
        draftPage = findViewById(R.id.draftContainer)
        val d = resources.displayMetrics.density
        dp70 = 70f * d
        dp16 = 16f * d
    }

    /**
     * Capture normalized pan before new bounds can clamp the old pixel translation.
     * PagesAdapter restores this snapshot after PageContainer and ZoomHost are both laid out.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw <= 0 || oldh <= 0 || w <= 0 || h <= 0) return
        if (w == oldw && h == oldh) return
        if (baseContentW <= 0 || baseContentH <= 0) return
        pendingResizeViewportState = pageViewportState()
        // Refresh the visible window without clamping against half-updated host/page bounds.
        syncViewport(tx, ty)
        onViewportChanged?.invoke()
    }

    fun takePendingResizeViewportState(): PageViewportState? {
        val saved = pendingResizeViewportState
        pendingResizeViewportState = null
        return saved
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (browseMode) doubleTapDetector.onTouchEvent(ev)
        if (doubleTapActive) {
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                doubleTapActive = false
                notifyScaleIfNeeded(force = true)
                onViewportSettled?.invoke()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // 做题模式：写字时禁止 ViewPager2 抢事件。
            // 浏览模式把拦截权还给它：竖屏 VERTICAL 才能拦到纵向 MOVE
            // 做跨页滚动，横向则不被拦截、由下面补一次离散翻页。
            parent?.requestDisallowInterceptTouchEvent(!browseMode)
            singleStartX = ev.x
            singleStartY = ev.y
            lastSingleX = ev.x
            lastSingleY = ev.y
            singlePanning = false
            horizontalFlipDone = false
            gestureDeferred = false
        }
        val multi = ev.pointerCount >= 2
        if (multi && !zooming) {
            flushTransform()
            onViewportGestureChanged?.invoke(true)
            // 多指开始才临时锁父级：双指横滑翻页不会被 ViewPager2 抢走
            parent?.requestDisallowInterceptTouchEvent(true)
            if (!browseMode) {
                // 第一根手指已在 InkView 里起了一笔。多指期间所有 MOVE/UP
                // 都被这里吞掉，若不补发 CANCEL，那一笔会永远残留在 live 缓冲。
                val c = MotionEvent.obtain(ev.downTime, ev.eventTime,
                    MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                try {
                    if (manualInk != null) sendManual(c) else super.dispatchTouchEvent(c)
                } finally { c.recycle() }
                manualInk = null
            }
            zooming = true
        }
        if (zooming) {
            handle(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                flushTransform()
                zooming = false
                startDist = 0f
                parent?.requestDisallowInterceptTouchEvent(!browseMode)
                onViewportGestureChanged?.invoke(false)
                notifyScaleIfNeeded(force = true)
                onViewportSettled?.invoke()
            }
            return true
        }
        if (browseMode) {
            // 关键：浏览模式必须自己消费 DOWN。否则 InkView 已 return false，
            // 整棵树无人消费 DOWN，系统会停止派发后续 MOVE/POINTER_DOWN/UP，
            // 双指缩放、上下平移、横滑翻页会「一起失效」。
            // v26：竖屏 pager 为 VERTICAL 时会自行拦截纵向 MOVE，本 View 收到
            // ACTION_CANCEL 后走 UP 分支收尾，跨页滚动交给系统 RecyclerView。
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - lastSingleX
                    val dy = ev.y - lastSingleY
                    val dxAbs = abs(ev.x - singleStartX)
                    val dyAbs = abs(ev.y - singleStartY)
                    // 竖屏完整页适配时 panLimitY≈0，纵向交给 pager；只有页面自身
                    // 纵向超出（WIDTH）或放大后才在这里做页内平移。
                    val canPan = panLimitY() > 0.5f
                    val zoomed = scale > 1.01f

                    // v26 ② 预览全程跟手：DOWN 后第一次 MOVE 就切 0.5x
                    // 预览网格，不再等 16dp 判定；起步即跟手，抬手再补高清。
                    if (!gestureDeferred) {
                        gestureDeferred = true
                        onViewportGestureChanged?.invoke(true)
                    }

                    if (!singlePanning && (
                            GestureArbiter.isVerticalPan(dxAbs, dyAbs, dp16, canPan) ||
                            GestureArbiter.isZoomedPan(dxAbs, dyAbs, dp16, zoomed))) {
                        singlePanning = true
                        // 放大后横向翻页已禁用，锁住父级避免 ViewPager2 抢
                        parent?.requestDisallowInterceptTouchEvent(true)
                        // v26 ① 死区补偿：判定阈值内累积的位移一次性补回，
                        // 否则起步那 16dp 白丢，连续 ty/tx 就不是 1:1 跟手。
                        ty += ev.y - singleStartY
                        if (zoomed) tx += ev.x - singleStartX
                        scheduleTransform()
                    } else if (singlePanning) {
                        ty += dy
                        if (zoomed) tx += dx   // 放大后横向拖动 = 平移，不是翻页
                        scheduleTransform()
                    } else if (!horizontalFlipDone &&
                            GestureArbiter.isHorizontalFlip(dxAbs, dyAbs, dp16, !zoomed)) {
                        // v26：竖屏 VERTICAL 后横向不再触发 pager 滚动，
                        // 补一次离散翻页保住「左右滑翻页」。纵向不被抢，
                        // 系统侧滑返回仍走已让出的屏幕边缘。
                        horizontalFlipDone = true
                        onFlip?.invoke(if (ev.x - singleStartX > 0f) -1 else 1)
                    }
                    lastSingleX = ev.x
                    lastSingleY = ev.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (singlePanning) {
                        flushTransform()
                        singlePanning = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        onViewportGestureChanged?.invoke(false)
                        onViewportSettled?.invoke()
                    } else if (gestureDeferred) {
                        // CANCEL：纵向被 pager 接走 / 单纯抬手，都要补回高清。
                        onViewportGestureChanged?.invoke(false)
                    }
                    gestureDeferred = false
                }
            }
            return true
        }
        if (manualInk != null) {
            val handled = sendManual(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL) manualInk = null
            return handled
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val target = outsideFrameInk(ev.x, ev.y)
            if (target != null) {
                manualInk = target.first
                manualDx = target.second
                manualDy = target.third
                val handled = sendManual(ev)
                if (!handled) manualInk = null
                return handled
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun sendManual(ev: MotionEvent): Boolean {
        val v = manualInk ?: return false
        val c = MotionEvent.obtain(ev)
        c.offsetLocation(-manualDx, -manualDy)
        return try { v.dispatchTouchEvent(c) } finally { c.recycle() }
    }

    /**
     * DOWN 点不在任何页框内、但落在放大后显示出的页面范围内时，
     * 返回应接收该手势的笔迹层及「宿主坐标 → 笔迹层本地坐标」的偏移。
     */
    private fun outsideFrameInk(x: Float, y: Float): Triple<View, Float, Float>? {
        if (legacyViewScale || scale <= 1.001f || width <= 0 || height <= 0) return null
        val pages = listOf(primaryPage, secondaryPage, draftPage)
        var best: Triple<View, Float, Float>? = null
        var bestGap = Float.MAX_VALUE
        for (page in pages) {
            val f = pageFrameFor(page) ?: continue
            // 点在原始页框内：系统自己会命中，无需手动转发
            if (x >= f.left && x < f.left + f.width && y >= f.top && y < f.top + f.height) {
                return null
            }
            val cx = width * 0.5f
            val cy = height * 0.5f
            val dl = cx + (f.left - cx) * scale + tx
            val dt = cy + (f.top - cy) * scale + ty
            val dr = dl + f.width * scale
            val db = dt + f.height * scale
            if (x < dl || x >= dr || y < dt || y >= db) continue
            val ink = (page as? android.view.ViewGroup)?.let { g ->
                (0 until g.childCount).map { g.getChildAt(it) }
                    .lastOrNull { it is InkView && it.visibility == View.VISIBLE }
            } ?: continue
            val gap = maxOf(dl - x, x - dr, 0f)
            if (gap < bestGap) {
                bestGap = gap
                best = Triple(ink, f.left, f.top)
            }
        }
        return best
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
                // 只有页面在当前视口中确有横向溢出时，双指横滑才用于页内平移；
                // 页面横向没有超出时仍允许横滑翻页。
                val list = if (continuousScroll && scale <= 1.01f && spreadDelta <= 3f)
                    parent as? androidx.recyclerview.widget.RecyclerView else null
                if (!flipDone && !continuousScroll && panLimitX() <= 0.5f && abs(dx) > dp70 &&
                    spreadDelta < dp16 && abs(dx) > abs(dy)) {
                    flipDone = true
                    onFlip?.invoke(if (dx > 0f) -1 else 1)
                } else if (!flipDone && list != null) {
                    // 连续滚动：双指拖动 = 滚动整个列表。滚动会带着本 View 一起移动，
                    // 下一次事件的本地坐标已经扣掉了位移，所以起点 startMidY 不用改。
                    val px = dy.toInt()
                    if (px != 0) list.scrollBy(0, -px)
                    startDist = d
                    startMidX = mx
                } else if (!flipDone) {
                    if (spreadDelta > 3f) {
                        scale = (scale * d / startDist).coerceIn(lowestScale, 4f)
                    }
                    startDist = d
                    tx += dx
                    ty += dy
                    startMidX = mx
                    startMidY = my
                    scheduleTransform()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scale <= lowestScale + 0.0001f) {
                    scale = lowestScale
                    // 只有到达最小倍率才钳位；小于 1× 的倍率必须保留。
                    scheduleTransform()
                }
                startDist = 0f
            }
        }
    }

    /** Return a visible child's measured frame in ZoomHost coordinates. */
    fun pageFrameFor(view: View?): PageFrame? {
        if (view == null || view.visibility != View.VISIBLE ||
            view.width <= 0 || view.height <= 0
        ) return null
        val root = content ?: return null
        return PageFrame(
            root.left + root.translationX + view.left + view.translationX,
            root.top + root.translationY + view.top + view.translationY,
            view.width,
            view.height
        )
    }

    /** Bounding rectangle of all visible page surfaces in the current spread. */
    private fun contentBounds(): android.graphics.RectF? {
        val frames = listOfNotNull(
            pageFrameFor(primaryPage),
            pageFrameFor(secondaryPage),
            pageFrameFor(draftPage)
        )
        if (frames.isNotEmpty()) {
            return android.graphics.RectF(
                frames.minOf { it.left },
                frames.minOf { it.top },
                frames.maxOf { it.right },
                frames.maxOf { it.bottom }
            )
        }
        if (baseContentW <= 0 || baseContentH <= 0) return null
        val left = (width - baseContentW) * 0.5f
        val top = (height - baseContentH) * 0.5f
        return android.graphics.RectF(
            left, top, left + baseContentW, top + baseContentH
        )
    }

    private fun translationRangeX(): TranslationRange {
        val bounds = contentBounds() ?: return TranslationRange()
        return ZoomTransform.translationRange(width, bounds.left, bounds.right, scale)
    }

    private fun translationRangeY(): TranslationRange {
        val bounds = contentBounds() ?: return TranslationRange()
        return ZoomTransform.translationRange(height, bounds.top, bounds.bottom, scale)
    }

    private fun panLimitX(): Float = translationRangeX().maxAbs
    private fun panLimitY(): Float = translationRangeY().maxAbs

    private fun updatePageViewport(target: Viewport, view: View?, tx0: Float, ty0: Float) {
        val frame = pageFrameFor(view)
        if (frame == null || width <= 0 || height <= 0) {
            target.scale = scale
            target.bucket = TiledPageView.bucket(scale)
            target.lw = 0
            target.lh = 0
            target.originX = 0f
            target.originY = 0f
            target.visLeft = 0f
            target.visTop = 0f
            target.visW = 0
            target.visH = 0
            return
        }

        val renderScale = TiledPageView.bucket(scale)
        val mapping = ZoomTransform.pageViewportMapping(
            width, height, frame, scale, renderScale, tx0, ty0
        )
        target.scale = scale
        target.bucket = renderScale
        target.lw = mapping.logicalWidth
        target.lh = mapping.logicalHeight
        target.originX = mapping.originX
        target.originY = mapping.originY
        target.visLeft = mapping.visibleLeft
        target.visTop = mapping.visibleTop
        target.visW = mapping.visibleWidth
        target.visH = mapping.visibleHeight
    }

    private fun syncViewport(tx0: Float, ty0: Float) {
        updatePageViewport(viewport, primaryPage, tx0, ty0)
        updatePageViewport(secondViewport, secondaryPage, tx0, ty0)
        updatePageViewport(draftViewport, draftPage, tx0, ty0)
    }

    private fun applyTransform() {
        val v = content ?: return
        // 以当前可见页面组合的整体边界限位，双页时不再只参考左页尺寸。
        val rangeX = translationRangeX()
        val rangeY = translationRangeY()
        tx = rangeX.clamp(tx)
        ty = rangeY.clamp(ty)
        if (rangeX.hasMovement) rememberedPanX = rangeX.fraction(tx)
        if (rangeY.hasMovement) rememberedPanY = rangeY.fraction(ty)
        syncViewport(tx, ty)
        if (legacyViewScale) {
            // 保留旧式变换兼容入口；常规 PDF 页面已统一走自绘 viewport。
            v.scaleX = scale
            v.scaleY = scale
            v.translationX = tx
            v.translationY = ty
        } else {
            // 页面底图和笔迹层都通过同一 viewport 变换，缩放倍率连续且可逆。
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationX = 0f
            v.translationY = 0f
        }
        onViewportChanged?.invoke()
        notifyScaleIfNeeded()
        onPanChange?.invoke(tx, ty)
    }

    private fun notifyScaleIfNeeded(force: Boolean = false) {
        val crossedOne = !lastNotifiedScale.isNaN() &&
            ((lastNotifiedScale < 1f) != (scale < 1f))
        if (!force && !lastNotifiedScale.isNaN() &&
            abs(scale - lastNotifiedScale) < 0.01f && !crossedOne
        ) return
        lastNotifiedScale = scale
        onScaleCommit?.invoke(scale)
        onZoomChange?.invoke(scale)
    }

    /**
     * toTop：WIDTH 模式下「1:1 并回页顶」。按当前页面组合的有效边界
     * 将内容顶边对齐到阅读视口顶端。
     */
    fun reset(silent: Boolean = false, toTop: Boolean = false) {
        flushTransform()
        pendingResizeViewportState = null
        scale = 1f
        tx = 0f
        val initialYRange = translationRangeY()
        ty = if (toTop && initialYRange.hasMovement) initialYRange.maximum else 0f
        rememberedPanX = 0f
        rememberedPanY = if (toTop && initialYRange.hasMovement) {
            initialYRange.fraction(ty)
        } else 0f
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
        notifyScaleIfNeeded(force = true)
        if (!silent) onPanChange?.invoke(0f, ty)
        onViewportSettled?.invoke()
    }

    val zoomScale: Float get() = scale
    val panX: Float get() = tx
    val panY: Float get() = ty

    /**
     * 双击切换阅读倍率。缩放以双击点为锚点，尽量让目标文字留在原位置。
     */
    fun toggleSmartZoom(focusX: Float, focusY: Float) {
        flushTransform()
        if (kotlin.math.abs(scale - 1f) > 0.05f) {
            reset(silent = false, toTop = translationRangeY().hasMovement)
        } else {
            zoomTo(1.65f, focusX, focusY)
        }
        notifyScaleIfNeeded(force = true)
        onViewportSettled?.invoke()
    }

    private fun zoomTo(nextScale: Float, focusX: Float, focusY: Float) {
        val next = nextScale.coerceIn(ZoomTransform.MIN_SCALE, 4f)
        val old = scale.coerceAtLeast(ZoomTransform.MIN_SCALE)
        if (kotlin.math.abs(next - old) < 0.001f) return
        tx = ZoomTransform.anchoredTranslation(old, next, width.toFloat(), focusX, tx)
        ty = ZoomTransform.anchoredTranslation(old, next, height.toFloat(), focusY, ty)
        scale = next
        applyTransform()
    }

    /** 按可移动范围的比例保存位置，适应栏显隐、旋转和不同屏幕尺寸。 */
    fun pageViewportState(): PageViewportState = PageViewportState(
        zoom = scale,
        panX = rememberedPanX,
        panY = rememberedPanY
    ).normalized()

    fun setPageViewportState(saved: PageViewportState) {
        flushTransform()
        pendingResizeViewportState = null
        val state = saved.normalized()
        scale = state.zoom
        rememberedPanX = state.panX
        rememberedPanY = state.panY
        tx = translationRangeX().offset(rememberedPanX)
        ty = translationRangeY().offset(rememberedPanY)
        applyTransform()
        notifyScaleIfNeeded(force = true)
        onViewportSettled?.invoke()
    }

    /** 会话恢复：还原倍率与旧版像素平移量，兼容 v17 及更早版本。 */
    fun setScale(s: Float, px: Float = 0f, py: Float = 0f) {
        flushTransform()
        pendingResizeViewportState = null
        scale = s.coerceIn(ZoomTransform.MIN_SCALE, 4f)
        tx = px
        ty = py
        applyTransform()
        notifyScaleIfNeeded(force = true)
        onViewportSettled?.invoke()
    }

    /**
     * Child frames can change independently (for example, only the right page
     * receives its real cached ratio). Restore normalized pan against new bounds.
     */
    fun refreshPageGeometry() {
        flushTransform()
        tx = translationRangeX().offset(rememberedPanX)
        ty = translationRangeY().offset(rememberedPanY)
        applyTransform()
        onViewportSettled?.invoke()
    }

    fun refreshPanBounds() { refreshPageGeometry() }
}
