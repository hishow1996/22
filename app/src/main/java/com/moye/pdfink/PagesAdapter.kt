package com.moye.pdfink

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
    private val repo: PdfRepo,
    private val host: InkView.Host,
    private val state: UiState,
    private val scope: CoroutineScope,
    private val onInkBound: (String, InkView) -> Unit,
    private val onInkRecycled: (String) -> Unit,
    private val onZoomChange: (Float) -> Unit,
    private val onFlip: (Int) -> Unit
) : RecyclerView.Adapter<PagesAdapter.VH>() {

    private val holders = HashMap<Int, VH>()

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
                val active = position == value
                holder.image.setCurrentPage(active)
                holder.secondImage.setCurrentPage(active)
            }
        }
    var pageViewportProvider: ((Int) -> PageViewportState?)? = null
    var onPageViewportChange: ((Int, PageViewportState) -> Unit)? = null
    var onPanChange: ((Float, Float) -> Unit)? = null
    /** 只有恢复会话的那一页用 initialPan，翻到的其它页回到页顶，不串平移量。 */
    var restorePanAt: Int = -1

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
        var renderToken = 0
        var ratioJob: Job? = null
        val ratioFailures = HashSet<Int>()
        var boundPage = -1
        var boundLogical = -1
        var lastHostW = -1
        var lastHostH = -1
        var lastPrimaryW = -1
        var lastPrimaryH = -1
        var lastSecondW = -1
        var lastSecondH = -1
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
        h.lastPrimaryW = -1
        h.lastPrimaryH = -1
        h.lastSecondW = -1
        h.lastSecondH = -1
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
            h.image.invalidate()
            h.secondImage.invalidate()
            h.ink.invalidate()
            h.secondInk.invalidate()
            h.draftInk.invalidate()
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
        val vp = h.zoom.viewport
        h.image.viewport = vp
        h.secondImage.viewport = vp
        h.ink.viewport = vp
        h.secondInk.viewport = vp
        h.draftInk.viewport = vp

        h.pageContainer.layoutParams = params()
        h.secondContainer.layoutParams = params().apply {
            marginStart = (10 * h.itemView.resources.displayMetrics.density).toInt()
        }
        h.draftContainer.layoutParams = params().apply {
            marginStart = (10 * h.itemView.resources.displayMetrics.density).toInt()
        }
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
        h.pageContainer.ratio = ratio0 ?: 0.75f
        h.secondContainer.ratio = ratio1 ?: ratio0 ?: 0.75f
        h.draftContainer.ratio = h.pageContainer.ratio
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
        h.image.setCurrentPage(position == activePosition)
        h.secondImage.setCurrentPage(position == activePosition)

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

            // FILL 下页面横向也会超出，panExtraX 不能再写死 0
            h.zoom.panExtraX = (w - h.zoom.width).coerceAtLeast(0).toFloat()
            h.zoom.panExtraY = (ht - h.zoom.height).coerceAtLeast(0).toFloat()
            val remembered = pageViewportProvider?.invoke(p0)
            when {
                remembered != null -> h.zoom.setPageViewportState(remembered)
                position == restorePanAt &&
                    (kotlin.math.abs(initialZoom - 1f) > 0.0001f ||
                        initialPanX != 0f || initialPanY != 0f) -> {
                    h.zoom.setScale(initialZoom, initialPanX, initialPanY)
                }
                else -> {
                    h.zoom.reset(silent = false,
                        toTop = fitMode == PageContainer.WIDTH ||
                            fitMode == PageContainer.FILL)
                    if (fitMode == PageContainer.FILL) h.zoom.alignStart()
                }
            }
            if (position == restorePanAt) restorePanAt = -1

            // 0°/90°/180°/270° 全部按当前视口分块渲染。
            h.image.bindTiled(
                repo, p0, w, ht, state.tint, state.brightness,
                state.pageRotation, position == activePosition
            )
            if (showSecond) {
                val w2 = h.secondContainer.width
                val h2 = h.secondContainer.height
                if (w2 > 0 && h2 > 0) {
                    h.secondImage.bindTiled(
                        repo, p1, w2, h2, state.tint, state.brightness,
                        state.pageRotation, position == activePosition
                    )
                }
            }
            // 相邻页的少量预加载任务由全局队列排在当前页后面。
        }
    }

    /** 当前页真实倍率：避免用全局 currentZoom 导致放大后翻页仍误禁翻页。 */
    fun zoomOf(pos: Int): Float = holders[pos]?.zoom?.zoomScale ?: 1f

    fun panOf(pos: Int): Pair<Float, Float> {
        val host = holders[pos]?.zoom ?: return 0f to 0f
        return host.panX to host.panY
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
    fun refreshHostBounds() {
        val anchor = holders.values.firstOrNull() ?: return
        anchor.itemView.post {
            for (h in holders.values.toList()) {
                if (h.boundLogical < 0 ||
                    h.bindingAdapterPosition == RecyclerView.NO_POSITION
                ) continue

                val w = h.pageContainer.width
                val ht = h.pageContainer.height
                val hw = h.zoom.width
                val hh = h.zoom.height
                if (w <= 0 || ht <= 0 || hw <= 0 || hh <= 0) continue

                val secondVisible = h.secondContainer.visibility == View.VISIBLE
                val secondW = if (secondVisible) h.secondContainer.width else 0
                val secondH = if (secondVisible) h.secondContainer.height else 0
                val primaryChanged = w != h.lastPrimaryW || ht != h.lastPrimaryH
                val secondChanged = secondVisible &&
                    (secondW != h.lastSecondW || secondH != h.lastSecondH)
                val hostChanged = hw != h.lastHostW || hh != h.lastHostH

                if (!primaryChanged && !secondChanged && !hostChanged) continue

                if (primaryChanged || hostChanged) {
                    // 先保留归一化阅读位置，再按新边界还原，防止栏显隐改变阅读锚点。
                    val savedViewport = h.zoom.pageViewportState()
                    h.zoom.baseContentW = w
                    h.zoom.baseContentH = ht
                    h.zoom.panExtraX = (w - hw).coerceAtLeast(0).toFloat()
                    h.zoom.panExtraY = (ht - hh).coerceAtLeast(0).toFloat()
                    h.zoom.setPageViewportState(savedViewport)
                }

                if (primaryChanged) {
                    h.image.bindTiled(
                        repo, h.boundLogical, w, ht, state.tint, state.brightness,
                        state.pageRotation, h.boundPage == activePosition
                    )
                }

                if (secondChanged && secondW > 0 && secondH > 0) {
                    h.secondImage.bindTiled(
                        repo, h.boundLogical + 1, secondW, secondH,
                        state.tint, state.brightness, state.pageRotation,
                        h.boundPage == activePosition
                    )
                }

                h.lastHostW = hw
                h.lastHostH = hh
                h.lastPrimaryW = w
                h.lastPrimaryH = ht
                h.lastSecondW = secondW
                h.lastSecondH = secondH
            }
        }
    }

    fun bitmapFor(page: Int): android.graphics.Bitmap? =
        PdfBitmapCache.findPrefix("page:${repo.cacheId}:$page-")

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
        // v15：所有模式都回 1:1 并回页顶（FIT 下 panExtra=0，等价归零）
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
