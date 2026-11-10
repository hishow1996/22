package com.moye.pdfink

import androidx.lifecycle.ViewModel

/**
 * 配置变化时不重建的 UI 状态权威。MainActivity 只保留 View 与控制器逻辑。
 */
class MainViewModel : ViewModel() {

    val state = UiState()
    val history = InkHistory()

    private var sessionRepo: SessionRepository? = null
    fun attach(r: SessionRepository) { sessionRepo = r }
    fun sessions(): SessionRepository? = sessionRepo

    var colorIndex = 0
    var widthIndex = BrushSizes.DEFAULT
    var eraserIndex = BrushSizes.DEFAULT
    var brushTool = Tools.PEN
    var lastDrawTool = Tools.PEN
    var brushShape = Shapes.LINE
    var barsHidden = false
    var rightHanded = false
    var currentZoom = 1f
    var focusKey = "p0"
    var lastTriTap = 0L
    var currentFp = ""
    // v11：悬浮橡皮改「点击切换」，armed(按住中) 换成 active(已切换)
    var tempEraserActive = false
    var toolBeforeEraser = Tools.PEN
    var createdPage = -1
}
