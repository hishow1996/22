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
    /** v47：手指书写偏移档位 0 关 / 1 小 / 2 中 / 3 大，见 [FingerOffset]。 */
    @Volatile var fingerOffset = FingerOffset.OFF
    // v43：压感档位 0 关 / 1 弱 / 2 标准 / 3 强，只对触控笔生效
    @Volatile var pressureLevel = PressureCurve.DEFAULT
    @Volatile var edgeFlip = true
    @Volatile var activeLayer = Layers.ANSWER
    @Volatile var showCorrection = true

    // v4
    @Volatile var pageRotation = 0
    @Volatile var volumeFlip = true
    @Volatile var keepAwake = true
    @Volatile var followSystemDark = false
    /** v46：打开一页时自动把视口对准内容、隐藏四周空白（不改页面坐标）。 */
    @Volatile var autoCrop = false
    /** v46：按住拉直 / 规整图形，默认开。 */
    @Volatile var holdSnap = true
    @Volatile var textSnap = true
    /** v52：不压在文字上 / 没有文字层的页面，抬笔也把圈 / 框 / 线规整成图形，默认关。 */
    @Volatile var anySnap = false
    @Volatile var tempEraserOn = true
    // v5：竖屏时对开自动降级，用户设置原样保留，转回横屏自动恢复
    @Volatile var landscape = true
    // v7/v18：横屏保留 FIT / WIDTH / DOUBLE；竖屏支持 FIT / WIDTH，默认 WIDTH
    // displayMode 跨方向保留，竖屏下 DOUBLE 自动降级为 WIDTH
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
