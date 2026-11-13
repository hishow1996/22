package com.moye.pdfink

import kotlin.math.roundToInt

/**
 * 竖屏连续滚动（像 WPS / Microsoft 365：页页相连、中间一条细缝）用到的纯计算，
 * 不依赖 Android 类，便于单测。
 */
object ContinuousMath {

    /** 没读到页面比例时先用的估计值（A4 竖页）。 */
    const val DEFAULT_RATIO = 0.7071f

    /**
     * 连续滚动里一页占的高度：页宽 = 列表宽 - 两侧留白，高度按页面比例，
     * 取整方式与 PageContainer 完全一致（四舍五入），保证列表项刚好装下整页。
     * 宽度或比例无效时返回 -1，调用方退回 match_parent。
     */
    fun itemHeight(listWidth: Int, sideInsetPx: Int, ratio: Float, zoom: Float = 1f): Int {
        if (listWidth <= 0 || !ratio.isFinite() || ratio <= 0f) return -1
        val availW = (listWidth - sideInsetPx * 2).coerceAtLeast(1)
        val base = (availW / ratio).roundToInt().coerceAtLeast(1)
        val z = if (zoom.isFinite() && zoom > 0f) zoom else 1f
        return (base * z).roundToInt().coerceAtLeast(1)
    }

    /** 页间细缝高度（dp）。 */
    const val GAP_DP = 6

    /**
     * 以焦点为锚点缩放整列时，横向平移量。ZoomHost 以自身中心为缩放原点：
     * 页面左边显示位置 = hostW/2 + (left - hostW/2)·scale + tx。
     * 保证焦点下的那一点在缩放前后停在手指下面。
     */
    fun anchoredTx(
        hostW: Float, pageLeft: Float, oldScale: Float, newScale: Float,
        oldTx: Float, focusX: Float
    ): Float {
        if (oldScale <= 0f) return oldTx
        val c = hostW * 0.5f
        val oldLeft = c + (pageLeft - c) * oldScale + oldTx
        val pageX = (focusX - oldLeft) / oldScale
        return focusX - pageX * newScale - c - (pageLeft - c) * newScale
    }

    /**
     * 当前页 = 盖住列表垂直中线的那一页。tops / bottoms 是各可见列表项相对列表顶
     * 的上下边（不含页间缝），gap 是页间缝高度。返回下标，没有命中返回 -1。
     * 从后往前找：中线碰到某页 top 时立即归该页；落在页间缝里（没有页 top 命中）
     * 才由上一页的 gap 扩展区接住，保证任何位置都有当前页。
     */
    fun indexAtCenter(tops: IntArray, bottoms: IntArray, center: Int, gap: Int): Int {
        val n = minOf(tops.size, bottoms.size)
        for (i in n - 1 downTo 0) {
            if (tops[i] <= center && bottoms[i] + gap > center) return i
        }
        return -1
    }
}
