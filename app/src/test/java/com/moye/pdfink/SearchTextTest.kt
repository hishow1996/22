package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {

    private fun layout(t: String) = PageTextLayout(t, FloatArray(0), BooleanArray(0))

    @Test fun layoutTextIsPreferredAndFallbackIsSkippedWhenEveryPageHasText() {
        var fallbackCalls = 0
        val out = SearchText.build(2, { layout("版面文字$it") }) {
            fallbackCalls++
            listOf("旧-0", "旧-1")
        }
        assertEquals(listOf("版面文字0", "版面文字1"), out)
        assertEquals(0, fallbackCalls)
    }

    @Test fun onlyBlankPagesAreFilledFromTheFallback() {
        val out = SearchText.build(3, { if (it == 1) null else layout("版面$it") }) {
            listOf("旧-0", "旧-1", "旧-2")
        }
        assertEquals(listOf("版面0", "旧-1", "版面2"), out)
    }

    @Test fun layoutFailureOrEmptyFallbackNeverThrows() {
        val boom = SearchText.build(2, { throw IllegalStateException("x") }) { emptyList() }
        assertEquals(listOf("", ""), boom)
        val failedFallback = SearchText.build(1, { null }) { throw IllegalStateException("y") }
        assertEquals(listOf(""), failedFallback)
        assertTrue(SearchText.build(0, { null }) { emptyList() }.isEmpty())
    }
}
