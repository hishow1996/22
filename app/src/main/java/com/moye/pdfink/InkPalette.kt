package com.moye.pdfink

/**
 * 笔迹配色与坐标旋转的纯逻辑核心，不依赖任何 Android 类。
 * 屏幕渲染与离屏导出共用它，保证「所见即所得」。
 */
object InkPalette {
    const val WHITE = 0
    const val WARM = 1
    const val NIGHT = 2

    /** 与 PageFilter 的夜间矩阵完全相同的反相变换，笔迹才不会在暗底上消失。 */
    fun mapColor(argb: Int, mode: Int): Int {
        if (mode != NIGHT) return argb
        val a = (argb ushr 24) and 0xff
        val r = (argb ushr 16) and 0xff
        val g = (argb ushr 8) and 0xff
        val b = argb and 0xff
        val nr = (226f - 0.88f * r).toInt().coerceIn(0, 255)
        val ng = (220f - 0.88f * g).toInt().coerceIn(0, 255)
        val nb = (210f - 0.88f * b).toInt().coerceIn(0, 255)
        return (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    /**
     * 页面旋转时把已有笔迹整体迁移到新坐标系，笔迹与题目始终贴合。
     * 归一化坐标顺时针 90 度映射为（1-y, x）。
     */
    fun rotatePts(pts: FloatArray, degrees: Int): FloatArray {
        val rot = ((degrees % 360) + 360) % 360
        if (rot == 0) return pts
        val out = FloatArray(pts.size)
        var i = 0
        while (i + 1 < pts.size) {
            val x = pts[i]
            val y = pts[i + 1]
            val nx: Float
            val ny: Float
            when (rot) {
                90 -> { nx = 1f - y; ny = x }
                180 -> { nx = 1f - x; ny = 1f - y }
                270 -> { nx = y; ny = 1f - x }
                else -> { nx = x; ny = y }
            }
            out[i] = nx
            out[i + 1] = ny
            i += 2
        }
        return out
    }
}
