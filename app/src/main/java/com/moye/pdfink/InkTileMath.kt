package com.moye.pdfink

import kotlin.math.floor

/**
 * 笔迹分块层的纯数学部分，不依赖 android.*，可直接做 JVM 单测。
 */
object InkTileMath {
    /** 每块边长（像素）。足够小：写一笔只重传碰到的几块；足够大：块数不会多到拖慢绘制。 */
    const val TILE = 256

    /**
     * [start, end]（层坐标，像素）覆盖到的块下标范围，已钳到 [0, count-1]。
     * 无交集 / 非法输入返回空范围。恰好落在块边界上时按「触及」处理，多算一块不会出错。
     */
    fun range(start: Float, end: Float, count: Int): IntRange {
        if (count <= 0 || start.isNaN() || end.isNaN() || end < start) return IntRange.EMPTY
        val first = floor(start / TILE).toInt().coerceAtLeast(0)
        val last = floor(end / TILE).toInt().coerceAtMost(count - 1)
        return if (last < first) IntRange.EMPTY else first..last
    }

    /** 覆盖 [size] 像素所需的块数。 */
    fun count(size: Int): Int = if (size <= 0) 0 else (size + TILE - 1) / TILE

    /** 第 [index] 块的实际边长：边缘块可能不足 TILE。 */
    fun tileSize(index: Int, total: Int): Int =
        minOf(TILE, total - index * TILE).coerceAtLeast(0)
}
