package com.moye.pdfink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * v46：「按住拉直 / 规整图形」的识别。纯 JVM。
 * 输入是一笔自由笔迹（归一化 x,y 交错），输出直线或椭圆；看不出来就返回 null，
 * 宁可不吸附也不要把手写的字吸成形状。
 */
object ShapeSnap {

    sealed class Result {
        /** 归一化起点 / 终点。 */
        class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : Result()
        /** 归一化外接矩形（与 Shapes.OVAL 的两个角点同口径）。 */
        class Oval(val l: Float, val t: Float, val r: Float, val b: Float) : Result()
    }

    /** 太短的不识别（像素）。 */
    const val MIN_SIZE_PX = 40f

    fun detect(pts: FloatArray, w: Float, h: Float): Result? {
        val n = pts.size / 2
        if (n < 8 || w <= 0f || h <= 0f) return null
        val xs = FloatArray(n) { pts[2 * it] * w }
        val ys = FloatArray(n) { pts[2 * it + 1] * h }

        var pathLen = 0f
        var minX = xs[0]; var maxX = xs[0]; var minY = ys[0]; var maxY = ys[0]
        for (i in 1 until n) {
            pathLen += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
            minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
        }
        if (pathLen < MIN_SIZE_PX) return null
        val bw = maxX - minX
        val bh = maxY - minY
        val chord = hypot(xs[n - 1] - xs[0], ys[n - 1] - ys[0])

        // ---- 直线：弦长接近路径长，且所有点离弦都很近 ----
        if (chord >= MIN_SIZE_PX && chord / pathLen >= 0.88f) {
            val dx = xs[n - 1] - xs[0]
            val dy = ys[n - 1] - ys[0]
            var maxDev = 0f
            for (i in 0 until n) {
                val dev = abs(dx * (ys[i] - ys[0]) - dy * (xs[i] - xs[0])) / chord
                if (dev > maxDev) maxDev = dev
            }
            if (maxDev <= 0.07f * chord) {
                var x0 = xs[0]; var y0 = ys[0]; var x1 = xs[n - 1]; var y1 = ys[n - 1]
                // 接近水平 / 竖直（±4°）就摆正，保持中点不动
                val ang = Math.toDegrees(atan2(abs(dy).toDouble(), abs(dx).toDouble()))
                if (ang <= 4.0) {
                    val cy = (y0 + y1) / 2f; y0 = cy; y1 = cy
                } else if (ang >= 86.0) {
                    val cx = (x0 + x1) / 2f; x0 = cx; x1 = cx
                }
                return Result.Line(x0 / w, y0 / h, x1 / w, y1 / h)
            }
            return null
        }

        // ---- 椭圆：基本闭合、两个方向都够大、点都贴着椭圆、绕了接近一整圈 ----
        if (bw >= MIN_SIZE_PX && bh >= MIN_SIZE_PX &&
            chord <= 0.25f * max(bw, bh)
        ) {
            val cx = (minX + maxX) / 2f
            val cy = (minY + maxY) / 2f
            val a = bw / 2f
            val b = bh / 2f
            var sumDev = 0f
            var maxDev = 0f
            for (i in 0 until n) {
                val ux = (xs[i] - cx) / a
                val uy = (ys[i] - cy) / b
                val dev = abs(sqrt(ux * ux + uy * uy) - 1f)
                sumDev += dev
                if (dev > maxDev) maxDev = dev
            }
            if (sumDev / n > 0.18f || maxDev > 0.35f) return null

            // 绕圈角度：逐点展开，必须接近 360° 且基本单向（不是来回描）
            var sweep = 0.0
            var prev = atan2(((ys[0] - cy) / b).toDouble(), ((xs[0] - cx) / a).toDouble())
            for (i in 1 until n) {
                val cur = atan2(((ys[i] - cy) / b).toDouble(), ((xs[i] - cx) / a).toDouble())
                var d = cur - prev
                while (d > PI) d -= 2 * PI
                while (d < -PI) d += 2 * PI
                sweep += d
                prev = cur
            }
            if (abs(sweep) < 2 * PI * 0.85) return null

            var l = minX; var t = minY; var r = maxX; var bt = maxY
            // 长宽相差不到 12% 当正圆，取均值，中心不动
            if (abs(bw - bh) <= 0.12f * max(bw, bh)) {
                val s = (bw + bh) / 2f
                l = cx - s / 2f; r = cx + s / 2f; t = cy - s / 2f; bt = cy + s / 2f
            }
            return Result.Oval(l / w, t / h, r / w, bt / h)
        }
        return null
    }
}
