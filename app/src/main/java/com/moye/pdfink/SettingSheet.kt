package com.moye.pdfink

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * 设置面板（v55 瘦身版）：紧凑的悬浮小卡片，贴底居中，不压暗背景，写字时能直接看到页面。
 *
 * 和工具栏保持一个调子：纯文字 + 图标色，选中态用强调色的淡底，不用实心大色块；
 * 没有说明段落、没有预览图、没有「完成」按钮（点卡片外面就关）。
 *  - [showChoices]：一排并列的选项，点哪个就是哪个，当场生效，下面最多一行短说明；
 *  - [showPercent]：标题行右侧是当前值和开关，下面一条滑块。
 * 颜色全部取资源色，深色模式自动跟随。
 */

class SettingSheet(private val act: Activity) {

    /** 分段选择里的一项：label 显示在按钮上，desc 选中时显示在下面（一行短句），swatch 是可选的色块。 */
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
    /** 强调色的淡底（约 18%）：选中态，和工具栏选中的淡色圆角块同一个意思。 */
    private val cAccentSoft = (cAccent and 0x00FFFFFF) or 0x2E000000

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(w, h)

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp)
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    // ---------- 外框 ----------

    /** 悬浮卡片：标题行（左标题、右可放值 / 开关）+ 内容。点卡片外面关闭，背景不压暗。 */
    private fun frame(title: String, right: View?, content: View): Dialog {
        val dlg = Dialog(act)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cSheet, 16f, cDivider)
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        val titleView = TextView(act).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cInk)
            gravity = Gravity.CENTER_VERTICAL
        }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(titleView, lp(0, dp(40)).apply { weight = 1f })
        if (right != null) head.addView(right)
        root.addView(head, lp())
        root.addView(content, lp().apply { topMargin = dp(4) })
        dlg.setContentView(root)
        dlg.setCanceledOnTouchOutside(true)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            val dm = act.resources.displayMetrics
            w.setLayout(min(dm.widthPixels - dp(32), dp(340)), ViewGroup.LayoutParams.WRAP_CONTENT)
            val a = w.attributes
            a.y = dp(16)
            a.windowAnimations = android.R.style.Animation_InputMethod
            w.attributes = a
        }
        return dlg
    }

    // ---------- 分段选择 ----------

    /** 点哪个就是哪个，当场调用 [onPick]；面板不自动关，方便来回对比。 */
    /** 点哪个就是哪个，当场调用 [onPick]；卡片不自动关，方便来回对比。[hint] 不再显示（保留参数是为了调用处不用改）。 */
    fun showChoices(
        title: String, hint: String?, choices: List<Choice>, selected: Int, onPick: (Int) -> Unit
    ) {
        if (choices.isEmpty()) return
        var sel = selected.coerceIn(0, choices.size - 1)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val seg = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(cTrack, 12f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val desc = TextView(act).apply {
            textSize = 12f
            setTextColor(cMuted)
            maxLines = 2
        }
        val pills = ArrayList<TextView>()
        fun paint() {
            for ((i, tv) in pills.withIndex()) {
                val on = i == sel
                tv.setTextColor(if (on) cAccent else cInk)
                tv.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                tv.background = if (on) rounded(cAccentSoft, 9f) else null
            }
            val d = choices[sel].desc.orEmpty()
            desc.text = d
            desc.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
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
            seg.addView(tv, lp(0, dp(36)).apply { weight = 1f })
        }
        paint()
        col.addView(seg, lp())
        col.addView(desc, lp().apply { topMargin = dp(10) })
        frame(title, null, col).show()
    }

    // ---------- 百分比 ----------

    /**
     * 滑块（[minPct]..[maxPct]，按 [step]）+ 一排快捷按钮（「关」和 [presets]）+ 实时预览。
     * percent 为 0 表示关；拖滑块或点快捷按钮都会当场调用 [onChange]（0 = 关）。
     * remembered 是关闭前最后一次的百分比，关着时滑块停在它上面，一拖就用它起步。
     */
    /**
     * 标题行右侧：当前值 + 开关；下面一条滑块（[minPct]..[maxPct]，按 [step]）。
     * percent 为 0 表示关；拖滑块或拨开关都会当场调用 [onChange]（0 = 关）。
     * remembered 是关闭前最后一次的百分比，关着时滑块停在它上面，一拖就用它起步、开关自动打开。
     * [hint]、[presets] 不再使用（保留参数是为了调用处不用改）。
     */
    fun showPercent(
        title: String, hint: String?, percent: Int,
        minPct: Int, maxPct: Int, step: Int, presets: IntArray, remembered: Int,
        onChange: (Int) -> Unit
    ) {
        var cur = percent
        var last = if (remembered in minPct..maxPct) remembered else (minPct + maxPct) / 2
        var syncing = false

        val value = TextView(act).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cAccent)
        }
        val sw = SwitchCompat(act)
        val checkedState = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.setThumbTintList(ColorStateList(checkedState, intArrayOf(cAccent, cMuted)))
        sw.setTrackTintList(ColorStateList(checkedState, intArrayOf((cAccent and 0x00FFFFFF) or 0x66000000, cTrack)))
        val right = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        right.addView(value)
        right.addView(sw, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })

        val seek = SeekBar(act).apply {
            max = (maxPct - minPct) / step
            progressTintList = ColorStateList.valueOf(cAccent)
            thumbTintList = ColorStateList.valueOf(cAccent)
            progressBackgroundTintList = ColorStateList.valueOf(cTrack)
        }
        val ends = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        ends.addView(TextView(act).apply {
            text = "$minPct%"; textSize = 11f; setTextColor(cMuted)
        }, lp(0).apply { weight = 1f })
        ends.addView(TextView(act).apply {
            text = "$maxPct%"; textSize = 11f; setTextColor(cMuted); gravity = Gravity.END
        }, lp(0).apply { weight = 1f })

        fun render(fromSlider: Boolean) {
            val on = cur > 0
            value.text = if (on) "$cur%" else ""
            syncing = true
            sw.isChecked = on
            syncing = false
            seek.alpha = if (on) 1f else 0.45f
            if (!fromSlider) {
                seek.progress = (((if (on) cur else last) - minPct) / step).coerceIn(0, seek.max)
            }
        }
        fun applyValue(v: Int, fromSlider: Boolean) {
            cur = v
            if (v > 0) last = v
            render(fromSlider)
            onChange(v)
        }
        sw.setOnCheckedChangeListener { _, checked ->
            if (!syncing) applyValue(if (checked) last else 0, false)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyValue(minPct + progress * step, true)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        render(false)

        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(seek, lp())
        col.addView(ends, lp())
        frame(title, right, col).show()
    }
}
