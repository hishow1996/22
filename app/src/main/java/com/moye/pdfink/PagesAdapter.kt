package com.moye.pdfink

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job

class PagesAdapter(
    private val repo: PageSource,
    private val host: InkView.Host,
    private val state: UiState,
    private val scope: CoroutineScope,
    private val onInkBound: (String, InkView) -> Unit,
    private val onInkRecycled: (String) -> Unit,
    private val onZoomChange: (Float) -> Unit,
    private val onFlip: (Int) -> Unit
) : RecyclerView.Adapter<PagesAdapter.VH>() {

    private val holders = HashMap<Int, VH>()
    /** Coalesce viewport refreshes until the window has completed its next layout pass. */
    private var hostBoundsRefreshPending = false

    /** 竖屏「铺满屏幕」的连续滚动：每页按页宽比例定高，一页接一页往下排。 */
    var continuous = false
        set(value) {
            if (field == value) return
            field = value
            listZoom = 1f
            listTx = 0f
        }

    /** 连续滚动的整列倍率与横向位移：所有页共用，捏合 / 双击 / 拖动都改这两个值。 */
    var listZoom = 1f
        private set
    private var listTx = 0f
    private var recycler: RecyclerView? = null
    private var lastRatio = ContinuousMath.DEFAULT_RATIO

    /** 连续滚动时屏幕上同时有好几页，全部按「当前页」优先级渲染。 */
    private fun isActive(position: Int) = continuous || position == activePosition

    /** 列表滚动后，放大状态下每页的「可视窗口」跟着变，重算要渲染的区域。 */
    private val scrollSync = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (continuous && listZoom > 1.01f) {
                for (h in holders.values) h.zoom.resyncViewport()
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recycler = recyclerView
        recyclerView.addOnScrollListener(scrollSync)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnScrollListener(scrollSync)
        if (recycler === recyclerView) recycler = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private fun sideInset(rv: RecyclerView) =
        rv.resources.getDimensionPixelSize(R.dimen.pdf_page_horizontal_inset)

    /**
     * 整列缩放：以 (fx, fy) 为焦点（列表坐标）。所有可见页同步改倍率和高度，
     * 焦点下那一页的相对位置保持不动，横向用同一个 tx。
     */
    fun setListZoom(target: Float, fx: Float, fy: Float) {
        val rv = recycler ?: return
        if (!continuous) return
        val newZ = target.coerceIn(1f, 4f)
        val old = listZoom
        if (kotlin.math.abs(newZ - old) < 0.002f) return
        val r = newZ / old
        val gapPx = (ContinuousMath.GAP_DP * rv.resources.displayMetrics.density).toInt()

        var anchorPos = RecyclerView.NO_POSITION
        var anchorTop = 0
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i)
            if (fy >= c.top && fy < c.bottom + gapPx) {
                anchorPos = rv.getChildAdapterPosition(c)
                anchorTop = c.top
                break
            }
        }

        listTx = ContinuousMath.anchoredTx(
            rv.width.toFloat(), sideInset(rv).toFloat(), old, newZ, listTx, fx
        )
        listZoom = newZ
        for (h in holders.values) {
            applyItemHeight(h, h.pageContainer.ratio)
            h.zoom.setListView(listZoom, listTx)
        }
        syncListTx()

        if (anchorPos != RecyclerView.NO_POSITION) {
            val newTop = (fy - (fy - anchorTop) * r).toInt()
            (rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                ?.scrollToPositionWithOffset(anchorPos, newTop)
        }
        rv.requestLayout()
        refreshHostBounds()
        // 布局完成后，窗口范围按新位置再算一次
        rv.post { if (continuous) for (h in holders.values) h.zoom.resyncViewport() }
    }

    /** 整列横向平移 dx（本地像素）；左右边界由 ZoomHost 夹住。 */
    fun panListX(dx: Float) {
        if (!continuous || listZoom <= 1.01f) return
        listTx += dx
        for (h in holders.values) h.zoom.setListView(listZoom, listTx)
        syncListTx()
    }

    /** 各页夹位后的 tx 一致，取回来当作整列的 tx，避免越界部分累积。 */
    private fun syncListTx() {
        val any = holders.values.firstOrNull() ?: return
        listTx = any.zoom.panX
    }

    private fun resetListZoom() {
        val rv = recycler ?: return
        setListZoom(1f, rv.width / 2f, rv.height / 2f)
        listTx = 0f
        for (h in holders.values) h.zoom.setListView(listZoom, listTx)
    }

    /** 列表项高度：连续滚动按页比例，逐页翻页回到铺满。 */
    private fun applyItemHeight(h: VH, ratio: Float) {
        val lp = h.itemView.layoutParams ?: return
        var want = ViewGroup.LayoutParams.MATCH_PARENT
        if (continuous) {
            val rv = recycler
            if (rv != null) {
                val inset = rv.resources.getDimensionPixelSize(R.dimen.pdf_page_horizontal_inset)
                val ih = ContinuousMath.itemHeight(
                    rv.width - rv.paddingLeft - rv.paddingRight, inset, ratio, listZoom
                )
                if (ih > 0) want = ih
            }
        }
        if (lp.height != want) {
            lp.height = want
            h.itemView.layoutParams = lp
        }
    }

    var initialZoom = 1f
    var initialPanX = 0f
    var initialPanY = 0f
    var fitMode = PageContainer.FIT
    var doublePage = false
    var activePosition = 0
        set(value) {
            if (field == value) return
            field = value
            holders.forEach { (position, holder) ->
                val active = isActive(position)
                holder.image.setCurrentPage(active)
                holder.secondImage.setCurrentPage(active)
            }
        }
    var pageViewportProvider: ((Int) -> PageViewportState?)? = null
    var onPageViewportChange: ((Int, PageViewportState) -> Unit)? = null
    var onPanChange: ((Float, Float) -> Unit)? = null
    /** 只有恢复会话的那一页用 initialPan，翻到的其它页回到页顶，不串平移量。 */
    var restorePanAt: Int = -1
    var onTextCopied: ((String) -> Unit)? = null
    var onTextNotReady: (() -> Unit)? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val zoom: ZoomHost = v as ZoomHost
        val spreadRoot: LinearLayout = v.findViewById(R.id.spreadRoot)
        val pageContainer: PageContainer = v.findViewById(R.id.pageContainer)
        val draftContainer: PageContainer = v.findViewById(R.id.draftContainer)
        val secondContainer: PageContainer = v.findViewById(R.id.secondContainer)
        val image: TiledPageView = v.findViewById(R.id.pageImage)
        val secondImage: TiledPageView = v.findViewById(R.id.secondImage)
        val ink: InkView = v.findViewById(R.id.inkView)
        val secondInk: InkView = v.findViewById(R.id.secondInk)
        val draftInk: InkView = v.findViewById(R.id.draftInk)
        val textOverlay: PageTextOverlay = v.findViewById(R.id.textOverlay)
        val secondTextOverlay: PageTextOverlay = v.findViewById(R.id.secondTextOverlay)
        var renderToken = 0
        var ratioJob: Job? = null
        val ratioFailures = HashSet<Int>()
        var boundPage = -1
        var boundLogical = -1
        var lastHostW = -1
        var lastHostH = -1
        var lastPrimaryFrame: PageFrame? = null
        var lastSecondFrame: PageFrame? = null
        var lastDraftFrame: PageFrame? = null
    }

    /** 双页模式下每个 item 是一组两页，itemCount 减半。 */
    override fun getItemCount() =
        if (doublePage) (repo.count + 1) / 2 else repo.count

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false))

    private fun params(weight: Float = 1f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)

    override fun onBindViewHolder(h: VH, position: Int) {
        h.renderToken++
        val token = h.renderToken
        h.ratioJob?.cancel()
        h.boundPage = position
        h.lastHostW = -1
        h.lastHostH = -1
        h.lastPrimaryFrame = null
        h.lastSecondFrame = null
        h.lastDraftFrame = null
        // 双页模式：一个 item 承载 p0 与 p0+1 两个逻辑页，键不重叠
        val p0 = DisplayMode.logical(
            if (doublePage) DisplayMode.DOUBLE else DisplayMode.FIT, true, position)
        val p1 = p0 + 1
        val showSecond = doublePage && p1 < repo.count
        h.boundLogical = p0
        holders[position] = h
        h.zoom.reset(silent = true,
            toTop = fitMode == PageContainer.WIDTH && (!state.landscape || state.pageRotation == 0))
        h.zoom.browseMode = state.browse
        h.zoom.continuousScroll = continuous
        h.zoom.onListZoomTo = { t, fx, fy -> setListZoom(t, fx, fy) }
        h.zoom.onListPan = { dx -> panListX(dx) }
        h.zoom.onZoomChange = { value ->
            if (position == activePosition) onZoomChange(value)
        }
        h.zoom.onFlip = onFlip
        h.zoom.onPanChange = { px, py ->
            if (position == activePosition) onPanChange?.invoke(px, py)
        }
        // 所有旋转角度统一使用 viewport 自绘，不再退回整页位图。
        h.zoom.legacyViewScale = false
        h.zoom.onViewportChanged = {
            val vectorMode = kotlin.math.abs(h.zoom.viewport.scale - 1f) > 0.0001f
            if (h.ink.isShown) {
                if (h.ink.hiResMode != vectorMode) h.ink.hiResMode = vectorMode
                else h.ink.invalidate()
            }
            if (h.secondInk.isShown) {
                if (h.secondInk.hiResMode != vectorMode) h.secondInk.hiResMode = vectorMode
                else h.secondInk.invalidate()
            }
            if (h.draftInk.isShown) {
                if (h.draftInk.hiResMode != vectorMode) h.draftInk.hiResMode = vectorMode
                else h.draftInk.invalidate()
            }
            if (h.image.isShown) h.image.invalidate()
            if (h.secondImage.isShown) h.secondImage.invalidate()
            if (h.textOverlay.needsRedraw) h.textOverlay.invalidate()
            if (h.secondTextOverlay.needsRedraw) h.secondTextOverlay.invalidate()
            h.zoom.onSelectionViewportChanged()
        }
        h.zoom.onViewportGestureChanged = { active ->
            h.image.deferHighResolution = active
            h.secondImage.deferHighResolution = active
        }
        h.zoom.onViewportSettled = {
            onPageViewportChange?.invoke(p0, h.zoom.pageViewportState())
        }
        h.image.deferHighResolution = false
        h.secondImage.deferHighResolution = false
        h.zoom.onScaleCommit = { sc ->
            val hi = kotlin.math.abs(sc - 1f) > 0.0001f
            h.ink.hiResMode = hi
            h.secondInk.hiResMode = hi
            h.draftInk.hiResMode = hi
        }
        h.image.viewport = h.zoom.viewport
        h.ink.viewport = h.zoom.viewport
        h.secondImage.viewport = h.zoom.secondViewport
        h.secondInk.viewport = h.zoom.secondViewport
        h.draftInk.viewport = h.zoom.draftViewport

        // 页内长按选字：文字层在后台线程读取，读到之前 layout 为 null
        h.zoom.clearTextSelection()
        h.textOverlay.viewport = h.zoom.viewport
        h.secondTextOverlay.viewport = h.zoom.secondViewport
        h.textOverlay.layout = null
        h.secondTextOverlay.layout = null
        h.textOverlay.loader = { loadTextLayout(h, h.textOverlay, p0, token, position) }
        h.secondTextOverlay.loader = { loadTextLayout(h, h.secondTextOverlay, p1, token, position) }
        h.secondTextOverlay.visibility = if (showSecond) View.VISIBLE else View.GONE
        h.zoom.textOverlays =
            if (showSecond) listOf(h.textOverlay, h.secondTextOverlay) else listOf(h.textOverlay)
        // v44：搜索中翻到 / 滚到的新页，文字层读到后自动带上高亮
        if (searchActive) refreshSearch(h)
        h.zoom.textGate = { ev ->
            state.browse ||
                (state.stylusOnly && ev.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_FINGER)
        }
        h.zoom.onTextCopied = { t -> onTextCopied?.invoke(t) }
        h.zoom.onTextMarkup = { overlay, shape, rects ->
            val ink = if (overlay === h.secondTextOverlay) h.secondInk else h.ink
            val (color, alpha) = when (shape) {
                Shapes.TEXT_HL -> 0xFFFFD60A.toInt() to 110
                else -> 0xFFE53935.toInt() to 255
            }
            ink.addTextMarkup(shape, rects, color, alpha, overlay.selectedText())
        }
        h.zoom.onTextNotReady = { onTextNotReady?.invoke() }

        // v31：三态对齐表。v29 的「非 FIT 一律顶部对齐」把「适应宽度」也
        // 贴到栏下、底部堆出约 455px 空白；现在 FIT / WIDTH 回垂直居中，
        // 只有 FILL（页宽铺满、贴顶）保持靠左上。横屏三模式不动。
        h.pageContainer.fitMode = fitMode
        h.secondContainer.fitMode = PageContainer.FIT
        h.draftContainer.fitMode = PageContainer.FIT
        // 只同步读取尺寸缓存；首次打开的新页在后台打开 PdfRenderer 页面取尺寸。
        val ratio0 = repo.cachedRatioWithRotation(p0, state.pageRotation)
            ?: if (p0 in h.ratioFailures) 0.75f else null
        val ratio1 = if (showSecond) {
            repo.cachedRatioWithRotation(p1, state.pageRotation)
                ?: if (p1 in h.ratioFailures) 0.75f else null
        } else null
        val ratiosReady = ratio0 != null && (!showSecond || ratio1 != null)
        if (ratio0 != null) lastRatio = ratio0
        applyItemHeight(h, ratio0 ?: lastRatio)
        h.pageContainer.ratio = ratio0 ?: 0.75f
        h.secondContainer.ratio = ratio1 ?: ratio0 ?: 0.75f
        h.draftContainer.ratio = h.pageContainer.ratio

        // 页高 ≥ 可用高时（WIDTH 的长页）仍回页顶，保证能上下拖看完整页；
        // 页高 < 可用高时上下均分留白。宿主未测量完成时不误判。
        val availW = (h.zoom.width.takeIf { it > 0 } ?: h.itemView.width).toFloat()
        val availH = (h.zoom.height.takeIf { it > 0 } ?: h.itemView.height).toFloat()
        val pageH = if (h.pageContainer.ratio > 0f && availW > 0f)
            availW / h.pageContainer.ratio else availH
        val alignTop = PageAlignment.resolve(
            portrait = !state.landscape,
            fitMode = fitMode,
            pageHeight = pageH,
            availableHeight = availH
        ) == PageAlignment.TOP_START
        h.spreadRoot.gravity =
            if (alignTop) Gravity.START or Gravity.TOP else Gravity.CENTER
        h.pageContainer.layoutParams = params().apply {
            gravity = if (alignTop) Gravity.TOP else Gravity.CENTER_VERTICAL
        }
        h.secondContainer.layoutParams = params().apply {
            marginStart = (10 * h.itemView.resources.displayMetrics.density).toInt()
            gravity = if (alignTop) Gravity.TOP else Gravity.CENTER_VERTICAL
        }
        h.draftContainer.layoutParams = params().apply {
            marginStart = (10 * h.itemView.resources.displayMetrics.density).toInt()
            gravity = if (alignTop) Gravity.TOP else Gravity.CENTER_VERTICAL
        }
        h.pageContainer.visibility = if (ratiosReady) View.VISIBLE else View.INVISIBLE
        h.secondContainer.visibility = when {
            !showSecond -> View.GONE
            ratiosReady -> View.VISIBLE
            else -> View.INVISIBLE
        }
        if (!ratiosReady) {
            h.ratioJob = scope.launch(Dispatchers.IO) {
                val failedPages = HashSet<Int>()
                if (ratio0 == null && runCatching { repo.ratio(p0) }.isFailure) {
                    failedPages.add(p0)
                }
                if (showSecond && ratio1 == null &&
                    runCatching { repo.ratio(p1) }.isFailure
                ) {
                    failedPages.add(p1)
                }
                withContext(Dispatchers.Main) {
                    if (h.renderToken == token &&
                        h.boundPage == position &&
                        h.boundLogical == p0 &&
                        h.bindingAdapterPosition == position
                    ) {
                        h.ratioFailures.addAll(failedPages)
                        notifyItemChanged(position)
                    }
                }
            }
        }

        // 双页并排与「题目 / 草稿对开」互斥，effectiveSpread 已在 UiState 降级
        h.draftContainer.visibility =
            if (state.effectiveSpread) View.VISIBLE else View.GONE

        h.image.colorFilter = null
        h.secondImage.colorFilter = null
        // 所有页面（含 90/180/270 度）统一走分块渲染。
        h.image.unbindTiled()
        h.secondImage.unbindTiled()
        h.image.setImageBitmap(null)
        h.secondImage.setImageBitmap(null)
        h.image.setCurrentPage(isActive(position))
        h.secondImage.setCurrentPage(isActive(position))

        h.ink.setPageLook(state.tint)
        h.secondInk.setPageLook(state.tint)
        h.draftInk.setPageLook(state.tint)
        h.ink.attach(host, "p$p0")
        h.secondInk.attach(host, "p$p1")
        h.draftInk.attach(host, "d$p0")
        onInkBound("p$p0", h.ink)
        if (showSecond) onInkBound("p$p1", h.secondInk)
        onInkBound("d$p0", h.draftInk)

        h.pageContainer.post {
            if (h.renderToken != token || h.bindingAdapterPosition != position) return@post
            if (!ratiosReady) return@post
            val w = h.pageContainer.width
            val ht = h.pageContainer.height
            if (w <= 0 || ht <= 0) return@post

            // v15：View 不再被 scale，w/ht 恒定就是页面基准尺寸；
            // 必须在 refreshPanBounds / setScale 之前写入 syncViewport。
            h.zoom.baseContentW = w
            h.zoom.baseContentH = ht

            val remembered = pageViewportProvider?.invoke(p0)
            when {
                // 连续滚动：所有页共用整列倍率与横向位移，不读单页记忆
                continuous -> h.zoom.setListView(listZoom, listTx)
                remembered != null -> h.zoom.setPageViewportState(remembered)
                position == restorePanAt &&
                    (kotlin.math.abs(initialZoom - 1f) > 0.0001f ||
                        initialPanX != 0f || initialPanY != 0f) -> {
                    h.zoom.setScale(initialZoom, initialPanX, initialPanY)
                }
                else -> {
                    h.zoom.reset(
                        silent = false,
                        toTop = fitMode == PageContainer.WIDTH
                    )
                    // v46：没有记忆位置的页，按内容外框自动裁白边（单页、铺宽模式才做）
                    if (state.autoCrop && fitMode != PageContainer.FIT &&
                        !showSecond && !state.effectiveSpread
                    ) requestAutoCrop(h, p0, token, position)
                }
            }
            if (position == restorePanAt) restorePanAt = -1

            // 0°/90°/180°/270° 全部按当前视口分块渲染。
            h.image.bindTiled(
                repo, p0, w, ht, state.tint, state.brightness,
                state.pageRotation, isActive(position)
            )
            if (showSecond) {
                val w2 = h.secondContainer.width
                val h2 = h.secondContainer.height
                if (w2 > 0 && h2 > 0) {
                    h.secondImage.bindTiled(
                        repo, p1, w2, h2, state.tint, state.brightness,
                        state.pageRotation, isActive(position)
                    )
                }
            }
            // 相邻页的少量预加载任务由全局队列排在当前页后面。
        }
    }

    private val loadingText = HashSet<PageTextOverlay>()

    /** 后台读取一页的文字层，回到主线程时确认这个 holder 还绑着同一页。 */
    private fun loadTextLayout(h: VH, o: PageTextOverlay, page: Int, token: Int, position: Int) {
        if (o.layout != null || !loadingText.add(o)) return
        val rot = state.pageRotation
        scope.launch(Dispatchers.IO) {
            val l = runCatching { repo.pageTextLayout(page)?.rotated(rot) }.getOrNull()
            withContext(Dispatchers.Main) {
                loadingText.remove(o)
                if (h.renderToken == token && h.bindingAdapterPosition == position) {
                    o.layout = l ?: PageTextLayout.EMPTY
                    pushSearch(o, page)
                }
            }
        }
    }


    // ----- v44 全文搜索：高亮 + 定位 -----

    private var searchRanges: Map<Int, List<IntArray>> = emptyMap()
    private var searchTexts: List<String> = emptyList()
    private var searchCurPage = -1
    private var searchCurStart = -1
    val searchActive: Boolean get() = searchRanges.isNotEmpty()

    /**
     * 设置要高亮的命中。texts 是搜索时用的每页文字：只有页面文字层的文字与它一致，
     * 命中下标才对得上字框；老提取器补出来的页没有字框，只能跳页、不能高亮。
     */
    fun setSearch(hits: List<SearchHit>, texts: List<String>, current: SearchHit?) {
        searchRanges = hits.groupBy { it.page }
            .mapValues { (_, v) -> v.map { intArrayOf(it.start, it.end) } }
        searchTexts = texts
        searchCurPage = current?.page ?: -1
        searchCurStart = current?.start ?: -1
        for (h in holders.values) refreshSearch(h)
    }

    fun clearSearch() = setSearch(emptyList(), emptyList(), null)

    /** v45：OCR 完成后，已加载的「本页没有文字」结果作废，下次长按选字 / 搜索重新读。 */
    fun reloadTextLayouts() {
        for (h in holders.values) {
            h.textOverlay.layout = null
            h.secondTextOverlay.layout = null
            if (searchActive) refreshSearch(h)
        }
    }

    private fun refreshSearch(h: VH) {
        val p0 = h.boundLogical
        if (p0 < 0) return
        pushSearch(h.textOverlay, p0)
        if (h.secondTextOverlay.visibility == View.VISIBLE) pushSearch(h.secondTextOverlay, p0 + 1)
    }

    private fun pushSearch(o: PageTextOverlay, page: Int) {
        if (!searchActive) { o.clearSearch(); return }
        val l = o.layout
        if (l == null) { o.loader?.invoke(); return }   // 读到后 loadTextLayout 会再推一次
        val list = searchRanges[page]
        if (list == null || searchTexts.getOrNull(page) != l.text) { o.clearSearch(); return }
        val cur = if (page == searchCurPage) list.indexOfFirst { it[0] == searchCurStart } else -1
        o.setSearch(list, cur)
    }

    /**
     * 把 [page] 页里从 [start] 起的那一处命中挪进可视区：缩放后平移，连续滚动时再滚列表。
     * 页面还没绑定 / 文字层还没读到时每 80ms 重试，最多约 1.2 秒。
     */
    fun revealSearchHit(page: Int, start: Int, attempt: Int = 0) {
        val rv = recycler ?: return
        fun retry() {
            if (attempt < 15) rv.postDelayed({ revealSearchHit(page, start, attempt + 1) }, 80L)
        }
        val h = holders.values.firstOrNull {
            it.boundLogical == page || (doublePage && it.boundLogical + 1 == page)
        }
        if (h == null) { retry(); return }
        val o = if (h.boundLogical == page) h.textOverlay else h.secondTextOverlay
        val idx = searchRanges[page]?.indexOfFirst { it[0] == start } ?: -1
        if (idx < 0) return                      // 这一处不在本页的高亮里：只跳页
        val layout = o.layout
        if (layout == null) { o.loader?.invoke(); retry(); return }
        // 没有字框（扫描页 / 老提取器补出的页）或文字对不上：只跳页，不再重试
        if (layout === PageTextLayout.EMPTY || searchTexts.getOrNull(page) != layout.text) return
        val b = FloatArray(4)
        if (!o.searchHitNormalizedBounds(idx, b)) { retry(); return }
        val pageView = o.parent as? View
        val cx = (b[0] + b[2]) * 0.5f
        val cy = (b[1] + b[3]) * 0.5f
        h.zoom.revealPagePoint(pageView, cx, cy)
        if (continuous) {
            val p = FloatArray(2)
            if (h.zoom.pagePointInHost(pageView, cx, cy, p)) {
                val y = h.itemView.top + p[1]
                val rh = rv.height.toFloat()
                if (rh > 0f && (y < rh * 0.16f || y > rh * 0.84f)) {
                    rv.smoothScrollBy(0, (y - rh * 0.4f).toInt())
                }
            }
        }
    }

    /** 当前页真实倍率：避免用全局 currentZoom 导致放大后翻页仍误禁翻页。 */
    fun zoomOf(pos: Int): Float = holders[pos]?.zoom?.zoomScale ?: 1f

    fun panOf(pos: Int): Pair<Float, Float> {
        val host = holders[pos]?.zoom ?: return 0f to 0f
        return host.panX to host.panY
    }

    /** Capture live normalized page positions before the host viewport changes. */
    fun capturePageViewports() {
        for (h in holders.values.toList()) {
            if (h.boundLogical < 0 ||
                h.bindingAdapterPosition == RecyclerView.NO_POSITION ||
                h.zoom.baseContentW <= 0 || h.zoom.baseContentH <= 0
            ) continue
            onPageViewportChange?.invoke(h.boundLogical, h.zoom.pageViewportState())
        }
    }

    /** 切换浏览 / 做题时，已绑定的所有页立刻生效，不用等重新 bind。 */
    fun setBrowseMode(v: Boolean) {
        for (h in holders.values) h.zoom.browseMode = v
    }

    /**
     * 栏显隐 / insets / 分屏改变宿主视口后，按每个 holder 的实际尺寸更新几何。
     * 不清空全局 tile cache；尺寸变化只生成新 key，旧低清预览可暂时托底，
     * 尺寸恢复时也能复用仍在缓存中的图块。
     */
    /**
     * v29：栏显隐 / insets / 分屏后强制页面重新测量。
     * 只调 refreshHostBounds 不够 —— fitMode 值没变时 PageContainer 的
     * setter 不会 requestLayout，FILL 页高会停在旧的可用高上。
     */
    fun relayoutForChrome() {
        for (h in holders.values.toList()) {
            h.pageContainer.requestLayout()
            h.secondContainer.requestLayout()
            h.draftContainer.requestLayout()
        }
        refreshHostBounds()
    }

    fun refreshHostBounds() {
        val anchor = holders.values.firstOrNull() ?: return
        if (hostBoundsRefreshPending) return

        // View.post may run before ViewPager2/RecyclerView has consumed the new
        // padding and completed measuring its page children. Read geometry from
        // the window's pre-draw pass instead, after every nested child is laid out.
        val observer = anchor.itemView.rootView.viewTreeObserver
        if (!observer.isAlive) {
            anchor.itemView.post { refreshHostBounds() }
            return
        }
        hostBoundsRefreshPending = true
        observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                hostBoundsRefreshPending = false
                refreshHostBoundsNow()
                return true
            }
        })
    }

    private fun refreshHostBoundsNow() {
        for (h in holders.values.toList()) {
            if (h.boundLogical < 0 ||
                h.bindingAdapterPosition == RecyclerView.NO_POSITION
            ) continue

            val primaryFrame = h.zoom.pageFrameFor(h.pageContainer) ?: continue
            val hw = h.zoom.width
            val hh = h.zoom.height
            if (hw <= 0 || hh <= 0) continue

            val secondFrame = h.zoom.pageFrameFor(h.secondContainer)
            val draftFrame = h.zoom.pageFrameFor(h.draftContainer)
            val hostChanged = hw != h.lastHostW || hh != h.lastHostH
            val primaryChanged = primaryFrame != h.lastPrimaryFrame
            val secondChanged = secondFrame != h.lastSecondFrame
            val draftChanged = draftFrame != h.lastDraftFrame
            if (!primaryChanged && !secondChanged && !draftChanged && !hostChanged) {
                continue
            }

            val savedViewport = if (primaryChanged || hostChanged) {
                // 保留归一化阅读位置；页面尺寸或宿主变化后按新边界还原。
                h.zoom.takePendingResizeViewportState()
                    ?: pageViewportProvider?.invoke(h.boundLogical)
                    ?: h.zoom.pageViewportState()
            } else null

            if (primaryChanged || hostChanged) {
                h.zoom.baseContentW = primaryFrame.width
                h.zoom.baseContentH = primaryFrame.height
            }

            if (primaryChanged) {
                h.image.bindTiled(
                    repo, h.boundLogical, primaryFrame.width, primaryFrame.height,
                    state.tint, state.brightness, state.pageRotation,
                    isActive(h.boundPage)
                )
            }

            if (secondChanged && secondFrame != null) {
                h.secondImage.bindTiled(
                    repo, h.boundLogical + 1, secondFrame.width, secondFrame.height,
                    state.tint, state.brightness, state.pageRotation,
                    isActive(h.boundPage)
                )
            }

            // 右页或草稿页单独改变尺寸时，也更新该页视口与整组平移边界。
            if (savedViewport != null) h.zoom.setPageViewportState(savedViewport)
            else h.zoom.refreshPageGeometry()
            if (continuous) h.zoom.setListView(listZoom, listTx)

            h.lastHostW = hw
            h.lastHostH = hh
            h.lastPrimaryFrame = primaryFrame
            h.lastSecondFrame = secondFrame
            h.lastDraftFrame = draftFrame
        }
    }

    fun bitmapFor(page: Int): android.graphics.Bitmap? =
        PdfBitmapCache.findPrefix("page:${repo.cacheId}:$page-")

    // ----- v46 自动裁白边 -----

    /** 页 × 旋转 → 归一化内容外框；EMPTY 表示扫过了但没什么可裁。 */
    private val cropCache = java.util.concurrent.ConcurrentHashMap<Int, FloatArray>()
    private val noCrop = FloatArray(0)

    fun invalidateCropCache() { cropCache.clear() }

    private fun requestAutoCrop(h: VH, page: Int, token: Int, position: Int) {
        val rot = RasterRotation.normalize(state.pageRotation)
        val key = page * 4 + rot / 90
        scope.launch(Dispatchers.IO) {
            val b = cropCache[key] ?: run {
                val found = runCatching {
                    val w = 240
                    val ratio = repo.ratioWithRotation(page, rot)
                    val ht = (w / ratio).toInt().coerceIn(48, 900)
                    val bmp = repo.render(page, w, ht, PageFilter.WHITE, 0f, rot)
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    AutoCrop.findBounds(px, bmp.width, bmp.height)
                }.getOrNull()
                (found ?: noCrop).also { cropCache[key] = it }
            }
            if (b.size < 4) return@launch
            withContext(Dispatchers.Main) {
                if (state.autoCrop && h.renderToken == token &&
                    h.bindingAdapterPosition == position && h.boundLogical == page
                ) h.zoom.fitContent(b)
            }
        }
    }

    fun invalidateRenderCache() {
        TileCache.clear()
        PdfBitmapCache.clearPrefix("page:${repo.cacheId}:")
    }

    /** 亮度滑杆拖动时实时可见，松手后才真正重渲染。 */
    fun previewBrightness(b: Float) {
        val s = (1f + b * 0.45f).coerceIn(0.45f, 1.45f)
        val cm = ColorMatrix(floatArrayOf(
            s, 0f, 0f, 0f, 0f,
            0f, s, 0f, 0f, 0f,
            0f, 0f, s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        val f = ColorMatrixColorFilter(cm)
        for (h in holders.values) {
            h.image.colorFilter = f
            h.secondImage.colorFilter = f
        }
    }

    fun clearPreview() {
        for (h in holders.values) {
            h.image.colorFilter = null
            h.secondImage.colorFilter = null
        }
    }

    fun evictAll() {
        PdfBitmapCache.clearPrefix("tile:${repo.cacheId}:")
        PdfBitmapCache.clearPrefix("tile-preview:${repo.cacheId}:")
        clearPreview()
    }

    fun resetZoom(page: Int) {
        if (continuous) {
            resetListZoom()
            return
        }
        // v15：所有模式都回 1:1 并回页顶（FIT 下没有额外纵向平移范围）
        holders[page]?.zoom?.reset(toTop = true)
    }

    override fun onViewRecycled(h: VH) {
        h.renderToken++
        h.ratioJob?.cancel()
        h.ratioJob = null
        val pos = h.boundPage
        val p = h.boundLogical
        if (pos >= 0) {
            // 回收的页不再被索引持有，撤销/清除/导入不会作用到废弃 View
            onInkRecycled("p$p")
            onInkRecycled("p${p + 1}")
            onInkRecycled("d$p")
            holders.remove(pos)
            h.boundPage = -1
            h.boundLogical = -1
        }
        h.image.unbindTiled()
        h.secondImage.unbindTiled()
        h.ink.cancelPendingLoad()
        h.secondInk.cancelPendingLoad()
        h.draftInk.cancelPendingLoad()
        h.ink.releaseBuffers()
        h.secondInk.releaseBuffers()
        h.draftInk.releaseBuffers()
        h.image.colorFilter = null
        h.image.setImageBitmap(null)
        h.secondImage.colorFilter = null
        h.secondImage.setImageBitmap(null)
        super.onViewRecycled(h)
    }
}
