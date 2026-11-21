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
import android.widget.HorizontalScrollView
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
    val sizeIdx: Int,
    val bg: Boolean,
    val color: Int,
    val isNew: Boolean
) {
    /** 可选的文字颜色（和画笔调色板一致），由宿主填。 */
    var palette: IntArray = IntArray(0)
}

class TextBoxResult(
    val text: String,
    val sizeIdx: Int,
    val bg: Boolean,
    /** 页面宽度比例。 */
    val widthNorm: Float,
    val delete: Boolean,
    /** 文字颜色。 */
    val color: Int,
    /** 编辑时把框拖走的位移（屏幕像素），0 = 没动。 */
    val dx: Float,
    val dy: Float
)

/**
 * v53/v54：页面上原地输入文字批注。
 *  - 输入框浮在页面上，和最终效果同字号 / 同宽；
 *  - 框左上角的 ✥ 按住拖 = 把框移到页面任意位置；右边缘的蓝点左右拖 = 调宽；
 *  - 工具栏贴在键盘上方（像 WPS）：A− A＋ 字号、五个颜色、底（白底开关）、🗑（已有的框才有）、✓；
 *  - 键盘盖住框时框自动上移；你自己拖过之后位置就听你的，不再自动挪；
 *  - 点框外面 / 返回键 = 确定。
 */
class TextBoxEditor(
    private val activity: AppCompatActivity,
    private val host: ViewGroup,
    private val req: TextBoxRequest,
    private val onDone: (TextBoxResult?) -> Unit
) {
    private val d = activity.resources.displayMetrics.density
    private val overlay = FrameLayout(activity)
    private val edit = EditText(activity)
    private val bar = LinearLayout(activity)
    private val barScroll = HorizontalScrollView(activity)
    private val moveHandle = TextView(activity)
    private val widthHandle = View(activity)
    private val colorDots = ArrayList<View>()

    private var sizeIdx = TextBoxMath.clampIdx(req.sizeIdx)
    private var bg = req.bg
    private var color = req.color
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
        (TextBoxMath.size(sizeIdx) * req.pageUnit * req.scale).coerceAtLeast(9f * d)

    private fun applyStyle() {
        val s = sizePx()
        edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, s)
        edit.setTextColor(color)
        val pad = TextBoxGeometry.padOf(s).toInt()
        edit.setPadding(pad, pad, pad, pad)
        val gd = GradientDrawable()
        gd.cornerRadius = pad.toFloat()
        if (bg) {
            gd.setColor(Color.argb(245, 255, 255, 255))       // 编辑时几乎不透，字看得清；定稿后是半透明白底
            gd.setStroke(max(1, (1.4f * d).toInt()), color)
        } else {
            gd.setColor(Color.argb(40, 255, 255, 255))
            gd.setStroke(max(1, (1.4f * d).toInt()), 0xAA808080.toInt(), 6f * d, 4f * d)   // 虚线：无底时只在编辑时标出范围
        }
        edit.background = gd
        val lp = edit.layoutParams as FrameLayout.LayoutParams
        lp.width = widthPx.toInt()
        edit.layoutParams = lp
        for (i in colorDots.indices) {
            val c = req.palette[i]
            val dot = GradientDrawable()
            dot.shape = GradientDrawable.OVAL
            dot.setColor(c)
            dot.setStroke((if (c == color) 3 else 1) * d.toInt().coerceAtLeast(1),
                if (c == color) 0xFF2F6FED.toInt() else 0x55000000)
            colorDots[i].background = dot
        }
    }

    private fun textButton(label: String, onClick: () -> Unit): TextView = TextView(activity).apply {
        text = label
        setTextColor(0xFF222226.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        minWidth = (38 * d).toInt()
        setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
        setOnClickListener { onClick() }
    }

    fun show() {
        overlay.isClickable = true
        overlay.setOnClickListener { finish(true) }

        // 输入框（FrameLayout 子项，位置用 x / y 摆）
        edit.setText(req.text)
        edit.gravity = Gravity.TOP or Gravity.START
        edit.setIncludeFontPadding(false)
        edit.setHorizontallyScrolling(false)
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        edit.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI      // 横屏别弹全屏输入法，要看着页面打字
        edit.isFocusableInTouchMode = true
        overlay.addView(edit, FrameLayout.LayoutParams(widthPx.toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))

        // 底部工具栏：贴着键盘上方
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        bar.addView(textButton("A−") { sizeIdx = TextBoxMath.step(sizeIdx, -1); applyStyle() })
        bar.addView(textButton("A＋") { sizeIdx = TextBoxMath.step(sizeIdx, 1); applyStyle() })
        for (c in req.palette) {
            val v = View(activity)
            val lp = LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt())
            lp.setMargins((5 * d).toInt(), 0, (5 * d).toInt(), 0)
            v.setOnClickListener { color = c; applyStyle() }
            bar.addView(v, lp)
            colorDots.add(v)
        }
        val bgBtn = textButton("底") { }
        bgBtn.setOnClickListener {
            bg = !bg
            bgBtn.alpha = if (bg) 1f else 0.4f
            applyStyle()
        }
        bgBtn.alpha = if (bg) 1f else 0.4f
        bar.addView(bgBtn)
        if (!req.isNew) bar.addView(textButton("🗑") { finish(false, delete = true) })
        bar.addView(textButton("✓") { finish(true) }.apply { setTextColor(0xFF2F6FED.toInt()) })
        barScroll.isHorizontalScrollBarEnabled = false
        barScroll.setBackgroundColor(0xFFF4F4F6.toInt())
        barScroll.elevation = 8 * d
        barScroll.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, (46 * d).toInt()))
        overlay.addView(barScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (46 * d).toInt()))

        // 移动手柄（框左上角外侧）：按住拖 = 把框移到任意位置
        moveHandle.text = "✥"
        moveHandle.setTextColor(Color.WHITE)
        moveHandle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        moveHandle.gravity = Gravity.CENTER
        val mh = GradientDrawable()
        mh.shape = GradientDrawable.OVAL
        mh.setColor(0xFF2F6FED.toInt())
        mh.setStroke((2 * d).toInt(), Color.WHITE)
        moveHandle.background = mh
        overlay.addView(moveHandle, FrameLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()))
        var mdx = 0f; var mdy = 0f; var sdx = 0f; var sdy = 0f
        moveHandle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!userMoved) {            // 从自动避让后的位置接着拖，画面不跳
                        userMoved = true
                        dragDy += edit.y - baseTop()
                    }
                    mdx = ev.rawX; mdy = ev.rawY; sdx = dragDx; sdy = dragDy
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    dragDx = sdx + (ev.rawX - mdx)
                    dragDy = sdy + (ev.rawY - mdy)
                    reposition()
                    true
                }
                else -> true
            }
        }

        // 调宽圆点
        val hd = GradientDrawable()
        hd.shape = GradientDrawable.OVAL
        hd.setColor(0xFF2F6FED.toInt())
        hd.setStroke((2 * d).toInt(), Color.WHITE)
        widthHandle.background = hd
        overlay.addView(widthHandle, FrameLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()))
        var downX = 0f
        var startW = 0f
        widthHandle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; startW = widthPx; true }
                MotionEvent.ACTION_MOVE -> {
                    val minW = sizePx() * 3f
                    val maxW = overlay.width - edit.x - 4 * d
                    widthPx = (startW + (ev.rawX - downX)).coerceIn(minW, max(minW, maxW))
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

        // 布局变化（打字换行 / 键盘弹出）只重新避让键盘、挪手柄
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
        val barH = barScroll.height.toFloat().coerceAtLeast(46 * d)
        // 工具栏贴在键盘 / 导航栏上方
        val barTop = overlay.height - insetBottom() - barH
        barScroll.y = barTop
        val w = widthPx.coerceAtMost(ow)
        val left = (req.screenX - loc[0] + dragDx).coerceIn(0f, max(0f, ow - w))
        var top = baseTop()
        top = top.coerceIn(36f * d, max(36f * d, barTop - 24 * d))     // 上留出移动手柄的位置，下不压进工具栏
        if (!userMoved) {
            // 没自己拖过：键盘 / 工具栏盖住框底就整体上移
            val bottom = top + edit.height
            val limit = barTop - 8 * d
            if (bottom > limit) top = max(36f * d, top - (bottom - limit))
        }
        edit.x = left
        edit.y = top
        moveHandle.x = left - 6 * d
        moveHandle.y = top - 34 * d
        widthHandle.x = left + w - 14 * d
        widthHandle.y = top + edit.height / 2f - 14 * d
    }

    private fun finish(save: Boolean, delete: Boolean = false) {
        if (finished) return
        finished = true
        back.remove()
        val text = edit.text.toString().trimEnd('\n', ' ')
        try {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(edit.windowToken, 0)
        } catch (_: Throwable) { }
        host.removeView(overlay)
        if (!save && !delete) { onDone(null); return }
        onDone(TextBoxResult(
            text, sizeIdx, bg,
            TextBoxMath.normWidth(widthPx, req.scale, req.pageW),
            delete, color,
            if (userMoved) dragDx else 0f,
            if (userMoved) dragDy else 0f
        ))
    }
}
