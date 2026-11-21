package com.moye.pdfink

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v53：统一的「设置面板」（底部弹出）。
 *
 * 以前压感、手指书写偏移、页面底色、写完自动缩小都是「点一下图标换一档、再点再换一档」，
 * 想要哪一档得一路点过去，也看不出当前是什么、每档有什么区别。现在点图标弹出面板：
 *  - [showChoices]：分段选择（一排并列的选项），点哪个就是哪个，当场生效，下面有这一档的说明；
 *  - [showPercent]：百分比滑块 + 常用值快捷按钮 + 实时预览（写的时候 / 停笔后的对比）。
 * 颜色全部取资源色，深色模式自动跟随。点面板外面或「完成」关闭。
 */
class SettingSheet(private val act: Activity) {

    /** 分段选择里的一项：label 显示在按钮上，desc 选中时显示在下面，swatch 是可选的色块。 */
    class Choice(val label: String, val desc: String? = null, val swatch: Int? = null)

    private val density = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density + 0.5f).toInt()
    private fun dpf(v: Float) = v * density

    private val cSheet = ContextCompat.getColor(act, R.color.sheet)
    private val cInk = ContextCompat.getColor(act, R.color.icon)
    private val cMuted = ContextCompat.getColor(act, R.color.page_text)
    private val cAccent = ContextCompat.getColor(act, R.color.accent)
    private val cDivider = ContextCompat.getColor(act, R.color.divider)
    /** 半透明灰：浅色 / 深色背景上都是淡淡的一层底。 */
    private val cTrack = 0x1F808080

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(w, h)

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp)
    }

    // ---------- 外框 ----------

    private fun frame(title: String, hint: String?, content: View): Dialog {
        val dlg = Dialog(act)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cSheet)
                val r = dpf(22f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(20), dp(10), dp(20), dp(18))
        }
        // 顶部小横条，提示这是从底部弹出的面板
        root.addView(View(act).apply { background = rounded(cDivider, 2f) },
            lp(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(TextView(act).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cInk)
        }, lp().apply { topMargin = dp(16) })
        if (!hint.isNullOrBlank()) {
            root.addView(TextView(act).apply {
                text = hint
                textSize = 13f
                setTextColor(cMuted)
                setLineSpacing(0f, 1.2f)
            }, lp().apply { topMargin = dp(6) })
        }
        root.addView(content, lp().apply { topMargin = dp(18) })
        root.addView(TextView(act).apply {
            text = act.getString(R.string.sheet_done)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(cAccent, 14f)
            setOnClickListener { dlg.dismiss() }
        }, lp(h = dp(46)).apply { topMargin = dp(18) })
        dlg.setContentView(root)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            val dm = act.resources.displayMetrics
            w.setLayout(min(dm.widthPixels, dp(560)), ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setDimAmount(0.32f)
            val a = w.attributes
            a.windowAnimations = android.R.style.Animation_InputMethod
            w.attributes = a
        }
        return dlg
    }

    // ---------- 分段选择 ----------

    /** 点哪个就是哪个，当场调用 [onPick]；面板不自动关，方便来回对比。 */
    fun showChoices(
        title: String, hint: String?, choices: List<Choice>, selected: Int, onPick: (Int) -> Unit
    ) {
        if (choices.isEmpty()) return
        var sel = selected.coerceIn(0, choices.size - 1)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val seg = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(cTrack, 14f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val desc = TextView(act).apply {
            textSize = 13f
            setTextColor(cMuted)
            setLineSpacing(0f, 1.25f)
            minLines = 2
        }
        val pills = ArrayList<TextView>()
        fun paint() {
            for ((i, tv) in pills.withIndex()) {
                val on = i == sel
                tv.setTextColor(if (on) Color.WHITE else cInk)
                tv.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                tv.background = if (on) rounded(cAccent, 11f) else null
            }
            desc.text = choices[sel].desc.orEmpty()
        }
        for ((i, c) in choices.withIndex()) {
            val tv = TextView(act).apply {
                text = c.label
                textSize = 14f
                gravity = Gravity.CENTER
                if (c.swatch != null) {
                    val dot = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(c.swatch)
                        setStroke(dp(1), 0x33000000)
                        setSize(dp(14), dp(14))
                    }
                    compoundDrawablePadding = dp(6)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
                }
                setOnClickListener {
                    if (sel != i) {
                        sel = i
                        paint()
                        onPick(i)
                    }
                }
            }
            pills.add(tv)
            seg.addView(tv, lp(0, dp(42)).apply { weight = 1f })
        }
        paint()
        col.addView(seg, lp())
        col.addView(desc, lp().apply { topMargin = dp(12) })
        frame(title, hint, col).show()
    }

    // ---------- 百分比 ----------

    /**
     * 滑块（[minPct]..[maxPct]，按 [step]）+ 一排快捷按钮（「关」和 [presets]）+ 实时预览。
     * percent 为 0 表示关；拖滑块或点快捷按钮都会当场调用 [onChange]（0 = 关）。
     * remembered 是关闭前最后一次的百分比，关着时滑块停在它上面，一拖就用它起步。
     */
    fun showPercent(
        title: String, hint: String?, percent: Int,
        minPct: Int, maxPct: Int, step: Int, presets: IntArray, remembered: Int,
        onChange: (Int) -> Unit
    ) {
        var cur = percent
        var last = if (remembered in minPct..maxPct) remembered else (minPct + maxPct) / 2
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        val label = TextView(act).apply {
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }
        val sub = TextView(act).apply {
            textSize = 13f
            setTextColor(cMuted)
        }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(label, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(sub, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(10); bottomMargin = dp(6)
            })
        }
        val preview = ShrinkPreviewView(
            act, cInk, cAccent, cMuted, cTrack,
            act.getString(R.string.shrink_before),
            { p -> act.getString(R.string.shrink_after, p) },
            act.getString(R.string.shrink_not_on)
        )
        val seek = SeekBar(act).apply {
            max = (maxPct - minPct) / step
            progressTintList = ColorStateList.valueOf(cAccent)
            thumbTintList = ColorStateList.valueOf(cAccent)
            progressBackgroundTintList = ColorStateList.valueOf(cTrack)
        }
        val ends = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(act).apply {
                text = "$minPct%"; textSize = 11f; setTextColor(cMuted)
            }, lp(0).apply { weight = 1f })
            addView(TextView(act).apply {
                text = "$maxPct%"; textSize = 11f; setTextColor(cMuted); gravity = Gravity.END
            }, lp(0).apply { weight = 1f })
        }

        // 快捷按钮：关 + 常用值
        val chipValues = intArrayOf(0) + presets
        val chips = ArrayList<TextView>()
        val chipRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var applyValue: (Int, Boolean) -> Unit
        for ((i, v) in chipValues.withIndex()) {
            val tv = TextView(act).apply {
                text = if (v == 0) act.getString(R.string.sheet_off) else "$v%"
                textSize = 14f
                gravity = Gravity.CENTER
                setOnClickListener { applyValue(v, false) }
            }
            chips.add(tv)
            chipRow.addView(tv, lp(0, dp(40)).apply {
                weight = 1f
                if (i > 0) leftMargin = dp(8)
            })
        }

        fun render(fromSlider: Boolean) {
            val on = cur > 0
            label.text = if (on) "$cur%" else act.getString(R.string.shrink_state_off)
            label.setTextColor(if (on) cAccent else cMuted)
            sub.text = if (on) act.getString(R.string.shrink_state_on_hint) else ""
            preview.pct = cur
            seek.alpha = if (on) 1f else 0.45f
            if (!fromSlider) seek.progress = (((if (on) cur else last) - minPct) / step).coerceIn(0, seek.max)
            for ((i, tv) in chips.withIndex()) {
                val sel = chipValues[i] == cur
                tv.setTextColor(if (sel) Color.WHITE else cInk)
                tv.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                tv.background = rounded(if (sel) cAccent else cTrack, 12f)
            }
        }
        applyValue = { v, fromSlider ->
            cur = v
            if (v > 0) last = v
            render(fromSlider)
            onChange(v)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyValue(minPct + progress * step, true)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        render(false)

        col.addView(head, lp())
        col.addView(preview, lp(h = dp(122)).apply { topMargin = dp(12) })
        col.addView(seek, lp().apply { topMargin = dp(14) })
        col.addView(ends, lp())
        col.addView(chipRow, lp().apply { topMargin = dp(14) })
        frame(title, hint, col).show()
    }
}

/**
 * 「写的时候 / 停笔后」对比预览：上面一行是写的大字，下面一行是按当前百分比缩小后的样子，
 * 从同一个左端起笔（真实行为也是贴着起点缩）。拖滑块时实时变化。
 */
private class ShrinkPreviewView(
    ctx: Context,
    private val ink: Int,
    private val accent: Int,
    private val muted: Int,
    private val bg: Int,
    private val beforeText: String,
    private val afterText: (Int) -> String,
    private val notOnText: String
) : View(ctx) {

    /** 0 = 关（下面一行画成同样大小的灰线）。 */
    var pct = 0
        set(v) {
            field = v
            invalidate()
        }

    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private fun label(c: Canvas, text: String, x: Float, y: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = 11f * d
        c.drawText(text, x, y, paint)
    }

    private fun squiggle(c: Canvas, x0: Float, cy: Float, len: Float, amp: Float, color: Int, sw: Float) {
        path.reset()
        val n = 120
        for (i in 0..n) {
            val t = i / n.toFloat()
            val x = x0 + len * t
            val y = cy + amp * sin(t * 2.0 * PI * 5.5).toFloat() *
                (0.55f + 0.45f * sin(t * 2.0 * PI * 1.3 + 0.8).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = sw
        paint.color = color
        c.drawPath(path, paint)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = bg
        c.drawRoundRect(0f, 0f, w, height.toFloat(), 14f * d, 14f * d, paint)
        val pad = 16f * d
        val len = (w - 2 * pad) * 0.92f
        val on = pct > 0
        val s = if (on) pct / 100f else 1f
        label(c, beforeText, pad, 22f * d, muted)
        squiggle(c, pad, 52f * d, len, 15f * d, ink, 2.6f * d)
        label(c, if (on) afterText(pct) else notOnText, pad, 88f * d, if (on) accent else muted)
        squiggle(
            c, pad, 104f * d, len * s, 15f * d * s,
            if (on) accent else muted, 2.6f * d * sqrt(s)
        )
    }
}
