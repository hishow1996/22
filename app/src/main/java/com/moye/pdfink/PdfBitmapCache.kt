package com.moye.pdfink

import android.graphics.Bitmap
import android.util.LruCache

/**
 * PDF 位图共用受限总预算，并为低清连续预览保留独立空间。
 * 高清图块不能把预览缓存全部挤掉，否则缩放跨档时仍会短暂露出空白。
 */
object PdfBitmapCache {
    private val normalLimitKb: Int by lazy {
        (Runtime.getRuntime().maxMemory() / 1024L * 18L / 100L).toInt()
            .coerceIn(8 * 1024, 48 * 1024)
    }

    private val previewLimitKb: Int by lazy {
        (normalLimitKb * 0.22f).toInt()
            .coerceAtLeast(512)
            .coerceAtMost(10 * 1024)
            .coerceAtMost((normalLimitKb - 1024).coerceAtLeast(1))
    }

    private fun newCache(limitKb: Int) = object : LruCache<String, Bitmap>(limitKb) {
        override fun sizeOf(key: String, value: Bitmap) =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val previewCache = newCache(previewLimitKb)
    private val detailCache = newCache((normalLimitKb - previewLimitKb).coerceAtLeast(1024))

    private fun isPreview(key: String): Boolean = key.startsWith("tile-preview:")

    fun get(key: String): Bitmap? =
        if (isPreview(key)) previewCache.get(key) else detailCache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        if (isPreview(key)) previewCache.put(key, bitmap) else detailCache.put(key, bitmap)
    }

    fun remove(key: String) {
        if (isPreview(key)) previewCache.remove(key) else detailCache.remove(key)
    }

    fun findPrefix(prefix: String): Bitmap? =
        previewCache.snapshot().entries.firstOrNull { it.key.startsWith(prefix) }?.value
            ?: detailCache.snapshot().entries.firstOrNull { it.key.startsWith(prefix) }?.value

    fun clearPrefix(prefix: String) {
        previewCache.snapshot().keys.filter { it.startsWith(prefix) }
            .forEach(previewCache::remove)
        detailCache.snapshot().keys.filter { it.startsWith(prefix) }
            .forEach(detailCache::remove)
    }

    fun clear() {
        previewCache.evictAll()
        detailCache.evictAll()
    }

    /** 按系统内存压力同步收紧两级缓存；恢复前台后恢复正常上限。 */
    fun trim(level: Int) {
        val factor = when {
            level >= 80 -> 0.20f
            level >= 60 -> 0.32f
            level >= 40 -> 0.48f
            level >= 20 -> 0.65f
            level >= 15 -> 0.62f
            level >= 10 -> 0.48f
            else -> 0.82f
        }
        previewCache.resize((previewLimitKb * factor).toInt().coerceAtLeast(512))
        detailCache.resize(
            ((normalLimitKb - previewLimitKb) * factor).toInt().coerceAtLeast(1024)
        )
    }

    fun restore() {
        previewCache.resize(previewLimitKb)
        detailCache.resize((normalLimitKb - previewLimitKb).coerceAtLeast(1024))
    }

    fun sizeKb(): Int = previewCache.size() + detailCache.size()
}
