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

    /**
     * 一个闭合的圈（归一化外接矩形）→ 刚好框住圈里文字的椭圆外接矩形 l,t,r,b。
     * 圈里没有字、或者框出来的大小和手画的差太多（多半不是圈字），返回 null。
     */
    fun pickOval(
        l: PageTextLayout, ol: Float, ot: Float, or: Float, ob: Float, w: Float, h: Float
    ): FloatArray? {
        val cx = (ol + or) * 0.5f
        val cy = (ot + ob) * 0.5f
        val a = (or - ol) * 0.5f
        val b = (ob - ot) * 0.5f
        if (a <= 0f || b <= 0f) return null
        var n = 0
        var ul = 1f; var ut = 1f; var ur = 0f; var ub = 0f
        var hSum = 0f
        for (i in 0 until l.length) {
            if (!solid(l, i)) continue
            val x0 = l.boxes[4 * i]; val y0 = l.boxes[4 * i + 1]
            val x1 = l.boxes[4 * i + 2]; val y1 = l.boxes[4 * i + 3]
            val dx = ((x0 + x1) * 0.5f - cx) / a
            val dy = ((y0 + y1) * 0.5f - cy) / b
            if (dx * dx + dy * dy > 1.1f) continue
            n++
            ul = min(ul, x0); ut = min(ut, y0); ur = max(ur, x1); ub = max(ub, y1)
            hSum += y1 - y0
        }
        if (n == 0) return null
        val lh = hSum / n                              // 平均字高（归一化 y）
        val padY = 0.35f * lh
        val padX = padY * h / w                        // 同样的像素距离换到 x 方向
        val k = 1.4142f                                // 椭圆要罩住矩形，半轴放大 √2
        val na = (ur - ul) * 0.5f * k + padX
        val nb = (ub - ut) * 0.5f * k + padY
        val ncx = (ul + ur) * 0.5f
        val ncy = (ut + ub) * 0.5f
        val ratio = (na * nb) / (a * b)
        if (ratio > 2.5f || ratio < 0.35f) return null
        return floatArrayOf(
            (ncx - na).coerceAtLeast(0f), (ncy - nb).coerceAtLeast(0f),
            (ncx + na).coerceAtMost(1f), (ncy + nb).coerceAtMost(1f)
        )
    }

    /**
     * v49：一个闭合的长方形（归一化外接矩形）→ 刚好框住框里文字的长方形外接矩形 l,t,r,b。
     * 和 [pickOval] 同样的口径：框里没有字、或者框出来的大小和手画的差太多，返回 null。
     * 判框里的字时比椭圆宽松：字的中心落在手画矩形（四周放宽 6%）内就算。
     */
    fun pickRect(
        l: PageTextLayout, rl: Float, rt: Float, rr: Float, rb: Float, w: Float, h: Float
    ): FloatArray? {
        val a = rr - rl
        val b = rb - rt
        if (a <= 0f || b <= 0f) return null
        val sx = 0.06f * a
        val sy = 0.06f * b
        var n = 0
        var ul = 1f; var ut = 1f; var ur = 0f; var ub = 0f
        var hSum = 0f
        for (i in 0 until l.length) {
            if (!solid(l, i)) continue
            val x0 = l.boxes[4 * i]; val y0 = l.boxes[4 * i + 1]
            val x1 = l.boxes[4 * i + 2]; val y1 = l.boxes[4 * i + 3]
            val cx = (x0 + x1) * 0.5f
            val cy = (y0 + y1) * 0.5f
            if (cx < rl - sx || cx > rr + sx || cy < rt - sy || cy > rb + sy) continue
            n++
            ul = min(ul, x0); ut = min(ut, y0); ur = max(ur, x1); ub = max(ub, y1)
            hSum += y1 - y0
        }
        if (n == 0) return null
        val lh = hSum / n
        val padY = 0.35f * lh
        val padX = padY * h / w
        val nl = ul - padX; val nr = ur + padX
        val nt = ut - padY; val nb = ub + padY
        val ratio = ((nr - nl) * (nb - nt)) / (a * b)
        if (ratio > 2.5f || ratio < 0.35f) return null
        return floatArrayOf(nl.coerceAtLeast(0f), nt.coerceAtLeast(0f), nr.coerceAtMost(1f), nb.coerceAtMost(1f))
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
