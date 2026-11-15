package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabModelsTest {

    @Test fun encodeDecodeRoundTrip() {
        val items = listOf(
            TabItem("content://a", "高数卷子.pdf", "PDF"),
            TabItem("content://b", "阅读.txt", "TEXT")
        )
        val back = TabModels.decode(TabModels.encode(items))
        assertEquals(items, back)
    }

    @Test fun brokenJsonIsSafe() {
        assertTrue(TabModels.decode("").isEmpty())
        assertTrue(TabModels.decode("{not json").isEmpty())
    }

    @Test fun activateDeduplicatesAndMovesToEnd() {
        val items = listOf(
            TabItem("u1", "一", "PDF"), TabItem("u2", "二", "PDF"),
            TabItem("u3", "三", "PDF")
        )
        val (next, idx) = TabModels.activate(items, "u1", "一改名", "PDF")
        assertEquals(3, next.size)
        assertEquals(2, idx)
        assertEquals("一改名", next.last().name)
        assertEquals(listOf("u2", "u3", "u1"), next.map { it.uri })
    }

    @Test fun activateCapsAtMax() {
        var items = emptyList<TabItem>()
        repeat(TabModels.MAX + 4) { i ->
            items = TabModels.activate(items, "u$i", "n$i", "PDF").first
        }
        assertEquals(TabModels.MAX, items.size)
        assertEquals("u${TabModels.MAX + 3}", items.last().uri)
    }

    @Test fun removeCurrentFallsToNeighbour() {
        val items = listOf(TabItem("a", "A", ""), TabItem("b", "B", ""), TabItem("c", "C", ""))
        val (kept, next) = TabModels.remove(items, 1, 1)
        assertEquals(listOf("a", "c"), kept.map { it.uri })
        assertEquals(1, next)
    }

    @Test fun removeBeforeActiveKeepsSameDocument() {
        val items = listOf(TabItem("a", "A", ""), TabItem("b", "B", ""), TabItem("c", "C", ""))
        val (kept, next) = TabModels.remove(items, 0, 2)
        assertEquals(listOf("b", "c"), kept.map { it.uri })
        assertEquals(1, next)
        assertEquals("c", kept[next!!].uri)
    }

    @Test fun removeLastReturnsNull() {
        val (kept, next) = TabModels.remove(listOf(TabItem("a", "A", "")), 0, 0)
        assertTrue(kept.isEmpty())
        assertNull(next)
    }

    @Test fun shortNameTruncates() {
        assertEquals("短", TabModels.shortName("短"))
        assertEquals(11, TabModels.shortName("12345678901234").length)
        assertTrue(TabModels.shortName("12345678901234").endsWith("…"))
    }
}
