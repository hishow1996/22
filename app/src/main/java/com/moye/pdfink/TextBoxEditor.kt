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
    val sizeIdx: Int,
    val bg: Boolean,
    val color: Int,
    val isNew: Boolean
)

class TextBoxResult(
    val text: String,
    val sizeIdx: Int,
    val bg: Boolean,
    /** 页面宽度比例。 */
    val widthNorm: Float,
    val delete: Boolean
)

/**
 * v53：页面上原地输入文字批注。
 * 在页面上方浮一个和最终效果同字号 / 同宽的输入框（键盘弹出时整体上移避开键盘），
 * 上面一条小工具栏：A− / A＋ 换字号、底 切换白底、🗑 删除（已有的框才有）、✓ 确定；
 * 右边缘一个圆点可以左右拖，调宽度；点框外面 / 返回键 = 确定。
 */
class TextBoxEditor(
    private val activity: AppCompatActivity,
    private val host: ViewGroup,
    private val req: TextBoxRequest,
    private val onDone: (TextBoxResult?) -> Unit
) {
    private val d = activity.resources.displayMetrics.density
    private val overlay = FrameLayout(activity)
    private val group = LinearLayout(activity)
    private val toolbar = LinearLayout(activity)
    private val edit = EditText(activity)
    private val handle = View(activity)

    private var sizeIdx = TextBoxMath.clampIdx(req.sizeIdx)
    private var bg = req.bg
    private var widthPx = req.screenW
    private var shift = 0f
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
        val pad = (TextBoxGeometry.padOf(s)).toInt()
        edit.setPadding(pad, pad, pad, pad)
        val gd = GradientDrawable()
        gd.cornerRadius = pad.toFloat()
        if (bg) {
            gd.setColor(Color.argb(225, 255, 255, 255))
            gd.setStroke(max(1, (1.2f * d).toInt()), req.color)
        } else {
            gd.setColor(Color.TRANSPARENT)
            gd.setStroke(max(1, (1.2f * d).toInt()), 0x88808080.toInt(), 6f * d, 4f * d)   // 虚线：无底时只在编辑时标出范围
        }
        edit.background = gd
        val lp = edit.layoutParams as LinearLayout.LayoutParams
        lp.width = widthPx.toInt()
        edit.layoutParams = lp
    }

    private fun button(label: String, onClick: () -> Unit): TextView = TextView(activity).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        minWidth = (44 * d).toInt()
        setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
        setOnClickListener { onClick() }
    }

    fun show() {
        overlay.isClickable = true
        overlay.setOnClickListener { finish(true) }

        // 工具栏
        val tb = GradientDrawable()
        tb.setColor(0xF2222226.toInt())
        tb.cornerRadius = 10 * d
        toolbar.background = tb
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.addView(button("A−") { sizeIdx = TextBoxMath.step(sizeIdx, -1); applyStyle() })
        toolbar.addView(button("A＋") { sizeIdx = TextBoxMath.step(sizeIdx, 1); applyStyle() })
        val bgBtn = button("底") { }
        bgBtn.setOnClickListener {
            bg = !bg
            bgBtn.alpha = if (bg) 1f else 0.45f
            applyStyle()
        }
        bgBtn.alpha = if (bg) 1f else 0.45f
        toolbar.addView(bgBtn)
        if (!req.isNew) toolbar.addView(button("🗑") { finish(false, delete = true) })
        toolbar.addView(button("✓") { finish(true) })

        // 输入框
        edit.setText(req.text)
        edit.setTextColor(req.color)
        edit.gravity = Gravity.TOP or Gravity.START
        edit.setIncludeFontPadding(false)
        edit.setHorizontallyScrolling(false)
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        edit.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI      // 横屏别弹全屏输入法，要看着页面打字
        edit.isFocusableInTouchMode = true

        group.orientation = LinearLayout.VERTICAL
        group.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (6 * d).toInt() })
        group.addView(edit, LinearLayout.LayoutParams(widthPx.toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        overlay.addView(group, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 调宽圆点
        val hd = GradientDrawable()
        hd.shape = GradientDrawable.OVAL
        hd.setColor(0xFF2F6FED.toInt())
        hd.setStroke((2 * d).toInt(), Color.WHITE)
        handle.background = hd
        overlay.addView(handle, FrameLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt()))
        var downX = 0f
        var startW = 0f
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; startW = widthPx; true }
                MotionEvent.ACTION_MOVE -> {
                    val minW = sizePx() * 3f
                    overlay.getLocationOnScreen(loc)
                    val maxW = overlay.width - (group.x) - 4 * d
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

        // 首次布局后摆到页面上的位置；之后每次布局（打字换行 / 键盘弹出）只重新避让键盘、挪圆点
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

    private fun imeBottomInset(): Int =
        ViewCompat.getRootWindowInsets(overlay)
            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

    /** 摆位：输入框顶边对齐页面上那个点；键盘盖住就整体上移，但不超出屏幕顶部。 */
    private fun reposition() {
        if (finished || overlay.width == 0) return
        overlay.getLocationOnScreen(loc)
        val tbH = toolbar.height + 6 * d
        var left = req.screenX - loc[0]
        left = left.coerceIn(0f, max(0f, overlay.width - widthPx.coerceAtMost(overlay.width.toFloat())))
        var top = req.screenY - loc[1] - tbH
        if (top < 8 * d) top = 8 * d                  // 页面最上沿：工具栏放不下就整体下压一点
        group.x = left
        // 键盘避让
        val screenBottom = loc[1] + overlay.height
        val visibleBottom = screenBottom - imeBottomInset()
        val groupBottom = loc[1] + top + group.height
        shift = if (groupBottom > visibleBottom - 8 * d) {
            min(groupBottom - (visibleBottom - 8 * d), max(0f, top - 8 * d))
        } else 0f
        group.y = top - shift
        // 圆点贴在输入框右边缘、竖直居中
        handle.x = group.x + widthPx - 13 * d
        handle.y = group.y + toolbar.height + 6 * d + edit.height / 2f - 13 * d
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
            delete
        ))
    }
}
