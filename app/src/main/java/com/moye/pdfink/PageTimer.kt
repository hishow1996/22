package com.moye.pdfink

import java.io.File

/**
 * v48：做题计时。按文件、按页累计停留时间。纯 JVM（只用 java.io），便于本地单测。
 *
 * - 切页、切后台、回前台都由调用方喂时间戳（单调时钟毫秒），这里只做累加；
 * - 一次连续停留最多记 [capMs]（默认 15 分钟）——手机放桌上忘了关，不会算成一小时；
 * - 切到后台 / 关文件调用 [pause]，回来调用 [resume]，期间不计时。
 */
class PageTimer(private val capMs: Long = 15 * 60_000L) {

    private val totals = HashMap<Int, Long>()
    private var activePage = -1
    private var lastPage = -1
    private var since = 0L

    /** 有新增时间、还没存盘。 */
    var dirty = false
        private set

    private fun settle(now: Long) {
        if (activePage >= 0) {
            val d = (now - since).coerceIn(0L, capMs)
            if (d > 0) {
                totals[activePage] = (totals[activePage] ?: 0L) + d
                dirty = true
            }
        }
        since = now
    }

    /** 开始（或切到）某一页计时。 */
    fun start(page: Int, now: Long) {
        settle(now)
        activePage = page
        lastPage = page
    }

    fun pause(now: Long) {
        settle(now)
        activePage = -1
    }

    fun resume(now: Long) {
        if (activePage < 0 && lastPage >= 0) {
            activePage = lastPage
            since = now
        }
    }

    fun reset() {
        totals.clear()
        dirty = true
        since = 0L
        // 正在计时的页从零重新开始：下一次 start/pause 的 settle 会按新的 since 算
    }

    /** 截至 now 的各页累计毫秒（含当前这一段）。 */
    fun snapshot(now: Long): Map<Int, Long> {
        settle(now)
        return HashMap(totals)
    }

    fun markSaved() { dirty = false }

    fun total(now: Long): Long = snapshot(now).values.sum()

    /** 用时最长的前 n 页（毫秒降序，同长按页号升序）。 */
    fun slowest(now: Long, n: Int): List<Pair<Int, Long>> =
        snapshot(now).entries
            .filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<Int, Long>> { it.value }.thenBy { it.key })
            .take(n).map { it.key to it.value }

    /** 「页:毫秒,页:毫秒」；解析失败的项直接丢。 */
    fun encode(): String = totals.entries.sortedBy { it.key }
        .joinToString(",") { "${it.key}:${it.value}" }

    fun load(text: String) {
        totals.clear()
        for (part in text.split(',')) {
            val kv = part.split(':')
            if (kv.size != 2) continue
            val p = kv[0].trim().toIntOrNull() ?: continue
            val ms = kv[1].trim().toLongOrNull() ?: continue
            if (p >= 0 && ms > 0) totals[p] = ms
        }
    }

    companion object {
        /** 45秒 / 3分12秒 / 1时05分。 */
        fun format(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return when {
                s < 60 -> "${s}秒"
                s < 3600 -> "${s / 60}分${"%02d".format(s % 60)}秒"
                else -> "${s / 3600}时${"%02d".format((s % 3600) / 60)}分"
            }
        }
    }
}

/** 每个文件一个小文本，放在 files/timing 下，文件名由内容指纹净化而来。 */
class TimingStore(private val dir: File) {

    private fun file(fp: String) =
        File(dir, fp.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80) + ".txt")

    fun load(fp: String): PageTimer {
        val t = PageTimer()
        if (fp.isEmpty()) return t
        try {
            val f = file(fp)
            if (f.isFile) t.load(f.readText())
        } catch (e: Exception) { /* 读不出就当没有 */ }
        return t
    }

    fun save(fp: String, timer: PageTimer) {
        if (fp.isEmpty()) return
        try {
            dir.mkdirs()
            val f = file(fp)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(timer.encode())
            if (!tmp.renameTo(f)) { f.writeText(timer.encode()); tmp.delete() }
            timer.markSaved()
        } catch (e: Exception) { /* 存不了不影响阅读 */ }
    }
}
