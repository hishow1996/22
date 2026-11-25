package com.moye.pdfink

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max
import kotlin.math.min

/** 要编辑的文字框：位置 / 大小用屏幕像素（编辑器浮在页面上方），页面换算用 scale。 */
class TextBoxRequest(
    /** 框左上角在屏幕上的位置。 */
    val screenX: Float,
    val screenY: Float,
    /** 框此刻在屏幕上的宽。 */
    val screenW: Float,
    /** 屏幕像素 / 页面布局像素（页面放大后字也跟着大）。 */
    val scale: Float,
    /** 页面布局宽、短边（像素），换算字号 / 宽度用。 */
    val pageW: Float,
    val pageUnit: Float,
    val text: String,
    /** 字号（页面短边的比例）。 */
    val sizeFrac: Float,
    val bg: Boolean,
    val color: Int,
    val isNew: Boolean
)

class TextBoxResult(
    val text: String,
    val sizeFrac: Float,
    val bg: Boolean,
    /** 页面宽度比例。 */
    val widthNorm: Float,
    val color: Int,
    /** 编辑时把框拖走的位移（屏幕像素），0 = 没动。 */
    val dx: Float,
    val dy: Float
)

/**
 * v55：页面上原地输入文字批注，界面尽量干净——
 *  - 一个蓝色细边框；没有工具栏、没有颜色 / 字号按钮；
 *  - **移动**：按住框的边框外一圈（14dp 宽的透明带）拖，框到页面任意位置；
 *  - **放大缩小**：拖右下角的蓝点，宽度和字号等比例一起变（像 WPS 拖角缩放）；
 *  - 右上角一个小 ✓ = 确定；点框外面 / 返回键也是确定；把字删光再确定 = 删除这个框；
 *  - 键盘盖住框时框自动上移；自己拖过之后位置就听你的。
 * 颜色用此刻画笔颜色（改已有的框保持原色）。
 */
class TextBoxEditor(
    private val activity: AppCompatActivity,
    private val host: ViewGroup,
    private val req: TextBoxRequest,
    private val onDone: (TextBoxResult?) -> Unit
) {
    private val d = activity.resources.displayMetrics.density
    private val band = (14 * d).toInt()                // 框外一圈可拖动的透明带
    private val overlay = FrameLayout(activity)
    private val frame = FrameLayout(activity)
    private val edit = EditText(activity)
    private val okBtn = TextView(activity)
    private val corner = View(activity)

    private var sizeFrac = TextBoxMath.clampSize(req.sizeFrac)
    private var widthPx = req.screenW
    private var dragDx = 0f
    private var dragDy = 0f
    private var userMoved = false
    private var finished = false
    private val loc = IntArray(2)

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { finish(true) }
    }

    private fun sizePx(): Float =
        (sizeFrac * req.pageUnit * req.scale).coerceAtLeast(9f * d)

    private fun applyStyle() {
        val s = sizePx()
        edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, s)
        edit.setTextColor(req.color)
        val pad = TextBoxGeometry.padOf(s).toInt()
        edit.setPadding(pad, pad, pad, pad)
        val gd = GradientDrawable()
        gd.cornerRadius = pad.toFloat()
        // v62：不再用白底盖住原文——只留一圈蓝色虚线框和极淡的蓝色提示，下面的文字清晰可见，
        // 方便对准要批注的位置；确定后虚线框消失，页面上只剩文字
        gd.setColor(0x122F6FED)
        gd.setStroke(max(1, (1.5f * d).toInt()), 0xFF2F6FED.toInt(), 6f * d, 4f * d)
        edit.background = gd
        val lp = edit.layoutParams as FrameLayout.LayoutParams
        lp.width = widthPx.toInt()
        edit.layoutParams = lp
        val fl = frame.layoutParams as FrameLayout.LayoutParams
        fl.width = widthPx.toInt() + band * 2
        frame.layoutParams = fl
    }

    private fun dot(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((2 * d).toInt(), Color.WHITE)
    }

    fun show() {
        overlay.isClickable = true
        overlay.setOnClickListener { finish(true) }

        // 输入框 + 四周一圈透明的拖动带
        edit.setText(req.text)
        edit.gravity = Gravity.TOP or Gravity.START
        edit.setIncludeFontPadding(false)
        edit.setHorizontallyScrolling(false)
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        edit.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI      // 横屏别弹全屏输入法，要看着页面打字
        edit.isFocusableInTouchMode = true
        frame.addView(edit, FrameLayout.LayoutParams(
            widthPx.toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(band, band, band, band) })
        overlay.addView(frame, FrameLayout.LayoutParams(
            widthPx.toInt() + band * 2, ViewGroup.LayoutParams.WRAP_CONTENT))
        var downX = 0f; var downY = 0f; var sdx = 0f; var sdy = 0f
        frame.setOnTouchListener { _, ev ->            // 只有落在透明带上的触摸才会到这里（框里面的归输入框）
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!userMoved) {                   // 从自动避让后的位置接着拖，画面不跳
                        userMoved = true
                        dragDy += frame.y + band - baseTop()
                    }
                    downX = ev.rawX; downY = ev.rawY; sdx = dragDx; sdy = dragDy
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    dragDx = sdx + (ev.rawX - downX)
                    dragDy = sdy + (ev.rawY - downY)
                    reposition()
                    true
                }
                else -> true
            }
        }

        // 右上角 ✓
        okBtn.text = "✓"
        okBtn.setTextColor(Color.WHITE)
        okBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        okBtn.gravity = Gravity.CENTER
        okBtn.background = dot(0xFF2F6FED.toInt())
        okBtn.setOnClickListener { finish(true) }
        overlay.addView(okBtn, FrameLayout.LayoutParams((32 * d).toInt(), (32 * d).toInt()))

        // 右下角：拖动 = 宽度和字号等比例放大缩小
        corner.background = dot(0xFF2F6FED.toInt())
        overlay.addView(corner, FrameLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt()))
        var cx0 = 0f; var w0 = 0f; var s0 = 0f
        corner.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { cx0 = ev.rawX; w0 = widthPx; s0 = sizeFrac; true }
                MotionEvent.ACTION_MOVE -> {
                    val minW = 60 * d
                    val maxW = max(minW, overlay.width - edit.x - 4 * d)
                    val nw = (w0 + (ev.rawX - cx0)).coerceIn(minW, maxW)
                    widthPx = nw
                    sizeFrac = TextBoxMath.scaledSize(s0, w0, nw)
                    applyStyle()
                    true
                }
                else -> true
            }
        }

        host.addView(overlay, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activity.onBackPressedDispatcher.addCallback(activity, back)
        applyStyle()

        // 布局变化（打字换行 / 键盘弹出）重新避让键盘、挪手柄
        overlay.viewTreeObserver.addOnGlobalLayoutListener { reposition() }
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { _, insets ->
            reposition(); insets
        }
        overlay.post {
            reposition()
            edit.requestFocus()
            edit.setSelection(edit.text.length)
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun insetBottom(): Int {
        val r = ViewCompat.getRootWindowInsets(overlay) ?: return 0
        val ime = r.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val nav = r.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
        return max(ime, nav)
    }

    /** 框的基准顶边（叠加你拖动的位移，不含键盘避让）。 */
    private fun baseTop(): Float {
        overlay.getLocationOnScreen(loc)
        return req.screenY - loc[1] + dragDy
    }

    private fun reposition() {
        if (finished || overlay.width == 0) return
        overlay.getLocationOnScreen(loc)
        val ow = overlay.width.toFloat()
        val w = widthPx.coerceAtMost(ow)
        val left = (req.screenX - loc[0] + dragDx).coerceIn(0f, max(0f, ow - w))
        val visibleBottom = overlay.height - insetBottom() - 8 * d
        val minTop = 38f * d                                   // 上面给 ✓ 留位置
        var top = baseTop()
        if (!userMoved) {
            val bottom = top + edit.height
            if (bottom > visibleBottom) top -= (bottom - visibleBottom)   // 键盘盖住就整体上移
        }
        top = top.coerceIn(minTop, max(minTop, visibleBottom - 30 * d))
        frame.x = left - band
        frame.y = top - band
        okBtn.x = left + w - 16 * d
        okBtn.y = top - 38 * d
        corner.x = left + w - 13 * d
        corner.y = top + edit.height - 13 * d
    }

    private fun finish(save: Boolean) {
        if (finished) return
        finished = true
        back.remove()
        val text = edit.text.toString().trimEnd('\n', ' ')
        try {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(edit.windowToken, 0)
        } catch (_: Throwable) { }
        host.removeView(overlay)
        if (!save) { onDone(null); return }
        onDone(TextBoxResult(
            text, sizeFrac, req.bg,
            TextBoxMath.normWidth(widthPx, req.scale, req.pageW),
            req.color,
            if (userMoved) dragDx else 0f,
            if (userMoved) dragDy else 0f
        ))
    }
}
