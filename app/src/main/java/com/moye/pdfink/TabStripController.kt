package com.moye.pdfink

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

/**
 * v43 多标签：顶栏下方一条横向标签条。
 *
 * 同一时刻只实例一个 PageSource。标签只记 Uri / 名称 / 类型；切走前由
 * MainActivity 保存会话，切回来靠 InkStore 与 SessionRepository 按内容指纹
 * 恢复页码、缩放、笔迹。因此内存不随标签数翻倍，荣耀 10 上不引重库。
 */
class TabStripController(
    private val activity: AppCompatActivity,
    private val scroller: HorizontalScrollView,
    private val strip: LinearLayout,
    private val onActivate: (TabItem) -> Unit,
    private val onAdd: () -> Unit,
    private val onEmpty: () -> Unit
) {
    private val prefs = activity.getSharedPreferences("moye", Context.MODE_PRIVATE)
    private var items: List<TabItem> = TabModels.decode(prefs.getString("tabs", "").orEmpty())
    private var active = -1

    init { render() }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).roundToInt()

    /** 多于一个标签才占位，单文档时界面与 v42 完全一致。 */
    fun visible(): Boolean = items.size > 1
    fun size(): Int = items.size

    /** 每次成功打开文档后调用；已存在的标签只更新名称并激活，不打乱顺序。 */
    fun opened(uri: String, name: String, kind: String) {
        if (uri.isBlank()) return
        val idx = items.indexOfFirst { it.uri == uri }
        if (idx >= 0) {
            items = items.toMutableList().apply { this[idx] = TabItem(uri, name, kind) }
            active = idx
        } else {
            val (next, i) = TabModels.activate(items, uri, name, kind)
            items = next
            active = i
        }
        persist()
        render()
    }

    fun close(index: Int) {
        if (index !in items.indices) return
        val wasActive = index == active
        val (next, target) = TabModels.remove(items, index, active)
        items = next
        persist()
        if (target == null) {
            active = -1
            render()
            onEmpty()
            return
        }
        active = target
        render()
        // 只有关掉的是当前标签才需要重新打开；关别的标签不动当前文档。
        if (wasActive) items.getOrNull(active)?.let { onActivate(it) }
    }

    private fun activate(index: Int) {
        if (index == active || index !in items.indices) return
        active = index
        persist()
        render()
        items[index].let { onActivate(it) }
    }

    private fun persist() {
        prefs.edit().putString("tabs", TabModels.encode(items)).apply()
    }

    private fun tabBg(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(6).toFloat()
        setColor(if (selected) 0x222F6FED else 0x0A000000)
        setStroke(dp(1), if (selected) 0x66349CFF.toInt() else 0x22000000)
    }

    private fun render() {
        strip.removeAllViews()
        for ((i, t) in items.withIndex()) {
            val v = activity.layoutInflater.inflate(R.layout.item_tab, strip, false)
            val name = v.findViewById<TextView>(R.id.tabName)
            val btnClose = v.findViewById<ImageButton>(R.id.tabClose)
            name.text = TabModels.shortName(t.name.ifBlank { "文档" })
            name.contentDescription = t.name
            v.background = tabBg(i == active)
            name.setOnClickListener { activate(i) }
            btnClose.setOnClickListener { close(i) }
            btnClose.contentDescription = activity.getString(R.string.cd_tab_close)
            strip.addView(v)
        }
        val add = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_tab_add)
            setColorFilter(0xFF3B4250.toInt())
            background = null
            contentDescription = activity.getString(R.string.cd_tab_add)
            setOnClickListener { onAdd() }
        }
        strip.addView(add, LinearLayout.LayoutParams(dp(32), dp(30)).apply {
            marginStart = dp(4)
        })
        scroller.post { scroller.fullScroll(HorizontalScrollView.FOCUS_RIGHT) }
    }
}
