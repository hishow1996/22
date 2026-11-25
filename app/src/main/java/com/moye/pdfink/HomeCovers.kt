package com.moye.pdfink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/**
 * v61：书架卡片封面（PDF 第一页缩略图）。
 * 内存 LRU → 磁盘缓存（cacheDir/covers，按内容指纹命名）→ 现渲染，渲染在单线程后台串行做，
 * 完成后回主线程通知。同一份文件同时被多张卡片请求只渲一次。失败的（加密 / 损坏）本次运行不再重试。
 */
class HomeCovers(ctx: Context) {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "home-covers").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val mem = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val dir = File(app.cacheDir, "covers").apply { mkdirs() }
    private val pending = HashMap<String, MutableList<(Bitmap?) -> Unit>>()   // 仅主线程访问
    private val failed = HashSet<String>()                                    // 仅主线程访问

    fun cached(fp: String): Bitmap? = mem.get(fp)

    /** 主线程调用。命中内存直接回调；否则排队，回调一定在主线程。 */
    fun load(fp: String, uri: Uri, done: (Bitmap?) -> Unit) {
        mem.get(fp)?.let { done(it); return }
        if (fp in failed) { done(null); return }
        val waiting = pending[fp]
        if (waiting != null) { waiting.add(done); return }
        pending[fp] = mutableListOf(done)
        exec.execute {
            val bmp = try { fromDisk(fp) ?: render(fp, uri) } catch (_: Throwable) { null }
            main.post {
                if (bmp != null) mem.put(fp, bmp) else failed.add(fp)
                pending.remove(fp)?.forEach { it(bmp) }
            }
        }
    }

    private fun file(fp: String) = File(dir, fp.filter { it.isLetterOrDigit() }.take(64) + ".jpg")

    private fun fromDisk(fp: String): Bitmap? {
        val f = file(fp)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    private fun render(fp: String, uri: Uri): Bitmap? {
        val pfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return null
        var renderer: PdfRenderer? = null
        try {
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount <= 0) return null
            val page = renderer.openPage(0)
            try {
                val sz = HomeCardMath.coverSize(page.width, page.height)
                val bmp = Bitmap.createBitmap(sz[0], sz[1], Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                runCatching {
                    file(fp).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                }
                return bmp
            } finally {
                page.close()
            }
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd.close() }
        }
    }
}
