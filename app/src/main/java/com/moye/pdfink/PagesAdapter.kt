package com.moye.pdfink

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /**
     * 按字节限额（而不是张数）。WIDTH 模式位图比 FIT 大得多，限额按可用堆
     * 动态取，避免荣耀 10 这类低堆机器上放几张就把内存吃满。
     * key 带页面 + 实际像素尺寸，栏隐藏 / 旋转 / 切模式导致尺寸变化时
     * 不会误用旧尺寸的低清图。
     */
    private val shown = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 8L)
            .toInt().coerceIn(12 * 1024, 48 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val holders = HashMap<Int, VH>()

    var initialZoom = 1f
    var initialPanX = 0f
    var initialPanY = 0f
    var fitMode = PageContainer.FIT
    var doublePage = false
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
        var boundPage = -1
        var boundLogical = -1
    }

    /** 双页模式下每个 item 是一组两页，itemCount 减半。 */
    override fun getItemCount() =
        if (doublePage) (repo.count + 1) / 2 else repo.count

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false))

    private fun params(weight: Float = 1f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)

    private fun key(page: Int, w: Int, h: Int) = "$page-${w}x$h"

    override fun onBindViewHolder(h: VH, position: Int) {
        h.boundPage = position
        // 双页模式：一个 item 承载 p0 与 p0+1 两个逻辑页，键不重叠
        val p0 = DisplayMode.logical(
            if (doublePage) DisplayMode.DOUBLE else DisplayMode.FIT, true, position)
        val p1 = p0 + 1
        val showSecond = doublePage && p1 < repo.count
        h.boundLogical = p0
        holders[position] = h
        h.zoom.reset(silent = true,
            toTop = fitMode == PageContainer.WIDTH && state.pageRotation == 0)
        h.zoom.onZoomChange = onZoomChange
        h.zoom.onFlip = onFlip
        h.zoom.onPanChange = onPanChange
        // 页面旋转过（低频）沿用旧 View 变换；其余走 v15 的高分辨率自绘
        h.zoom.legacyViewScale = state.pageRotation != 0
        h.zoom.onViewportChanged = {
            h.image.invalidate()
            h.secondImage.invalidate()
            h.ink.invalidate()
            h.secondInk.invalidate()
            h.draftInk.invalidate()
        }
        h.zoom.onScaleCommit = { sc ->
            val hi = sc > 1.01f && state.pageRotation == 0
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
        h.pageContainer.ratio = repo.ratioWithRotation(p0, state.pageRotation)
        h.secondContainer.ratio =
            if (showSecond) repo.ratioWithRotation(p1, state.pageRotation)
            else h.pageContainer.ratio
        h.draftContainer.ratio = h.pageContainer.ratio

        h.secondContainer.visibility = if (showSecond) View.VISIBLE else View.GONE
        // 双页并排与「题目 / 草稿对开」互斥，effectiveSpread 已在 UiState 降级
        h.draftContainer.visibility =
            if (state.effectiveSpread) View.VISIBLE else View.GONE

        h.image.colorFilter = null
        h.secondImage.colorFilter = null
        // v15：所有未旋转页都走分块（竖屏 FIT 也 1:1，放大仍锐利）；
        // 只有页面旋转过才退回整页 render（低频，见 README 边界）。
        val useTiles = state.pageRotation == 0
        h.image.unbindTiled()
        h.secondImage.unbindTiled()
        if (useTiles) {
            h.image.setImageBitmap(null)
            h.secondImage.setImageBitmap(null)
        } else {
            showAnyCached(h, p0, h.image)
            if (showSecond) showAnyCached(h, p1, h.secondImage)
            else h.secondImage.setImageBitmap(null)
        }

        h.ink.setPageLook(state.tint)
        h.secondInk.setPageLook(state.tint)
        h.draftInk.setPageLook(state.tint)
        h.ink.attach(host, "p$p0")
        h.secondInk.attach(host, "p$p1")
        h.draftInk.attach(host, "d$p0")
        onInkBound("p$p0", h.ink)
        if (showSecond) onInkBound("p$p1", h.secondInk)
        onInkBound("d$p0", h.draftInk)

        val token = ++h.renderToken
        h.pageContainer.post {
            if (h.renderToken != token || h.bindingAdapterPosition != position) return@post
            val w = h.pageContainer.width
            val ht = h.pageContainer.height
            if (w <= 0 || ht <= 0) return@post

            // v15：View 不再被 scale，w/ht 恒定就是页面基准尺寸；
            // 必须在 refreshPanBounds / setScale 之前写入 syncViewport。
            h.zoom.baseContentW = w
            h.zoom.baseContentH = ht

            // WIDTH 模式页面比容器高，把超出量交给 ZoomHost 做 1x 纵向平移边界
            h.zoom.panExtraX = 0f
            h.zoom.panExtraY = (ht - h.zoom.height).coerceAtLeast(0).toFloat()
            if (position == restorePanAt &&
                (initialZoom > 1.01f || initialPanX != 0f || initialPanY != 0f)) {
                h.zoom.setScale(initialZoom, initialPanX, initialPanY)
                restorePanAt = -1
            } else {
                h.zoom.refreshPanBounds()
            }

            if (useTiles) {
                // 基准尺寸不变；放大倍率由 viewport 在 onDraw 里换算成逻辑画布
                h.image.bindTiled(repo, scope, p0, w, ht,
                    state.tint, state.brightness)
                if (showSecond) {
                    val w2 = h.secondContainer.width
                    val h2 = h.secondContainer.height
                    if (w2 > 0 && h2 > 0) {
                        h.secondImage.bindTiled(repo, scope, p1, w2, h2,
                            state.tint, state.brightness)
                    }
                }
            } else {
                ensureRendered(h, p0, h.image, w, ht, token, position)
                if (showSecond) {
                    val w2 = h.secondContainer.width
                    val h2 = h.secondContainer.height
                    if (w2 > 0 && h2 > 0) {
                        ensureRendered(h, p1, h.secondImage, w2, h2, token, position)
                    }
                }
            }
            scope.launch(Dispatchers.IO) {
                try {
                    repo.prefetch(p0 - 1, w, ht)
                    repo.prefetch(p1 + 1, w, ht)
                } catch (e: Exception) { }
            }
        }
    }

    /** 先塞任意尺寸的旧缓存，避免重渲染期间闪白；随后按实际尺寸覆盖。 */
    private fun showAnyCached(h: VH, page: Int, image: ImageView) {
        val snap = shown.snapshot()
        for (k in snap.keys) {
            if (k.startsWith("$page-")) {
                image.setImageBitmap(snap[k])
                return
            }
        }
        image.setImageBitmap(null)
    }

    /** 按容器实际像素渲染：栏隐藏 / 切模式 / 旋转后尺寸变了自然重渲染。 */
    private fun ensureRendered(
        h: VH, page: Int, image: ImageView, w: Int, ht: Int,
        token: Int, position: Int
    ) {
        val k = key(page, w, ht)
        val hit = shown.get(k)
        if (hit != null) {
            image.setImageBitmap(hit)
            return
        }
        scope.launch(Dispatchers.IO) {
            val b = try {
                repo.render(page, w, ht, state.tint, state.brightness,
                    state.pageRotation)
            } catch (e: Exception) { null }
            if (b != null) withContext(Dispatchers.Main) {
                shown.put(k, b)
                if (h.renderToken == token &&
                    h.bindingAdapterPosition == position &&
                    image.isAttachedToWindow
                ) {
                    image.colorFilter = null
                    image.setImageBitmap(b)
                }
            }
        }
    }

    fun bitmapFor(page: Int): Bitmap? {
        val snap = shown.snapshot()
        for (k in snap.keys) if (k.startsWith("$page-")) return snap[k]
        return null
    }

    fun invalidateRenderCache() { shown.evictAll(); TileCache.clear() }

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
        shown.evictAll()
        clearPreview()
    }

    fun resetZoom(page: Int) {
        // v15：所有模式都回 1:1 并回页顶（FIT 下 panExtra=0，等价归零）
        holders[page]?.zoom?.reset(toTop = true)
    }

    override fun onViewRecycled(h: VH) {
        h.renderToken++
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
        h.image.colorFilter = null
        h.image.setImageBitmap(null)
        h.secondImage.colorFilter = null
        h.secondImage.setImageBitmap(null)
        super.onViewRecycled(h)
    }
}
