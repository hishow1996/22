package com.moye.pdfink

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * v43 多标签。v48 起不再占一行标签条：多于一个文件时，顶栏最左的按钮变成「多标签」，
 * 点开弹出文件列表（当前的打 ✓，可切换 / 关闭 / 新开）。
 *
 * 同一时刻只实例一个 PageSource。标签只记 Uri / 名称 / 类型；切走前由
 * MainActivity 保存会话，切回来靠 InkStore 与 SessionRepository 按内容指纹
 * 恢复页码、缩放、笔迹。因此内存不随标签数翻倍，荣耀 10 上不引重库。
 */
class TabStripController(
    private val activity: AppCompatActivity,
    private val onActivate: (TabItem) -> Unit,
    private val onAdd: () -> Unit,
    private val onEmpty: () -> Unit,
    /** 标签集合 / 当前项变了（用来刷新顶栏按钮图标）。 */
    private val onChanged: () -> Unit = {}
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

    private fun render() { onChanged() }

    /** 文件列表：点名字切换，点 × 关闭，最后一行新开文件。 */
    fun showSwitcher() {
        if (items.isEmpty()) { onAdd(); return }
        val ink = ContextCompat.getColor(activity, R.color.icon)
        val accent = ContextCompat.getColor(activity, R.color.accent)
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var dialog: AlertDialog? = null
        for ((i, t) in items.withIndex()) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(4), 0)
            }
            row.addView(TextView(activity).apply {
                text = if (i == active) "✓" else ""
                textSize = 15f
                setTextColor(accent)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(activity).apply {
                text = t.name.ifBlank { "文档" }
                textSize = 15f
                setTextColor(if (i == active) accent else ink)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(dp(6), 0, dp(8), 0)
                contentDescription = t.name
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(activity).apply {
                text = "✕"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(ink)
                contentDescription = activity.getString(R.string.cd_tab_close)
                setOnClickListener { dialog?.dismiss(); close(i) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            row.setOnClickListener { dialog?.dismiss(); activate(i) }
            box.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        box.addView(TextView(activity).apply {
            text = "＋  " + activity.getString(R.string.tab_open_new)
            textSize = 15f
            setTextColor(accent)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { dialog?.dismiss(); onAdd() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        val scroll = ScrollView(activity).apply { addView(box) }
        dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.tab_switch_title, items.size))
            .setView(scroll)
            .show()
    }
}
