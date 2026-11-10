package com.moye.pdfink

import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/** 把「题目 + 笔迹」烘焙成一份新的 PDF 副本，原文件始终不动。 */
object PdfExporter {

    fun export(
        repo: PdfRepo,
        store: InkStore,
        pages: List<Int>,
        out: OutputStream,
        tint: Int,
        brightness: Float,
        layers: Set<Int>,
        spread: Boolean,
        renderWidth: Int = 1240,
        rotation: Int = 0,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Boolean {
        if (pages.isEmpty()) return false
        val doc = PdfDocument()
        return try {
            pages.forEachIndexed { i, page ->
                val ratio = repo.ratioWithRotation(page, rotation).coerceIn(0.3f, 3f)
                // 麒麟 970 上 PdfDocument 会在 writeTo 前缓存所有页位图，
                // 单页像素必须按可用堆与总页数收敛，避免整本导出 OOM。
                val maxH = (renderWidth * 1.7f).toInt().coerceAtMost(1800)
                var w = renderWidth.coerceIn(560, 1600)
                var h = (w / ratio).toInt()
                if (h > maxH) {
                    h = maxH
                    w = (h * ratio).toInt()
                }
                val bmp = PageComposer.compose(
                    repo, store, page, w, h, tint, brightness, layers, spread, rotation
                )
                val pw = (bmp.width * 72f / 200f).toInt().coerceAtLeast(1)
                val ph = (bmp.height * 72f / 200f).toInt().coerceAtLeast(1)
                val info = PdfDocument.PageInfo.Builder(pw, ph, i + 1).create()
                val p = doc.startPage(info)
                val m = Matrix().apply {
                    setScale(pw.toFloat() / bmp.width, ph.toFloat() / bmp.height)
                }
                p.canvas.drawBitmap(bmp, m, null)
                doc.finishPage(p)
                bmp.recycle()
                onProgress?.invoke(i + 1, pages.size)
            }
            doc.writeTo(out)
            true
        } catch (e: Exception) {
            false
        } finally {
            doc.close()
        }
    }
}
