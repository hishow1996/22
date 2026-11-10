package com.moye.pdfink

/** 跨页、跨操作的全局时间轴。一个 Entry 可以包含多页变化（清除全部 / 导入）。 */
class InkHistory {
    data class Change(val key: String, val before: List<Mark>, val after: List<Mark>)

    private val entries = ArrayList<List<Change>>()
    private var cursor = 0
    private val cap = 120

    fun push(changes: List<Change>) {
        if (changes.isEmpty()) return
        while (entries.size > cursor) entries.removeAt(entries.lastIndex)
        entries.add(changes.map { it.copy(before = ArrayList(it.before), after = ArrayList(it.after)) })
        cursor = entries.size
        while (entries.size > cap) {
            entries.removeAt(0)
            cursor--
        }
    }

    fun undo(): List<Change>? {
        if (cursor <= 0) return null
        cursor--
        return entries[cursor]
    }

    fun redo(): List<Change>? {
        if (cursor >= entries.size) return null
        val e = entries[cursor]
        cursor++
        return e
    }

    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < entries.size

    fun clear() {
        entries.clear()
        cursor = 0
    }
}
