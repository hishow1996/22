package com.moye.pdfink

/**
 * PDF 源页面到旋转后分块画布的矩阵。矩阵坐标统一是：
 * tileX = mappedX - left，tileY = mappedY - top。
 * 与 Matrix / Canvas 无关，便于验证四个旋转角度的角点映射。
 */
object PdfTileTransform {
    fun normalizeRotation(rotation: Int): Int {
        val normalized = ((rotation % 360) + 360) % 360
        return (((normalized + 45) / 90) % 4) * 90
    }

    fun matrixValues(
        sourceWidth: Float,
        sourceHeight: Float,
        renderWidth: Int,
        tileLeft: Int,
        tileTop: Int,
        rotation: Int
    ): FloatArray {
        require(sourceWidth > 0f && sourceHeight > 0f && renderWidth > 0)
        val turn = normalizeRotation(rotation)
        val rotatedWidth =
            if (turn == 90 || turn == 270) sourceHeight else sourceWidth
        val scale = renderWidth.toFloat() / rotatedWidth
        val left = tileLeft.toFloat()
        val top = tileTop.toFloat()
        return when (turn) {
            90 -> floatArrayOf(
                0f, -scale, sourceHeight * scale - left,
                scale, 0f, -top,
                0f, 0f, 1f
            )
            180 -> floatArrayOf(
                -scale, 0f, sourceWidth * scale - left,
                0f, -scale, sourceHeight * scale - top,
                0f, 0f, 1f
            )
            270 -> floatArrayOf(
                0f, scale, -left,
                -scale, 0f, sourceWidth * scale - top,
                0f, 0f, 1f
            )
            else -> floatArrayOf(
                scale, 0f, -left,
                0f, scale, -top,
                0f, 0f, 1f
            )
        }
    }
}
