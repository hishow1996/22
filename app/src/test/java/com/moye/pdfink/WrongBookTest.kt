package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Test

class WrongBookTest {
    @Test
    fun unionSortedDistinct() {
        assertEquals(listOf(1, 3, 5, 8), WrongBook.pages(listOf(5, 1, 3), listOf(3, 8), 10))
    }

    @Test
    fun dropsOutOfRange() {
        assertEquals(listOf(0, 9), WrongBook.pages(listOf(-1, 0, 10), listOf(9, 42), 10))
    }

    @Test
    fun emptyWhenNothing() {
        assertEquals(emptyList<Int>(), WrongBook.pages(emptyList(), emptyList(), 5))
    }
}
