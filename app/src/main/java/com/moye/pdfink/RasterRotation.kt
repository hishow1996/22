package com.moye.pdfink

/** 旋转后画布上的矩形，反算回未旋转画布上的轴对齐矩形（纯 JVM）。 */
data class SrcRect(
    val canvasW: Int, val canvasH: Int,
    val left: Int, val top: Int, val width: Int, val height: Int
)

object RasterRotation {
    fun normalize(rotation: Int): Int = when (((rotation % 360) + 360) % 360) {
        90 -> 90; 180 -> 180; 270 -> 270; else -> 0
    }

    /**
     * 把旋转后画布 (lw,lh) 上的块 (left,top,tw,th) 映射回源画布。
     * 输出位图经对应角度旋转后，尺寸正好回到 tw×th。
     */
    fun inverse(
        lw: Int, lh: Int, left: Int, top: Int, tw: Int, th: Int, rotation: Int
    ): SrcRect = when (normalize(rotation)) {
        90 -> SrcRect(lh, lw, top, lw - left - tw, th, tw)
        270 -> SrcRect(lh, lw, lh - top - th, left, th, tw)
        180 -> SrcRect(lw, lh, lw - left - tw, lh - top - th, tw, th)
        else -> SrcRect(lw, lh, left, top, tw, th)
    }
}
