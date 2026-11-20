package com.moye.pdfink

import java.util.Calendar
import java.util.TimeZone

/**
 * v48：首页书架按「最近打开时间」分段（像 WPS）：1 小时内 / 今天内 / 7 天内 / 更早。
 * 每个文件只落进一段：先判 1 小时内，再判今天，再判 7 天。纯 JVM。
 */
object HomeBuckets {
    enum class Bucket { STARRED, HOUR, TODAY, WEEK, EARLIER }

    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L

    /** 今天 0 点（本地时区）的毫秒数。 */
    fun startOfToday(now: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun bucketOf(time: Long, now: Long, startOfToday: Long): Bucket = when {
        time >= now - HOUR_MS -> Bucket.HOUR           // 含系统时钟被往回调导致「未来」的记录
        time >= startOfToday -> Bucket.TODAY
        time >= now - 7 * DAY_MS -> Bucket.WEEK
        else -> Bucket.EARLIER
    }

    /**
     * 按段分组，段顺序固定（收藏 / 1 小时 / 今天 / 7 天 / 更早），空段不出现；
     * 段内保持传入顺序。starred 为 true 的先放进「收藏」段（只在「收藏优先」档传 true）。
     */
    fun <T> group(
        items: List<T>, time: (T) -> Long, starred: ((T) -> Boolean)?,
        now: Long, startOfToday: Long
    ): List<Pair<Bucket, List<T>>> {
        val map = LinkedHashMap<Bucket, MutableList<T>>()
        for (b in Bucket.values()) map[b] = ArrayList()
        for (it in items) {
            val b = if (starred != null && starred(it)) Bucket.STARRED
            else bucketOf(time(it), now, startOfToday)
            map[b]!!.add(it)
        }
        return map.entries.filter { it.value.isNotEmpty() }.map { it.key to it.value }
    }
}
