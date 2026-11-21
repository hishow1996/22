package com.moye.pdfink

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * v52：写完自动缩小。纯 JVM。
 *
 * 手机屏幕小、手指粗，要写得清楚就得写大，可大字占页面。打开后，在页面上直接写大字，
 * 停笔约 1 秒，这一段（连着写的几笔）按比例缩小，贴着起点放；缩小后接着在它后面写下一段。
 * 一步可撤销（撤销回到缩小前的大字）。
 *
 * 缩小的锚点 = 这一段外框的「左边中点」：缩完仍落在你写的位置、行位置不漂。
 * 笔宽按 √比例 变细：字缩成一半、线不能也缩成一半，否则太细看不清；也不能不变，否则糊成一团。
 */
object LineShrink {
    const val OFF = 0

    /**
     * v53：缩小比例由「档位」改为直接存百分比，可在 [MIN_PCT, MAX_PCT] 内按 [STEP_PCT] 调（滑块），
     * 0 表示关。旧版存的是 1/2/3 档（60/50/40%），由 [fromLegacyLevel] 迁移。
     */
    const val MIN_PCT = 30
    const val MAX_PCT = 90
    const val STEP_PCT = 5
    const val DEFAULT_PCT = 50
    /** 常用快捷值（旧三档）。 */
    val PRESETS = intArrayOf(60, 50, 40)

    /** 停笔多久后缩小（毫秒）。 */
    const val IDLE_MS = 1000L
    /** 这一段外框高度的范围（dp）：小于下限当批注 / 划线不动；大于上限当画图不动。 */
    const val MIN_H_DP = 24f
    const val MAX_H_DP = 130f

    /** 把任意值规整成合法百分比：<= 0 → 关；否则夹到 [MIN, MAX] 并对齐到步长。 */
    fun clamp(pct: Int): Int {
        if (pct <= 0) return OFF
        val c = pct.coerceIn(MIN_PCT, MAX_PCT)
        return (((c + STEP_PCT / 2) / STEP_PCT) * STEP_PCT).coerceIn(MIN_PCT, MAX_PCT)
    }

    /** 百分比 → 缩小到原来的比例；关返回 1。 */
    fun frac(pct: Int): Float = if (clamp(pct) == OFF) 1f else clamp(pct) / 100f

    /** 旧版档位（0 关 / 1→60% / 2→50% / 3→40%）→ 百分比。 */
    fun fromLegacyLevel(level: Int): Int = when (level) {
        1 -> 60
        2 -> 50
        3 -> 40
        else -> OFF
    }

    /** 缩小方案：比例 s，锚点 (ax, ay)（归一化）。 */
    class Plan(val s: Float, val ax: Float, val ay: Float)

    /**
     * 这一段（strokes，每笔归一化 x,y 交错）要不要缩、怎么缩。不缩返回 null：
     *  - 关（0%）；没有点；
     *  - 外框高不在 [MIN_H_DP, MAX_H_DP]（页面像素 / dp），或者外框宽超过页宽 80%（多半在画图）；
     *  - 只有一笔、而且这一笔本身是个图形 / 直线（圈、框、划线）——那是标注不是写字。
     */
    fun plan(strokes: List<FloatArray>, w: Float, h: Float, density: Float, pct: Int): Plan? {
        if (clamp(pct) == OFF || strokes.isEmpty() || w <= 0f || h <= 0f) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var n = 0
        for (s in strokes) {
            var i = 0
            while (i + 1 < s.size) {
                l = min(l, s[i]); r = max(r, s[i])
                t = min(t, s[i + 1]); b = max(b, s[i + 1])
                i += 2; n++
            }
        }
        if (n < 4) return null
        val hPx = (b - t) * h
        val wPx = (r - l) * w
        if (hPx < MIN_H_DP * density || hPx > MAX_H_DP * density) return null
        if (wPx > 0.8f * w) return null
        if (strokes.size == 1 && ShapeSnap.detect(strokes[0], w, h) != null) return null
        return Plan(frac(pct), l, (t + b) / 2f)
    }

    /** 把点按方案缩小（在像素空间等比，再换回归一化，所以横竖比例不变）。 */
    fun scale(pts: FloatArray, p: Plan, w: Float, h: Float): FloatArray {
        val out = FloatArray(pts.size)
        var i = 0
        while (i + 1 < pts.size) {
            out[i] = p.ax + (pts[i] - p.ax) * p.s
            out[i + 1] = p.ay + (pts[i + 1] - p.ay) * p.s
            i += 2
        }
        return out
    }

    fun scaledWidth(width: Float, s: Float): Float = width * sqrt(s)
}
