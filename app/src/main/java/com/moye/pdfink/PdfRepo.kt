package com.moye.pdfink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import kotlin.math.min
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 系统 PdfRenderer 封装：串行访问 + raw 页缓存 + 滤镜与旋转输出。 */
class PdfRepo(private val ctx: Context, private val uri: Uri) : PageSource {

    private val pfd: ParcelFileDescriptor =
        ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("cannot open uri")

    private val renderer = PdfRenderer(pfd)
    private val ratios = ConcurrentHashMap<Int, Float>()
    // The tile scheduler is serial. Reuse a page for a short run of same-page
    // tile jobs, then close it to keep the native page resource bounded.
    private var tileRenderPage: PdfRenderer.Page? = null
    private var tileRenderPageIndex = -1
    private var tileRenderPageUses = 0
    override val cacheId: String = "doc-${nextId.incrementAndGet()}"

    companion object {
        private const val MAX_TILE_PAGE_REUSES = 4
        private val nextId = AtomicLong()
    }

    override val count: Int get() = renderer.pageCount

    // v43：文字层按需抽一次并缓存；扫描件 / 加密 PDF 得到空列表，
    // pageText 返回 null，由 UI 如实提示「本页没有可复制的文字」。
    @Volatile private var textPages: List<String>? = null

    private fun readSourceBytes(): ByteArray = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(65536)
            var left = PdfTextExtractor.MAX_SOURCE_BYTES + 1   // 多读 1 字节，超限即可识别
            while (left > 0) {
                val n = input.read(tmp, 0, minOf(tmp.size, left))
                if (n <= 0) break
                buf.write(tmp, 0, n)
                left -= n
            }
            buf.toByteArray()
        } ?: ByteArray(0)
    } catch (e: Exception) { ByteArray(0) }

    private fun ensureText(): List<String> {
        textPages?.let { return it }
        val pages = PdfTextExtractor.extract(readSourceBytes())
        textPages = pages
        return pages
    }

    // 页内选字：解析器 + 对象表较占内存，用软引用，系统缺内存时可回收，下次再解析。
    private val layoutLock = Any()
    private var layoutDoc: java.lang.ref.SoftReference<PdfLayoutDoc>? = null
    private var layoutDocFailed = false
    private val layoutCache = object : LinkedHashMap<Int, PageTextLayout?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageTextLayout?>?) =
            size > 12
    }

    /** 原生文字层优先；本页没有文字时，用 OCR 的结果顶上（识别过才有）。 */
    override fun pageTextLayout(page: Int): PageTextLayout? {
        val l = nativeLayout(page)
        if (l != null && l.text.isNotBlank()) return l
        return ocrLayoutOf(page) ?: l
    }

    private fun nativeLayout(page: Int): PageTextLayout? = synchronized(layoutLock) {
        if (layoutCache.containsKey(page)) return@synchronized layoutCache[page]
        val doc = layoutDocLocked() ?: return@synchronized null
        val l = doc.layout(page)
        layoutCache[page] = l
        l
    }

    /** 调用方须持有 layoutLock。读不出（加密 / 过大 / 损坏）后不再重试。 */
    private fun layoutDocLocked(): PdfLayoutDoc? {
        if (layoutDocFailed) return null
        var doc = layoutDoc?.get()
        if (doc == null) {
            val bytes = readSourceBytes()
            doc = if (bytes.isEmpty() || bytes.size > PdfTextExtractor.MAX_SOURCE_BYTES) null
                  else PdfLayoutDoc.open(bytes)
            if (doc == null) {
                layoutDocFailed = true
                return null
            }
            layoutDoc = java.lang.ref.SoftReference(doc)
        }
        return doc
    }

    @Volatile private var outlineCache: List<OutlineEntry>? = null

    // v45：PDF 自带目录。系统 PdfRenderer 不暴露目录，这里用自己的解析器读 /Outlines。
    override fun outline(): List<OutlineEntry> {
        outlineCache?.let { return it }
        val items = synchronized(layoutLock) { layoutDocLocked()?.outline() ?: emptyList() }
        outlineCache = items
        return items
    }

    override fun pageText(page: Int): String? =
        ensureText().getOrNull(page)?.takeIf { it.isNotBlank() }

    // v44：全文搜索 / 复制全文用版面文字（与选字同源，按每段文字实际字体解码），
    // 读不出来的页才退回老提取器；与 pageText 的缓存分开，互不拖慢。
    // ----- v45：扫描页 OCR -----

    private val ocrLock = Any()
    private var ocrPages: Array<PageTextLayout?>? = null      // null 项 = 这一页还没识别过

    private fun ocrFile(): java.io.File? = contentFp?.let { OcrCache.fileFor(ctx.cacheDir, it) }

    /** 调用方须持有 ocrLock。第一次用到时从磁盘读；没有缓存就是全空数组。 */
    private fun ocrArrayLocked(): Array<PageTextLayout?> {
        ocrPages?.let { return it }
        val n = count
        val arr = ocrFile()?.let { OcrCache.read(it, n) } ?: arrayOfNulls(n)
        ocrPages = arr
        return arr
    }

    private fun ocrLayoutOf(page: Int): PageTextLayout? = synchronized(ocrLock) {
        ocrArrayLocked().getOrNull(page)?.takeIf { it.text.isNotBlank() }
    }

    override fun ocrPending(): Int {
        val pages = extractAllText()
        return synchronized(ocrLock) {
            val ocr = ocrArrayLocked()
            pages.indices.count { pages[it].isBlank() && ocr.getOrNull(it) == null }
        }
    }

    /** 渲染一页给 OCR 用：长边约 2200px（内存紧时 1800），白底，调用方负责回收。 */
    private fun renderForOcr(page: Int): Bitmap = synchronized(renderer) {
        val r = ratio(page)                                  // 宽 / 高
        val side = if (Runtime.getRuntime().maxMemory() >= 192L * 1024 * 1024) 2200f else 1800f
        val w = if (r >= 1f) side.toInt() else (side * r).toInt()
        val h = if (r >= 1f) (side / r).toInt() else side.toInt()
        closeTileRenderPageLocked()
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        try {
            val p = renderer.openPage(page)
            try { p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } finally { p.close() }
            bmp
        } catch (t: Throwable) {
            if (!bmp.isRecycled) bmp.recycle()
            throw t
        }
    }

    override fun runOcr(progress: (Int, Int) -> Unit, cancelled: () -> Boolean): Int {
        val base = extractAllText()
        val todo = synchronized(ocrLock) {
            val ocr = ocrArrayLocked()
            base.indices.filter { base[it].isBlank() && ocr.getOrNull(it) == null }
        }
        if (todo.isEmpty()) return 0
        var done = 0
        progress(0, todo.size)
        for (page in todo) {
            if (cancelled()) break
            val bmp = try { renderForOcr(page) } catch (t: Throwable) { null } ?: continue
            val layout = try {
                OcrEngine.recognize(bmp)?.let { OcrLayoutBuilder.build(it, bmp.width, bmp.height) }
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
            if (layout != null) {          // null = 引擎失败，留给下次重试；空文字也算「识别过」
                synchronized(ocrLock) { ocrArrayLocked()[page] = layout }
                done++
                // 每 4 页落一次盘，中途退出也能接着识别
                if (done % 4 == 0) synchronized(ocrLock) { ocrFile()?.let { OcrCache.write(it, ocrArrayLocked()) } }
            }
            progress(done, todo.size)
        }
        synchronized(ocrLock) { ocrFile()?.let { OcrCache.write(it, ocrArrayLocked()) } }
        if (done > 0) {
            // 把 OCR 文字并进搜索文本并重写文字缓存；字框层的 LRU 里可能缓存了「本页没有文字」，一并作废
            synchronized(layoutLock) { layoutCache.clear() }
            val merged = synchronized(ocrLock) {
                val ocr = ocrArrayLocked()
                base.mapIndexed { i, t ->
                    if (t.isBlank()) ocr.getOrNull(i)?.text?.takeIf { it.isNotBlank() } ?: t else t
                }
            }
            synchronized(textLock) {
                searchPages = merged
                contentFp?.let { TextCache.write(TextCache.fileFor(ctx.cacheDir, it), merged) }
            }
        }
        return done
    }

    @Volatile private var searchPages: List<String>? = null
    @Volatile private var contentFp: String? = null
    private val textLock = Any()

    override fun attachFingerprint(fp: String) { contentFp = fp }

    // v45：提取结果按内容指纹落盘缓存（cacheDir，系统可回收）；同一份文档第二次起秒出。
    // 并发调用（后台预热 + 用户点搜索）串行化，后到的直接拿先到的结果，不重复提取。
    // 全是空白（扫描件 / 加密 / 解析失败）不落盘，避免一次临时失败被缓存成「没有文字」。
    override fun extractAllText(): List<String> = synchronized(textLock) {
        searchPages?.let { return@synchronized it }
        val file = contentFp?.let { TextCache.fileFor(ctx.cacheDir, it) }
        if (file != null) {
            TextCache.read(file, count)?.let {
                searchPages = it
                return@synchronized it
            }
        }
        val pages = SearchText.build(count, { pageTextLayout(it) }) { ensureText() }
        searchPages = pages
        if (file != null && DocumentSearch.hasText(pages)) TextCache.write(file, pages)
        pages
    }

    private fun closeTileRenderPageLocked() {
        val opened = tileRenderPage
        tileRenderPage = null
        tileRenderPageIndex = -1
        tileRenderPageUses = 0
        try {
            opened?.close()
        } catch (_: Exception) {
        }
    }

    private fun acquireTileRenderPageLocked(page: Int): PdfRenderer.Page {
        val current = tileRenderPage
        if (current != null && tileRenderPageIndex == page &&
            tileRenderPageUses < MAX_TILE_PAGE_REUSES
        ) return current

        closeTileRenderPageLocked()
        val opened = renderer.openPage(page)
        tileRenderPage = opened
        tileRenderPageIndex = page
        tileRenderPageUses = 0
        return opened
    }

    override fun ratio(page: Int): Float = synchronized(renderer) {
        ratios[page] ?: run {
            closeTileRenderPageLocked()
            val p = renderer.openPage(page)
            val r = try {
                p.width.toFloat() / p.height.coerceAtLeast(1).toFloat()
            } finally {
                p.close()
            }
            ratios[page] = r
            r
        }
    }

    /** 缓存命中版本只读内存，不打开 PdfRenderer 页面。 */
    override fun cachedRatioWithRotation(page: Int, rotation: Int): Float? =
        ratios[page]?.let {
            if (PdfTileTransform.normalizeRotation(rotation) in setOf(90, 270)) 1f / it else it
        }

    /** 90 / 270 度时页面宽高互换，容器比例要取倒数。 */
    override fun ratioWithRotation(page: Int, rotation: Int): Float {
        val r = ratio(page)
        return if (PdfTileTransform.normalizeRotation(rotation) in setOf(90, 270)) 1f / r else r
    }

    private fun raw(page: Int, w: Int, h: Int): Bitmap = synchronized(renderer) {
        // 按可用堆分档：屏幕 WIDTH 走分块（不经过这里），
        // 导出 / 缩略图才走整页，尽量接近显示宽度但避免 OOM
        val mem = Runtime.getRuntime().maxMemory()
        val maxSide = when {
            mem >= 256L * 1024 * 1024 -> 3200f
            mem >= 192L * 1024 * 1024 -> 2600f
            mem >= 128L * 1024 * 1024 -> 2200f
            else -> 1800f
        }
        val s = min(1f, maxSide / maxOf(w, h).coerceAtLeast(1).toFloat())
        val rw = (w * s).toInt().coerceAtLeast(1)
        val rh = (h * s).toInt().coerceAtLeast(1)
        val key = "raw:$cacheId:$page-${rw}x$rh"
        val hit = PdfBitmapCache.get(key)
        if (hit != null) {
            hit
        } else {
            closeTileRenderPageLocked()
            val bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            try {
                val p = renderer.openPage(page)
                try {
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } finally {
                    p.close()
                }
                PdfBitmapCache.put(key, bmp)
                bmp
            } catch (failure: Throwable) {
                if (!bmp.isRecycled) bmp.recycle()
                throw failure
            }
        }
    }

    /**
     * 只渲染逻辑画布上 (left,top) 起 tw×th 的一块，横向严格 1:1。
     * A = 逻辑画布宽 / PDF 页宽（已含放大倍率），矩阵 = 缩放 A + 平移 -(块左上/A)。
     * PdfRenderer 按 bitmap 边界裁剪，所以只分配 tw×th 的位图，
     * 4× 放大时可见窗口仍是屏幕大小，块数不随倍率增长。
     */
    override fun renderTile(
        page: Int, lw: Int, lh: Int, left: Int, top: Int,
        tw: Int, th: Int, tint: Int, brightness: Float,
        rotation: Int
    ): Bitmap {
        if (lw <= 0 || tw <= 0 || th <= 0 || left >= lw || top >= lh) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        try {
            synchronized(renderer) {
                val p = acquireTileRenderPageLocked(page)
                try {
                    val values = PdfTileTransform.matrixValues(
                        p.width.toFloat(), p.height.toFloat(), lw, left, top, rotation
                    )
                    val m = Matrix().apply { setValues(values) }
                    p.render(out, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    tileRenderPageUses++
                    if (tileRenderPageUses >= MAX_TILE_PAGE_REUSES) {
                        closeTileRenderPageLocked()
                    }
                } catch (failure: Throwable) {
                    closeTileRenderPageLocked()
                    throw failure
                }
            }
            val filtered = PageFilter.apply(out, tint, brightness)
            if (filtered !== out) out.recycle()
            return filtered
        } catch (failure: Throwable) {
            if (!out.isRecycled) out.recycle()
            throw failure
        }
    }

    override fun render(
        page: Int, w: Int, h: Int, tint: Int, brightness: Float,
        rotation: Int
    ): Bitmap {
        val normalizedRotation = PdfTileTransform.normalizeRotation(rotation)
        val swap = normalizedRotation == 90 || normalizedRotation == 270
        val rw = if (swap) h else w
        val rh = if (swap) w else h
        val src = raw(page, rw, rh)
        val filtered = PageFilter.apply(src, tint, brightness)
        if (normalizedRotation == 0) return filtered
        val m = Matrix().apply { postRotate(normalizedRotation.toFloat()) }
        val out = Bitmap.createBitmap(
            filtered, 0, 0, filtered.width, filtered.height, m, true
        )
        // filtered 可能就是 raw 缓存对象，绝不能回收它
        if (out !== filtered && out !== src) filtered.recycle()
        return out
    }

    /** 只填缓存，结果丢弃，用于相邻页预渲染。 */
    override fun prefetch(page: Int, w: Int, h: Int) {
        if (page < 0 || page >= count) return
        raw(page, w, h)
    }

    override fun thumb(page: Int, w: Int): Bitmap {
        val h = (w / ratio(page)).toInt().coerceAtLeast(1)
        return render(page, w, h, PageFilter.WHITE, 0f)
    }

    override fun close() {
        PdfTileScheduler.cancelRepository(cacheId)
        try {
            synchronized(renderer) {
                closeTileRenderPageLocked()
                renderer.close()
            }
        } finally {
            pfd.close()
            PdfBitmapCache.clearPrefix("raw:$cacheId:")
            PdfBitmapCache.clearPrefix("tile:$cacheId:")
            PdfBitmapCache.clearPrefix("tile-preview:$cacheId:")
            PdfBitmapCache.clearPrefix("page:$cacheId:")
        }
    }
}
