package com.moye.pdfink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
    // 双指手势先判定是「平移」还是「缩放」再动手：0 未定 / 1 平移 / 2 缩放。
    // 以前两根手指间距每帧抖动超过 3px 就当缩放，拖着页面上下滑时会一边缩放一边抖。
    private var pinchMode = 0
    private var gestureStartDist = 0f
    private var panAccum = 0f
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
    /** 连续滚动：缩放整列。参数 = 目标倍率、焦点在列表里的 x / y。 */
    var onListZoomTo: ((Float, Float, Float) -> Unit)? = null
    /** 连续滚动：整列横向平移（所有页共用同一个 tx）。 */
    var onListPan: ((Float) -> Unit)? = null
    private var velocity: android.view.VelocityTracker? = null
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

    // ---- 连续滚动：一笔跨页 ----
    // 手势始终由起笔那一页（本 View）收到；笔移出本页时，把这一笔在页边切开：
    // 当前页补一个边缘点后抬笔，相邻页在边缘处落笔继续。所有坐标都用本 View 的局部坐标，
    // 邻页的位置靠两个列表项 top 的差换算。
    private var crossActive = false
    private var crossCur: ZoomHost? = null
    private var crossUp: ZoomHost? = null
    private var crossDown: ZoomHost? = null
    private var boundUp = Float.NEGATIVE_INFINITY
    private var boundDown = Float.POSITIVE_INFINITY
    private var crossGrouped = false
    private var crossPrevX = 0f
    private var crossPrevY = 0f
    private val crossProps = MotionEvent.PointerProperties()
    private val crossCoords = MotionEvent.PointerCoords()
    private var singleStartX = 0f
    private var singleStartY = 0f
    private var lastSingleX = 0f
    private var lastSingleY = 0f
    private var singlePanning = false
    // v26：竖屏 pager 为 VERTICAL 时，横向单指滑补一次离散翻页；
    // DOWN 后首次 MOVE 即切 0.5x 预览网格，不等 16dp 判定，起步即跟手。
    private var horizontalFlipDone = false
    private var gestureDeferred = false

    // ---- 页内选字（长按选词 / 拖把手 / 系统浮动菜单复制）----
    /** 本页（含双页时的右页）的选字层，由 PagesAdapter 绑定时设置。 */
    var textOverlays: List<PageTextOverlay> = emptyList()
    /** 本次触摸允许长按选字吗：浏览模式、或「仅手写笔」下的手指。 */
    var textGate: ((MotionEvent) -> Boolean)? = null
    var onTextCopied: ((String) -> Unit)? = null
    /** 文字层还在后台读取时长按，提示一下。 */
    var onTextNotReady: (() -> Unit)? = null
    private var selTarget: PageTextOverlay? = null
    private var selMode = SEL_NONE
    private var selHandle = 0
    private var selYOffset = 0f
    private var selDownX = 0f
    private var selDownY = 0f
    private var selActionMode: ActionMode? = null
    private val selLongPress = Runnable { fireTextLongPress() }
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
        // 连续滚动：列表项高度随整列倍率变化，纵向位移是由高度算出来的固定值，
        // 尺寸变了就重新夹一次。
        if (continuousScroll && w > 0 && h > 0) scheduleTransform()
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


    // ------------------------------------------------------------------
    // 页内选字
    // ------------------------------------------------------------------

    private fun overlayFrame(o: PageTextOverlay): PageFrame? = pageFrameFor(o.parent as? View)

    private fun cancelTextLongPress() {
        removeCallbacks(selLongPress)
        if (selMode == SEL_PENDING) selMode = SEL_NONE
    }

    /** 取消选区并收起系统菜单。 */
    fun clearTextSelection() {
        cancelTextLongPress()
        val t = selTarget
        selTarget = null
        selMode = SEL_NONE
        for (o in textOverlays) o.clear()
        t?.clear()
        val m = selActionMode
        selActionMode = null
        m?.finish()
    }

    /** 缩放 / 平移后：选区层重画，系统菜单跟着挪。 */
    fun onSelectionViewportChanged() {
        val t = selTarget ?: return
        t.invalidate()
        selActionMode?.invalidateContentRect()
    }

    /** 返回 true = 本次事件已被选字逻辑吃掉。 */
    private fun handleTextTouch(ev: MotionEvent): Boolean {
        val act = ev.actionMasked
        // 正在拖把手 / 长按后手指还没抬：吞掉整段手势
        if (selMode == SEL_DRAG || selMode == SEL_HOLD) {
            val t = selTarget
            val f = t?.let { overlayFrame(it) }
            if (ev.pointerCount == 1 && t != null && f != null &&
                (act == MotionEvent.ACTION_MOVE || act == MotionEvent.ACTION_UP)
            ) {
                val h = if (selMode == SEL_DRAG) selHandle else PageTextOverlay.HANDLE_END
                t.dragTo(h, ev.x - f.left, ev.y - f.top, if (selMode == SEL_DRAG) selYOffset else 0f)
            }
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                selMode = SEL_NONE
                parent?.requestDisallowInterceptTouchEvent(!browseMode)
                if (act == MotionEvent.ACTION_CANCEL) clearTextSelection() else showSelectionMenu()
            } else if (act == MotionEvent.ACTION_MOVE) {
                selActionMode?.invalidateContentRect()
            }
            return true
        }
        if (zooming || doubleTapActive) {
            cancelTextLongPress()
            return false
        }
        when (act) {
            MotionEvent.ACTION_DOWN -> {
                cancelTextLongPress()
                val t = selTarget
                if (t != null && t.hasSelection) {
                    val f = overlayFrame(t)
                    val hit = if (f != null) t.hitHandle(ev.x - f.left, ev.y - f.top) else 0
                    if (hit != 0 && f != null) {
                        selMode = SEL_DRAG
                        selHandle = hit
                        selYOffset = t.beginDrag(hit, ev.y - f.top)
                        selActionMode?.hide(ActionMode.DEFAULT_HIDE_DURATION.toLong())
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    clearTextSelection()   // 点到别处：取消选区，这次触摸照常处理
                }
                if (ev.pointerCount == 1 && textOverlays.isNotEmpty() &&
                    (textGate?.invoke(ev) ?: browseMode)
                ) {
                    selDownX = ev.x
                    selDownY = ev.y
                    selMode = SEL_PENDING
                    for (o in textOverlays) if (o.layout == null) o.loader?.invoke()
                    postDelayed(selLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> if (selMode == SEL_PENDING) {
                val slop = minOf(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), dp16 * 0.5f)
                if (ev.pointerCount > 1 ||
                    abs(ev.x - selDownX) > slop || abs(ev.y - selDownY) > slop
                ) cancelTextLongPress()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                cancelTextLongPress()
        }
        return false
    }

    private fun fireTextLongPress() {
        if (selMode != SEL_PENDING) return
        selMode = SEL_NONE
        var found: PageTextOverlay? = null
        var loading = false
        for (o in textOverlays) {
            val f = overlayFrame(o) ?: continue
            if (o.layout == null) { loading = true; continue }
            if (o.selectWordAt(selDownX - f.left, selDownY - f.top)) { found = o; break }
        }
        if (found == null) {
            if (loading) onTextNotReady?.invoke()
            return
        }
        for (o in textOverlays) if (o !== found) o.clear()
        selTarget = found
        selMode = SEL_HOLD
        // 浏览手势在 DOWN 后可能已进入「暂停高清补渲染」，这里收尾，否则页面会一直糊着
        if (gestureDeferred) {
            gestureDeferred = false
            onViewportGestureChanged?.invoke(false)
        }
        velocity?.recycle()
        velocity = null
        singlePanning = false
        val c = MotionEvent.obtain(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            MotionEvent.ACTION_CANCEL, selDownX, selDownY, 0
        )
        try { doubleTapDetector.onTouchEvent(c) } finally { c.recycle() }
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun showSelectionMenu() {
        val m = selActionMode
        if (m != null) {
            m.invalidateContentRect()
            m.hide(0)
            return
        }
        selActionMode = startActionMode(selMenuCallback, ActionMode.TYPE_FLOATING)
    }

    private val selMenuCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, SEL_ID_COPY, 0, context.getString(R.string.sel_copy))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, SEL_ID_ALL, 1, context.getString(R.string.sel_select_all))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val t = selTarget ?: return false
            when (item.itemId) {
                SEL_ID_COPY -> {
                    val text = t.selectedText()
                    if (text.isNotEmpty()) {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                            ?.setPrimaryClip(ClipData.newPlainText("text", text))
                        onTextCopied?.invoke(text)
                    }
                    mode.finish()
                }
                SEL_ID_ALL -> {
                    t.selectAll()
                    mode.invalidateContentRect()
                }
                else -> return false
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (selActionMode === mode) selActionMode = null
            selTarget?.clear()
            selTarget = null
            if (selMode != SEL_DRAG && selMode != SEL_HOLD) selMode = SEL_NONE
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
            val t = selTarget
            val f = t?.let { overlayFrame(it) }
            val b = RectF()
            if (t != null && f != null && t.selectionBounds(b)) {
                outRect.set(
                    (b.left + f.left).toInt(), (b.top + f.top).toInt(),
                    (b.right + f.left).toInt() + 1, (b.bottom + f.top).toInt() + 1
                )
            } else outRect.set(0, 0, 1, 1)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (textOverlays.isNotEmpty() && handleTextTouch(ev)) return true
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
            // 连续滚动且已放大：横向要平移整列，由本 View 接管全部单指拖动
            parent?.requestDisallowInterceptTouchEvent(
                !browseMode || (continuousScroll && scale > 1.01f)
            )
            if (browseMode && continuousScroll) {
                velocity?.recycle()
                velocity = android.view.VelocityTracker.obtain()
            }
            singleStartX = ev.x
            singleStartY = ev.y
            lastSingleX = ev.x
            lastSingleY = ev.y
            singlePanning = false
            horizontalFlipDone = false
            gestureDeferred = false
        }
        if (browseMode && continuousScroll && !zooming) {
            // 速度按屏幕坐标算：本 View 会随滚动移动，本地坐标会把速度吃掉
            val v = velocity
            if (v != null) {
                val c = MotionEvent.obtain(ev)
                c.setLocation(ev.rawX, ev.rawY)
                v.addMovement(c)
                c.recycle()
            }
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
                endCross()
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
                    val zoomedList = if (continuousScroll && scale > 1.01f)
                        parent as? androidx.recyclerview.widget.RecyclerView else null
                    if (zoomedList != null) {
                        // 连续滚动且已放大：横向拖动 = 整列平移，纵向拖动 = 滚动列表。
                        if (!gestureDeferred) {
                            gestureDeferred = true
                            onViewportGestureChanged?.invoke(true)
                        }
                        if (!singlePanning &&
                            (abs(ev.x - singleStartX) > dp16 * 0.5f ||
                                abs(ev.y - singleStartY) > dp16 * 0.5f)) {
                            singlePanning = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        if (singlePanning) {
                            val ddx = ev.x - lastSingleX
                            val py = (ev.y - lastSingleY).toInt()
                            if (ddx != 0f) onListPan?.invoke(ddx)
                            // 滚动带着本 View 一起移动，残差留在 lastSingleY，不更新它
                            if (py != 0) zoomedList.scrollBy(0, -py)
                            lastSingleX = ev.x
                        }
                        return true
                    }
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
                    if (continuousScroll && singlePanning &&
                        ev.actionMasked == MotionEvent.ACTION_UP) {
                        val vt = velocity
                        val rv = parent as? androidx.recyclerview.widget.RecyclerView
                        if (vt != null && rv != null) {
                            vt.computeCurrentVelocity(1000)
                            rv.fling(0, -vt.yVelocity.toInt())
                        }
                    }
                    velocity?.recycle()
                    velocity = null
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
            val handled = if (crossActive) sendCross(ev) else sendManual(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                endCross()
                manualInk = null
            }
            return handled
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val target = outsideFrameInk(ev.x, ev.y) ?: insideFrameCrossInk(ev)
            if (target != null) {
                manualInk = target.first
                manualDx = target.second
                manualDy = target.third
                val handled = sendManual(ev)
                if (!handled) manualInk = null
                else beginCross(target.first as? InkView, ev)
                return handled
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 连续滚动里，落在本页页框内的单指落笔：自由笔 / 橡皮改走手动转发，才能一笔跨页。 */
    private fun insideFrameCrossInk(ev: MotionEvent): Triple<View, Float, Float>? {
        if (!continuousScroll || ev.pointerCount != 1) return null
        val (ink, f) = primaryInkAndFrame() ?: return null
        if (!ink.canCrossPages()) return null
        if (ev.x < f.left || ev.x >= f.right || ev.y < f.top || ev.y >= f.bottom) return null
        return Triple(ink, f.left, f.top)
    }

    private fun primaryInkAndFrame(): Pair<InkView, PageFrame>? {
        val page = primaryPage as? android.view.ViewGroup ?: return null
        val f = pageFrameFor(page) ?: return null
        val ink = (0 until page.childCount).map { page.getChildAt(it) }
            .lastOrNull { it is InkView && it.visibility == View.VISIBLE } as? InkView
            ?: return null
        return ink to f
    }

    private fun usableNeighbor(of: ZoomHost, dir: Int): ZoomHost? {
        val rv = of.parent as? androidx.recyclerview.widget.RecyclerView ?: return null
        val pos = rv.getChildAdapterPosition(of)
        if (pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return null
        val n = rv.findViewHolderForAdapterPosition(pos + dir)?.itemView as? ZoomHost ?: return null
        if (!n.continuousScroll || n.browseMode || n.primaryInkAndFrame() == null) return null
        return n
    }

    private fun beginCross(ink: InkView?, ev: MotionEvent) {
        crossActive = continuousScroll && ink != null && ink.canCrossPages()
        crossGrouped = false
        crossPrevX = ev.x
        crossPrevY = ev.y
        crossCur = this
        if (crossActive) refreshCrossBounds()
    }

    private fun refreshCrossBounds() {
        val cur = crossCur ?: return
        crossUp = usableNeighbor(cur, -1)
        crossDown = usableNeighbor(cur, 1)
        boundUp = crossUp?.let { (it.bottom + cur.top) * 0.5f } ?: Float.NEGATIVE_INFINITY
        boundDown = crossDown?.let { (cur.bottom + it.top) * 0.5f } ?: Float.POSITIVE_INFINITY
    }

    private fun endCross() {
        if (crossGrouped) (manualInk as? InkView)?.groupEnd()
        crossGrouped = false
        crossActive = false
        crossCur = null
        crossUp = null
        crossDown = null
    }

    /** 本 View 局部纵坐标 y 落在当前接笔页的上方(-1) / 内(0) / 下方(1)。 */
    private fun crossDir(y: Float): Int {
        val yr = y + top
        return when {
            yr < boundUp -> -1
            yr >= boundDown -> 1
            else -> 0
        }
    }

    private fun lerpX(x0: Float, y0: Float, x1: Float, y1: Float, y: Float): Float {
        val dy = y1 - y0
        if (kotlin.math.abs(dy) < 0.001f) return x1
        return x0 + (x1 - x0) * ((y - y0) / dy)
    }

    /**
     * 用 ev 的第 idx 个采样（idx == historySize 表示当前点）做底，换成指定动作和位置，
     * 造一个单指事件。保留触控笔类型 / 压力 / 触摸面积；原始坐标仍是真实屏幕位置，
     * 这样顶边防误触、手掌判断照常生效。
     */
    private fun synthSample(ev: MotionEvent, idx: Int, action: Int, x: Float, y: Float): MotionEvent {
        val hs = ev.historySize
        ev.getPointerProperties(0, crossProps)
        val time: Long
        if (idx < hs) {
            ev.getHistoricalPointerCoords(0, idx, crossCoords)
            time = ev.getHistoricalEventTime(idx)
        } else {
            ev.getPointerCoords(0, crossCoords)
            time = ev.eventTime
        }
        val ox = ev.rawX - ev.x
        val oy = ev.rawY - ev.y
        crossCoords.x = x + ox
        crossCoords.y = y + oy
        val c = MotionEvent.obtain(
            if (action == MotionEvent.ACTION_DOWN) time else ev.downTime, time, action,
            1, arrayOf(crossProps), arrayOf(crossCoords),
            ev.metaState, ev.buttonState, ev.xPrecision, ev.yPrecision,
            ev.deviceId, ev.edgeFlags, ev.source, ev.flags
        )
        c.offsetLocation(-ox, -oy)
        return c
    }

    private fun sendSynth(ev: MotionEvent, idx: Int, action: Int, x: Float, y: Float): Boolean {
        val c = synthSample(ev, idx, action, x, y)
        return try { sendManual(c) } finally { c.recycle() }
    }

    /** 当前接笔页换成相邻页 dir（-1 上 / 1 下）：本页补边缘点并抬笔，邻页在边缘落笔。 */
    private fun crossTo(ev: MotionEvent, idx: Int, dir: Int, x: Float, y: Float): Boolean {
        val cur = crossCur ?: return false
        val next = (if (dir > 0) crossDown else crossUp) ?: return false
        val (nInk, nFrame) = next.primaryInkAndFrame() ?: return false
        val curEdge = (if (dir > 0) cur.bottom else cur.top) - top.toFloat()
        val nextEdge = (if (dir > 0) next.top else next.bottom) - top.toFloat()
        val exitX = lerpX(crossPrevX, crossPrevY, x, y, curEdge)
        val entryX = lerpX(crossPrevX, crossPrevY, x, y, nextEdge)
        if (!crossGrouped) {
            crossGrouped = true
            (manualInk as? InkView)?.groupBegin()
        }
        sendSynth(ev, idx, MotionEvent.ACTION_MOVE, exitX, curEdge)
        sendSynth(ev, idx, MotionEvent.ACTION_UP, exitX, curEdge)
        manualInk = nInk
        manualDx = nFrame.left + (next.left - left)
        manualDy = nFrame.top + (next.top - top)
        crossCur = next
        refreshCrossBounds()
        val ok = sendSynth(ev, idx, MotionEvent.ACTION_DOWN, entryX, nextEdge)
        crossPrevX = entryX
        crossPrevY = nextEdge
        return ok
    }

    /** 一笔跨页的事件分发：绝大多数事件仍整块转发（保留历史采样），只有越过页边的才逐点拆开。 */
    private fun sendCross(ev: MotionEvent): Boolean {
        val act = ev.actionMasked
        if (ev.pointerCount != 1 || act == MotionEvent.ACTION_CANCEL ||
            (act != MotionEvent.ACTION_MOVE && act != MotionEvent.ACTION_UP)
        ) return sendManual(ev)
        val hs = ev.historySize
        var inside = true
        for (i in 0..hs) {
            val y = if (i < hs) ev.getHistoricalY(i) else ev.y
            if (crossDir(y) != 0) { inside = false; break }
        }
        if (inside) {
            val handled = sendManual(ev)
            crossPrevX = ev.x
            crossPrevY = ev.y
            return handled
        }
        var handled = true
        for (i in 0..hs) {
            val x = if (i < hs) ev.getHistoricalX(i) else ev.x
            val y = if (i < hs) ev.getHistoricalY(i) else ev.y
            var guard = 0
            while (guard++ < 3) {
                val dir = crossDir(y)
                if (dir == 0 || !crossTo(ev, i, dir, x, y)) break
            }
            val action = if (i == hs) act else MotionEvent.ACTION_MOVE
            handled = sendSynth(ev, i, action, x, y)
            crossPrevX = x
            crossPrevY = y
        }
        return handled
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
                    pinchMode = 0
                    gestureStartDist = startDist
                    panAccum = 0f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount < 2 || startDist <= 0f) return
                val d = distance(ev).coerceAtLeast(1f)
                val mx = midX(ev)
                val my = midY(ev)
                val dx = mx - startMidX
                val dy = my - startMidY
                val spreadFrame = abs(d - startDist)
                // 判定本次手势是平移还是缩放：间距净变化（与平移无关）对比中点累计位移。
                // 平移时手指间距只会小幅抖动，缩放时间距变化远大于中点位移。
                if (pinchMode == 0) {
                    panAccum += abs(dx) + abs(dy)
                    val spreadNet = abs(d - gestureStartDist)
                    if (spreadNet > dp16 * 0.8f || panAccum > dp16 * 0.8f) {
                        pinchMode = if (spreadNet > panAccum) 2 else 1
                    }
                }
                val zoomNow = pinchMode == 2 && spreadFrame > 1f
                val list = if (continuousScroll)
                    parent as? androidx.recyclerview.widget.RecyclerView else null
                if (list != null) {
                    // 连续滚动：缩放 / 平移都作用于整列，不是单页
                    if (zoomNow) {
                        onListZoomTo?.invoke(
                            scale * d / startDist, mx + left, my + top
                        )
                        // 缩放会改列表项高度并重新定位，本地坐标整体变了：重置起点
                        startMidX = mx
                        startMidY = my
                    } else {
                        // 横向：整列共用一个 tx；纵向：滚动列表。滚动会带着本 View
                        // 一起移动，下一次事件的本地坐标已扣掉位移，startMidY 不用改。
                        if (dx != 0f) onListPan?.invoke(dx)
                        val px = dy.toInt()
                        if (px != 0) list.scrollBy(0, -px)
                        startMidX = mx
                    }
                    startDist = d
                } else if (!flipDone && pinchMode != 2 && panLimitX() <= 0.5f &&
                    abs(dx) > dp70 && spreadFrame < dp16 && abs(dx) > abs(dy)) {
                    flipDone = true
                    onFlip?.invoke(if (dx > 0f) -1 else 1)
                } else if (!flipDone) {
                    if (zoomNow) {
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
                pinchMode = 0
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
        if (continuousScroll) {
            // 连续滚动：列表项高度 = 页高 × 倍率，页面顶对齐。缩放以本 View 中心为原点，
            // 要让页顶落在 0，纵向位移必须是固定值（范围收成一个点，hasMovement=false）。
            val c = height * 0.5f
            val v = -(c + (bounds.top - c) * scale)
            return TranslationRange(v, v)
        }
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
        clipToListWindow(target, renderScale)
    }

    /**
     * 连续滚动放大后，本 View 高度 = 整页 × 倍率，远超屏幕。只把真正在列表可视范围内
     * （上下各多留 256px 预取）的那一段当作「可见区」，否则会把整页按放大倍率全部渲染。
     */
    private fun clipToListWindow(target: Viewport, renderScale: Float) {
        if (!continuousScroll || scale <= 1.01f || target.visH <= 0) return
        val rv = parent as? androidx.recyclerview.widget.RecyclerView ?: return
        if (rv.height <= 0) return
        val factor = renderScale / scale
        if (factor <= 0f || !factor.isFinite()) return
        val margin = 256f
        val localTop = maxOf(0f, -top - margin)
        val localBottom = minOf(height.toFloat(), rv.height - top + margin)
        if (localBottom <= localTop) {
            target.visH = 1
            return
        }
        target.visTop += localTop * factor
        target.visH = kotlin.math.ceil((localBottom - localTop) * factor).toInt().coerceAtLeast(1)
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
        if (continuousScroll && onListZoomTo != null) {
            val target = if (scale > 1.05f) 1f else 1.65f
            onListZoomTo?.invoke(target, focusX + left, focusY + top)
            return
        }
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

    /** 连续滚动：整列统一倍率与横向位移（纵向位移由页高算出，不用传）。 */
    fun setListView(s: Float, px: Float) {
        flushTransform()
        pendingResizeViewportState = null
        scale = s.coerceIn(1f, 4f)
        tx = px
        applyTransform()
        notifyScaleIfNeeded()
    }

    /** 列表滚动后，可视窗口变了，重算要渲染的区域并重绘。 */
    fun resyncViewport() {
        syncViewport(tx, ty)
        onViewportChanged?.invoke()
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


    /**
     * 页面上归一化坐标 (nx, ny) 的点此刻在本 View 里的位置（本 View 局部像素）。
     * 公式与 [ZoomTransform.pageViewportMapping] 的 displayedLeft / displayedTop 一致。
     */
    fun pagePointInHost(pageView: View?, nx: Float, ny: Float, out: FloatArray): Boolean {
        val f = pageFrameFor(pageView) ?: return false
        if (width <= 0 || height <= 0) return false
        val s = scale
        val left = width * 0.5f + (f.left - width * 0.5f) * s + tx
        val top = height * 0.5f + (f.top - height * 0.5f) * s + ty
        out[0] = left + nx * f.width * s
        out[1] = top + ny * f.height * s
        return true
    }

    /**
     * v44 搜索定位：把页面上的点平移进可视区。已经在可视区内（四周留一点边）就不动；
     * 不在就平移到横向居中、纵向约 40% 处。平移范围由 applyTransform 照常限位，
     * 页面没有可移动余量（整页都在屏幕里）时什么也不会发生。
     * 连续滚动的纵向位置由列表滚动决定，这里只处理横向。
     */
    fun revealPagePoint(pageView: View?, nx: Float, ny: Float) {
        val p = FloatArray(2)
        if (!pagePointInHost(pageView, nx, ny, p)) return
        flushTransform()
        if (!pagePointInHost(pageView, nx, ny, p)) return
        val mx = width * 0.12f
        val my = height * 0.16f
        var ntx = tx
        var nty = ty
        if (p[0] < mx || p[0] > width - mx) ntx = tx + (width * 0.5f - p[0])
        if (!continuousScroll && (p[1] < my || p[1] > height - my)) {
            nty = ty + (height * 0.4f - p[1])
        }
        if (ntx == tx && nty == ty) return
        tx = ntx
        ty = nty
        applyTransform()
        onViewportSettled?.invoke()
    }

    private companion object {
        const val SEL_NONE = 0
        const val SEL_PENDING = 1
        const val SEL_HOLD = 2
        const val SEL_DRAG = 3
        const val SEL_ID_COPY = 0x7f0f0001
        const val SEL_ID_ALL = 0x7f0f0002
    }
}
