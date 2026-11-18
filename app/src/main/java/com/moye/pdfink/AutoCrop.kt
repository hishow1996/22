package com.moye.pdfink

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * v46：自动裁白边。不改页面坐标系（笔迹 / 文字层 / 导出都按整页归一化坐标），
 * 只是在打开一页时把「视口」缩放平移到内容范围上，等价于隐藏四周空白。
 * 纯 JVM，便于本地单测。
 */
object AutoCrop {

    /**
     * 在小图上找内容外框，返回归一化 [左, 上, 右, 下]；
     * 空白页、内容几乎铺满整页（没什么可裁）都返回 null。
     *
     * 背景色取外圈像素里最多的那一档（兼容米黄纸 / 深色底），
     * 与背景相差超过 [tol] 的才算内容；一行 / 一列里至少 [minHits] 个内容点才算，
     * 免得扫描件上的孤立噪点撑大外框。
     */
    fun findBounds(px: IntArray, w: Int, h: Int, tol: Int = 36, minHits: Int = 2): FloatArray? {
        if (w < 8 || h < 8 || px.size < w * h) return null

        // 外圈 2 像素：按每通道 >>4 分档，取最多的一档，再对该档求平均色
        val hist = HashMap<Int, IntArray>()   // key -> [count, sumR, sumG, sumB]
        fun sample(x: Int, y: Int) {
            val c = px[y * w + x]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val a = hist.getOrPut(((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)) { IntArray(4) }
            a[0]++; a[1] += r; a[2] += g; a[3] += b
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) sample(x, y)
        }
        val top = hist.values.maxByOrNull { it[0] } ?: return null
        val br = top[1] / top[0]; val bg = top[2] / top[0]; val bb = top[3] / top[0]

        val rows = IntArray(h); val cols = IntArray(w)
        var any = false
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val d = max(
                abs(((c shr 16) and 0xFF) - br),
                max(abs(((c shr 8) and 0xFF) - bg), abs((c and 0xFF) - bb))
            )
            if (d > tol) { rows[y]++; cols[x]++; any = true }
        }
        if (!any) return null

        val y0 = rows.indexOfFirst { it >= minHits }
        val y1 = rows.indexOfLast { it >= minHits }
        val x0 = cols.indexOfFirst { it >= minHits }
        val x1 = cols.indexOfLast { it >= minHits }
        if (y0 < 0 || x0 < 0 || y1 < y0 || x1 < x0) return null

        val l = x0.toFloat() / w
        val t = y0.toFloat() / h
        val r = (x1 + 1).toFloat() / w
        val b = (y1 + 1).toFloat() / h
        // 内容太小（多半是一页只有页码 / 水印）不裁；几乎铺满整页也不裁
        if ((r - l) < 0.15f || (b - t) < 0.03f) return null
        if (l < 0.03f && t < 0.03f && r > 0.97f && b > 0.97f) return null
        return floatArrayOf(l, t, r, b)
    }

    /** 缩放倍率 + 平移（屏幕像素，ZoomHost 里 tx / ty 的口径）。 */
    data class Plan(val scale: Float, val tx: Float, val ty: Float)

    /**
     * 让内容宽度（加两侧留白）正好撑满视口宽，内容水平居中、内容上缘（加留白）贴视口顶。
     * 缩放以视口中心为锚点：displayed(x) = host/2 + (x - host/2) * s + t。
     * 倍率不足 [minGain] 视为没什么可裁，返回 null；最多放大到 [maxScale]。
     * 最终 tx / ty 仍由 ZoomHost 按可移动范围夹一次。
     */
    fun plan(
        b: FloatArray,
        hostW: Int, hostH: Int,
        frameLeft: Float, frameTop: Float, frameW: Int, frameH: Int,
        padFrac: Float = 0.02f, maxScale: Float = 2.5f, minGain: Float = 1.08f
    ): Plan? {
        if (b.size < 4 || hostW <= 0 || hostH <= 0 || frameW <= 0 || frameH <= 0) return null
        val contentW = (b[2] - b[0]) * frameW
        if (contentW <= 0f) return null
        val pad = padFrac * hostW
        val s = min(hostW / (contentW + 2f * pad), maxScale)
        if (!s.isFinite() || s < minGain) return null

        val cx = frameLeft + (b[0] + b[2]) * 0.5f * frameW
        val tx = -(cx - hostW * 0.5f) * s
        val topY = frameTop + b[1] * frameH - pad
        val ty = -(hostH * 0.5f + (topY - hostH * 0.5f) * s)
        return Plan(s, tx, ty)
    }
}
