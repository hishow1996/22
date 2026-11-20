package com.moye.pdfink

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * v48：文字吸附。纯 JVM。
 *
 * 手指写得再歪，也要落到文字上：笔迹抬笔时，如果是一条近似水平的直线或一个闭合的圈，
 * 又正好压在有文字层的页面文字上，就改成贴着文字的版本——
 *  - 笔划线 → 贴在字下面的下划线（或字中间的删除线）；
 *  - 荧光笔刷一道 → 这一行被刷到的字的整块高亮；
 *  - 圈 → 刚好框住被圈字的椭圆。
 * 宁可不吸附：不在文字上、对不上文字、尺寸差太多，都返回 null，笔迹保持原样。
 */
object TextSnap {

    class TLine(val first: Int, val last: Int, val l: Float, val t: Float, val r: Float, val b: Float) {
        val h: Float get() = b - t
    }

    /** 下划线 / 删除线 / 高亮：rect 为归一化 x0,y0,x1,y1（该行被选字的外框）。 */
    class Pick(val shape: Int, val start: Int, val end: Int, val rect: FloatArray)

    /** 把文字层按行分组（只认横排；页面转了 90° 的由调用方不吸附）。 */
    fun lines(l: PageTextLayout): List<TLine> {
        val out = ArrayList<TLine>()
        var has = false
        var f = 0; var lst = 0
        var L = 0f; var T = 0f; var R = 0f; var B = 0f
        fun flush() { if (has) out.add(TLine(f, lst, L, T, R, B)); has = false }
        for (i in 0 until l.length) {
            if (!l.real[i]) { if (l.text[i] == '\n') flush(); continue }
            if (l.text[i].isWhitespace()) { if (has) lst = i; continue }
            val x0 = l.boxes[4 * i]; val y0 = l.boxes[4 * i + 1]
            val x1 = l.boxes[4 * i + 2]; val y1 = l.boxes[4 * i + 3]
            if (has) {
                val yc = (y0 + y1) * 0.5f
                val cyc = (T + B) * 0.5f
                if (abs(yc - cyc) > 0.6f * max(B - T, y1 - y0)) flush()
            }
            if (!has) { f = i; L = x0; T = y0; R = x1; B = y1; has = true }
            else { L = min(L, x0); T = min(T, y0); R = max(R, x1); B = max(B, y1) }
            lst = i
        }
        flush()
        return out
    }

    private fun solid(l: PageTextLayout, i: Int) = l.real[i] && !l.text[i].isWhitespace()

    /**
     * 一条近似水平的直线（归一化端点）→ 下划线 / 删除线 / 高亮。
     * highlighter=true 时按「刷过这一行」认，否则按「画在字下面 / 字中间」认。
     */
    fun pickLine(
        l: PageTextLayout, x0: Float, y0: Float, x1: Float, y1: Float,
        w: Float, h: Float, highlighter: Boolean
    ): Pick? {
        val dx = (x1 - x0) * w
        val dy = (y1 - y0) * h
        if (abs(dx) < 1f || abs(dy) > 0.32f * abs(dx)) return null     // 超过约 18° 不当作贴字线
        val xa = min(x0, x1); val xb = max(x0, x1)
        val y = (y0 + y1) * 0.5f
        var best: TLine? = null
        var bestScore = Float.MAX_VALUE
        for (ln in lines(l)) {
            if (ln.r <= xa || ln.l >= xb) continue
            val lh = ln.h
            val score: Float
            if (highlighter) {
                if (y < ln.t - 0.3f * lh || y > ln.b + 0.3f * lh) continue
                score = abs(y - (ln.t + ln.b) * 0.5f)
            } else {
                if (y < ln.t + 0.3f * lh || y > ln.b + 0.9f * lh) continue
                score = abs(y - (ln.b + 0.05f * lh))
            }
            if (score < bestScore) { bestScore = score; best = ln }
        }
        val ln = best ?: return null
        var s = -1; var e = -1
        var L = 0f; var R = 0f
        for (i in ln.first..ln.last) {
            if (!solid(l, i)) continue
            val cx0 = l.boxes[4 * i]; val cx1 = l.boxes[4 * i + 2]
            val ov = min(cx1, xb) - max(cx0, xa)
            if (ov < 0.4f * (cx1 - cx0)) continue
            if (s < 0) { s = i; L = cx0 }
            e = i + 1; R = cx1
        }
        if (s < 0) return null
        val shape = when {
            highlighter -> Shapes.TEXT_HL
            y < ln.b - 0.25f * ln.h -> Shapes.TEXT_STRIKE
            else -> Shapes.TEXT_UL
        }
        return Pick(shape, s, e, floatArrayOf(L, ln.t, R, ln.b))
    }

    /** 圈住的字：下标 [first, last)、贴字外框 rect = l,t,r,b（归一化，已留好边距），lh = 平均字高（归一化 y）。 */
    class Enclosure(val first: Int, val last: Int, val rect: FloatArray, val lh: Float)

    /**
     * v51：圈字，按「这一笔画出的圈」精确选字，不放大、不多圈。
     *
     * 圈（笔迹点，归一化 x,y 交错，首尾自动连起来）里盖住字框 ≥ 50% 的字才算被圈到
     * （字框里取 4×3 个点，用非零环绕数判点在圈内），被圈边线切掉一半以上的邻字不算。
     *  - 最多跨 3 行；每行取被圈字的首尾，中间的字一并算；
     *  - 所有行的外框合起来之后，里面不能再有没被圈到的字（否则圈得有歧义，返回 null 让调用方退回手画的形状）；
     *  - 外框四周只留一点边距（上下 0.13 字高、左右 0.08 字高），框紧紧贴着字，不会盖住邻行 / 邻字。
     */
    fun pickEnclosure(l: PageTextLayout, pts: FloatArray, w: Float, h: Float): Enclosure? {
        val n0 = pts.size / 2
        if (n0 < 4 || w <= 0f || h <= 0f) return null
        // 抽稀到 ≤ 128 点，算得快
        val step = max(1, n0 / 128)
        val px = ArrayList<Float>(); val py = ArrayList<Float>()
        var i0 = 0
        while (i0 < n0) { px.add(pts[2 * i0]); py.add(pts[2 * i0 + 1]); i0 += step }
        val n = px.size
        var bl = 1f; var bt = 1f; var br = 0f; var bb = 0f
        for (i in 0 until n) { bl = min(bl, px[i]); br = max(br, px[i]); bt = min(bt, py[i]); bb = max(bb, py[i]) }
        if (br <= bl || bb <= bt) return null

        fun inside(x: Float, y: Float): Boolean {
            var wn = 0
            for (i in 0 until n) {
                val j = if (i + 1 == n) 0 else i + 1
                val y0 = py[i]; val y1 = py[j]
                if ((y0 <= y) != (y1 <= y)) {
                    val t = (y - y0) / (y1 - y0)
                    val xc = px[i] + t * (px[j] - px[i])
                    if (xc > x) { if (y1 > y0) wn++ else wn-- }
                }
            }
            return wn != 0
        }

        val chosen = BooleanArray(l.length)
        var any = false
        for (i in 0 until l.length) {
            if (!solid(l, i)) continue
            val x0 = l.boxes[4 * i]; val y0 = l.boxes[4 * i + 1]
            val x1 = l.boxes[4 * i + 2]; val y1 = l.boxes[4 * i + 3]
            if (x1 < bl || x0 > br || y1 < bt || y0 > bb) continue
            var hit = 0
            for (gy in 0 until 3) for (gx in 0 until 4) {
                val sx = x0 + (x1 - x0) * (gx + 0.5f) / 4f
                val sy = y0 + (y1 - y0) * (gy + 0.5f) / 3f
                if (inside(sx, sy)) hit++
            }
            if (hit >= 6) { chosen[i] = true; any = true }
        }
        if (!any) return null

        val lns = lines(l)
        var ul = 1f; var ut = 1f; var ur = 0f; var ub = 0f
        var hSum = 0f; var hN = 0
        var first = Int.MAX_VALUE; var last = -1
        var used = 0
        val ranges = ArrayList<IntArray>()
        for (ln in lns) {
            var s = -1; var e = -1
            for (i in ln.first..ln.last) if (chosen[i]) { if (s < 0) s = i; e = i }
            if (s < 0) continue
            if (++used > 3) return null
            ranges.add(intArrayOf(s, e))
            first = min(first, s); last = max(last, e)
            for (i in s..e) {
                if (!solid(l, i)) continue
                ul = min(ul, l.boxes[4 * i]); ut = min(ut, l.boxes[4 * i + 1])
                ur = max(ur, l.boxes[4 * i + 2]); ub = max(ub, l.boxes[4 * i + 3])
                hSum += l.boxes[4 * i + 3] - l.boxes[4 * i + 1]; hN++
            }
        }
        if (hN == 0 || ur <= ul) return null
        // 外框里不能有没被圈到的字（多行时首行行尾 / 末行行首的半截最容易这样）
        for (i in 0 until l.length) {
            if (!solid(l, i)) continue
            if (ranges.any { i >= it[0] && i <= it[1] }) continue
            val x0 = l.boxes[4 * i]; val y0 = l.boxes[4 * i + 1]
            val x1 = l.boxes[4 * i + 2]; val y1 = l.boxes[4 * i + 3]
            val ox = min(x1, ur) - max(x0, ul); val oy = min(y1, ub) - max(y0, ut)
            if (ox > 0f && oy > 0f && ox * oy >= 0.5f * (x1 - x0) * (y1 - y0)) return null
        }
        val lh = hSum / hN
        val padY = 0.13f * lh
        val padX = 0.08f * lh * h / w
        return Enclosure(
            first, last + 1,
            floatArrayOf((ul - padX).coerceAtLeast(0f), (ut - padY).coerceAtLeast(0f),
                (ur + padX).coerceAtMost(1f), (ub + padY).coerceAtMost(1f)),
            lh
        )
    }

    /**
     * 圆角矩形折线（归一化 x,y 交错，闭合不重复首点），圆角半径 radius 给的是「字高」的倍数换算后的像素。
     * 框字用：比椭圆紧得多，不会盖到邻字。
     */
    fun roundedRect(rect: FloatArray, radiusPx: Float, w: Float, h: Float): FloatArray {
        val l = rect[0] * w; val t = rect[1] * h; val r = rect[2] * w; val b = rect[3] * h
        val rad = min(radiusPx, min((r - l), (b - t)) / 2f)
        val out = ArrayList<Float>()
        val seg = 6
        fun arc(cx: Float, cy: Float, a0: Double) {
            for (k in 0..seg) {
                val a = a0 + Math.PI / 2 * k / seg
                out.add((cx + rad * Math.cos(a).toFloat()) / w); out.add((cy + rad * Math.sin(a).toFloat()) / h)
            }
        }
        arc(r - rad, t + rad, -Math.PI / 2)          // 右上角：从上边到右边
        arc(r - rad, b - rad, 0.0)                    // 右下
        arc(l + rad, b - rad, Math.PI / 2)            // 左下
        arc(l + rad, t + rad, Math.PI)                // 左上
        return out.toFloatArray()
    }

    /** v49：归一化外接矩形里有没有文字（至少一个字的中心在里面）。多边形压在文字上时用。 */
    fun hasTextIn(l: PageTextLayout, rl: Float, rt: Float, rr: Float, rb: Float): Boolean {
        for (i in 0 until l.length) {
            if (!solid(l, i)) continue
            val cx = (l.boxes[4 * i] + l.boxes[4 * i + 2]) * 0.5f
            val cy = (l.boxes[4 * i + 1] + l.boxes[4 * i + 3]) * 0.5f
            if (cx in rl..rr && cy in rt..rb) return true
        }
        return false
    }

    /** 被选字的原文，供批注汇总 / 导出用。 */
    fun textOf(l: PageTextLayout, start: Int, end: Int): String? =
        l.text.substring(start.coerceIn(0, l.length), end.coerceIn(0, l.length))
            .replace('\n', ' ').trim().takeIf { it.isNotEmpty() }?.take(300)
}
