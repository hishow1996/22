package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InkHistoryTest {
    private fun mark(v: Float) = Mark(0, 0.01f, 255, Shapes.FREE, floatArrayOf(v, v))
    private fun change(k: String, a: List<Mark>, b: List<Mark>) =
        InkHistory.Change(k, a, b)

    @Test
    fun undoRedoAcrossPages() {
        val h = InkHistory()
        h.push(listOf(change("p0", emptyList(), listOf(mark(1f)))))
        h.push(listOf(change("p7", emptyList(), listOf(mark(2f)))))
        val u2 = h.undo()!!
        assertEquals("p7", u2[0].key)
        assertEquals(0, u2[0].before.size)
        val u1 = h.undo()!!
        assertEquals("p0", u1[0].key)
        assertNull(h.undo())
        assertEquals("p0", h.redo()!![0].key)
        assertEquals("p7", h.redo()!![0].key)
    }

    @Test
    fun newChangeTruncatesRedo() {
        val h = InkHistory()
        h.push(listOf(change("p0", emptyList(), listOf(mark(1f)))))
        h.undo()
        h.push(listOf(change("p0", emptyList(), listOf(mark(9f)))))
        assertNull(h.redo())
        assertEquals(1, h.undo()!![0].after.size)
    }

    @Test
    fun batchEntryAppliesToAllPages() {
        val h = InkHistory()
        val before = mapOf("p0" to listOf(mark(1f)), "d0" to listOf(mark(2f)))
        h.push(before.map { (k, v) -> change(k, v, emptyList()) })
        val u = h.undo()!!
        assertEquals(2, u.size)
        assertEquals(setOf("p0", "d0"), u.map { it.key }.toSet())
    }
}
