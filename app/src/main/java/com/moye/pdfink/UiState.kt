package com.moye.pdfink

object Tools {
    const val PEN = 0
    const val HIGHLIGHT = 1
    const val SHAPE = 2
    const val ERASER = 3
    const val LASSO = 4
    const val NOTE = 5
}

object Layers {
    const val ANSWER = 0
    const val CORRECTION = 1
}

class UiState {
    @Volatile var spread = false
    @Volatile var tint = PageFilter.WHITE
    @Volatile var brightness = 0f
    // 默认浏览（看题翻页），点按钮切到做题做笔记
    @Volatile var browse = true
    @Volatile var stylusOnly = false
    @Volatile var palmGuard = true
    @Volatile var edgeFlip = true
    @Volatile var activeLayer = Layers.ANSWER
    @Volatile var showCorrection = true

    // v4
    @Volatile var pageRotation = 0
    @Volatile var volumeFlip = true
    @Volatile var keepAwake = true
    @Volatile var followSystemDark = false
    @Volatile var tempEraserOn = true
    // v5：竖屏时对开自动降级，用户设置原样保留，转回横屏自动恢复
    @Volatile var landscape = true
    // v7：横屏保留用户选择；竖屏统一按宽度适配，超高页面可纵向平移
    // displayMode 继续保存横屏偏好，旋转回横屏时恢复原选择
    @Volatile var displayMode = DisplayMode.WIDTH

    val effectiveMode: Int
        get() = DisplayMode.effective(displayMode, landscape)

    /** 双页并排与「题目 / 草稿对开」不能叠三层，双页优先。 */
    val effectiveSpread: Boolean
        get() = spread && landscape && effectiveMode != DisplayMode.DOUBLE

    val visibleLayers: Set<Int>
        get() = if (showCorrection) setOf(Layers.ANSWER, Layers.CORRECTION)
        else setOf(Layers.ANSWER)
}
