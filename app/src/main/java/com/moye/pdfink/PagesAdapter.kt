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

    /** 按字节限额，而不是张数：8 张 2200px 图曾接近 150MB。 */
    private val shown = object : LruCache<Int, Bitmap>(24 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val holders = HashMap<Int, VH>()
    var initialZoom = 1f

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val zoom: ZoomHost = v as ZoomHost
        val spreadRoot: LinearLayout = v.findViewById(R.id.spreadRoot)
        val pageContainer: PageContainer = v.findViewById(R.id.pageContainer)
        val draftContainer: PageContainer = v.findViewById(R.id.draftContainer)
        val image: ImageView = v.findViewById(R.id.pageImage)
        val ink: InkView = v.findViewById(R.id.inkView)
        val draftInk: InkView = v.findViewById(R.id.draftInk)
        var renderToken = 0
        var boundPage = -1
    }

    override fun getItemCount() = repo.count

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false))

    private fun params(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)

    override fun onBindViewHolder(h: VH, position: Int) {
        h.boundPage = position
        holders[position] = h
        h.zoom.reset(silent = true)
        if (initialZoom > 1.01f) h.zoom.setScale(initialZoom)
        h.zoom.onZoomChange = onZoomChange
        h.zoom.onFlip = onFlip
        h.pageContainer.layoutParams = params()
        h.draftContainer.layoutParams = params().apply {
            marginStart = (10 * h.itemView.resources.displayMetrics.density).toInt()
        }
        h.pageContainer.ratio = repo.ratioWithRotation(position, state.pageRotation)
        h.draftContainer.ratio = repo.ratioWithRotation(position, state.pageRotation)
        h.draftContainer.visibility = if (state.effectiveSpread) View.VISIBLE else View.GONE
        h.image.colorFilter = null

        // 命中缓存立即显示，翻回上一页不再重新渲染 PDF
        val hit = shown.get(position)
        h.image.setImageBitmap(hit)

        h.ink.setPageLook(state.tint)
        h.draftInk.setPageLook(state.tint)
        h.ink.attach(host, "p$position")
        h.draftInk.attach(host, "d$position")
        onInkBound("p$position", h.ink)
        onInkBound("d$position", h.draftInk)

        val token = ++h.renderToken
        h.pageContainer.post {
            if (h.renderToken != token || h.bindingAdapterPosition != position) return@post
            val w = h.pageContainer.width
            val ht = h.pageContainer.height
            if (w <= 0 || ht <= 0) return@post
            val cached = shown.get(position)
            if (cached != null && cached.width == w && cached.height == ht) {
                // 尺寸一致的缓存可直接用，只补相邻页预渲染
                scope.launch(Dispatchers.IO) {
                    try {
                        repo.prefetch(position - 1, w, ht)
                        repo.prefetch(position + 1, w, ht)
                    } catch (e: Exception) { }
                }
                return@post
            }
            scope.launch(Dispatchers.IO) {
                val b = try {
                    repo.render(position, w, ht, state.tint, state.brightness,
                        state.pageRotation)
                } catch (e: Exception) { null }
                try {
                    repo.prefetch(position - 1, w, ht)
                    repo.prefetch(position + 1, w, ht)
                } catch (e: Exception) { }
                if (b != null) withContext(Dispatchers.Main) {
                    shown.put(position, b)
                    if (h.renderToken == token && h.bindingAdapterPosition == position) {
                        h.image.colorFilter = null
                        h.image.setImageBitmap(b)
                    }
                }
            }
        }
    }

    fun bitmapFor(page: Int): Bitmap? = shown.get(page)

    fun invalidateRenderCache() { shown.evictAll() }

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
        for (h in holders.values) h.image.colorFilter = f
    }

    fun clearPreview() {
        for (h in holders.values) h.image.colorFilter = null
    }

    fun evictAll() {
        shown.evictAll()
        clearPreview()
    }

    fun resetZoom(page: Int) { holders[page]?.zoom?.reset() }

    override fun onViewRecycled(h: VH) {
        h.renderToken++
        val p = h.boundPage
        if (p >= 0) {
            // 关键：回收的页不再被索引持有，撤销/清除/导入不会作用到废弃 View
            onInkRecycled("p$p")
            onInkRecycled("d$p")
            holders.remove(p)
            h.boundPage = -1
        }
        h.image.colorFilter = null
        h.image.setImageBitmap(null)
        super.onViewRecycled(h)
    }
}
