package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Test
import com.moye.pdfink.HomeBuckets.Bucket as B
import java.util.Calendar
import java.util.TimeZone

class HomeBucketsTest {
    private val tz = TimeZone.getTimeZone("Asia/Shanghai")

    private fun at(d: Int, h: Int, m: Int): Long {
        val c = Calendar.getInstance(tz)
        c.clear(); c.set(2026, Calendar.NOVEMBER, d, h, m, 0)
        return c.timeInMillis
    }

    private val now = at(20, 9, 30)
    private val sod = HomeBuckets.startOfToday(now, tz)

    @Test
    fun startOfTodayIsLocalMidnight() {
        assertEquals(at(20, 0, 0), sod)
    }

    @Test
    fun bucketsFollowHourTodayWeekEarlier() {
        assertEquals(B.HOUR, HomeBuckets.bucketOf(at(20, 9, 0), now, sod))       // 30 分钟前
        assertEquals(B.HOUR, HomeBuckets.bucketOf(at(20, 8, 30), now, sod))      // 刚好 1 小时
        assertEquals(B.TODAY, HomeBuckets.bucketOf(at(20, 8, 29), now, sod))
        assertEquals(B.TODAY, HomeBuckets.bucketOf(at(20, 0, 0), now, sod))
        assertEquals(B.WEEK, HomeBuckets.bucketOf(at(19, 23, 59), now, sod))     // 昨天
        assertEquals(B.WEEK, HomeBuckets.bucketOf(at(13, 9, 30), now, sod))      // 刚好 7 天
        assertEquals(B.EARLIER, HomeBuckets.bucketOf(at(13, 9, 29), now, sod))
        assertEquals(B.HOUR, HomeBuckets.bucketOf(now + 5000, now, sod))         // 时钟回拨
    }

    @Test
    fun justAfterMidnightLastNightFilesFallIntoHourNotToday() {
        val n = at(20, 0, 20)
        val s = HomeBuckets.startOfToday(n, tz)
        assertEquals(HomeBuckets.Bucket.HOUR, HomeBuckets.bucketOf(at(19, 23, 50), n, s))
        assertEquals(HomeBuckets.Bucket.WEEK, HomeBuckets.bucketOf(at(19, 22, 0), n, s))
    }

    @Test
    fun groupKeepsOrderSkipsEmptyAndEachItemOnce() {
        data class F(val n: String, val t: Long, val star: Boolean = false)
        val items = listOf(
            F("a", at(20, 9, 20)), F("b", at(20, 7, 0)), F("c", at(18, 10, 0)),
            F("d", at(1, 10, 0)), F("e", at(20, 9, 10), star = true)
        )
        val g = HomeBuckets.group(items, { it.t }, null, now, sod)
        assertEquals(listOf(HomeBuckets.Bucket.HOUR, HomeBuckets.Bucket.TODAY,
            HomeBuckets.Bucket.WEEK, HomeBuckets.Bucket.EARLIER), g.map { it.first })
        assertEquals(listOf("a", "e"), g[0].second.map { it.n })
        assertEquals(5, g.sumOf { it.second.size })

        val gs = HomeBuckets.group(items, { it.t }, { it.star }, now, sod)
        assertEquals(HomeBuckets.Bucket.STARRED, gs[0].first)
        assertEquals(listOf("e"), gs[0].second.map { it.n })
        assertEquals(listOf("a"), gs[1].second.map { it.n })
    }
}
