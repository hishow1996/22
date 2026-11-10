package com.moye.pdfink

import android.graphics.Bitmap
import android.util.LruCache

/**
 * 所有 PDF 显示位图共用一个字节预算，避免整页缓存、分块缓存和预览缓存
 * 各自占满一份堆内存。命中时 LruCache 会自动把正在使用的页面提升为近期项。
 */
object PdfBitmapCache {
    private val normalLimitKb: Int by lazy {
        (Runtime.getRuntime().maxMemory() / 1024L * 18L / 100L).toInt()
            .coerceIn(8 * 1024, 48 * 1024)
    }

    private val cache = object : LruCache<String, Bitmap>(normalLimitKb) {
        override fun sizeOf(key: String, value: Bitmap) =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled) cache.put(key, bitmap)
    }

    fun remove(key: String) {
        cache.remove(key)
    }

    fun findPrefix(prefix: String): Bitmap? =
        cache.snapshot().entries.firstOrNull { it.key.startsWith(prefix) }?.value

    fun clearPrefix(prefix: String) {
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach(cache::remove)
    }

    fun clear() {
        cache.evictAll()
    }

    /** 按系统内存压力临时收紧预算；恢复前台后可恢复正常上限。 */
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
        cache.resize((normalLimitKb * factor).toInt().coerceAtLeast(4 * 1024))
    }

    fun restore() {
        cache.resize(normalLimitKb)
    }

    fun sizeKb(): Int = cache.size()
}
