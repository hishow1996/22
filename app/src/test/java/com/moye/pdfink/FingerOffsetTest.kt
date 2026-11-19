package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerOffsetTest {
    @Test fun levelsCycleThroughOffSmallMediumLargeAndBack() {
        var l = FingerOffset.OFF
        val seen = ArrayList<Int>()
        repeat(5) { seen.add(l); l = FingerOffset.next(l) }
        assertEquals(listOf(0, 1, 2, 3, 0), seen)
    }

    @Test fun dpGrowsWithLevelAndOffIsZero() {
        assertEquals(0, FingerOffset.dp(0))
        assertTrue(FingerOffset.dp(1) < FingerOffset.dp(2))
        assertTrue(FingerOffset.dp(2) < FingerOffset.dp(3))
        assertEquals(0, FingerOffset.dp(99))
    }

    @Test fun clampKeepsSavedValuesInRange() {
        assertEquals(0, FingerOffset.clamp(-5))
        assertEquals(3, FingerOffset.clamp(42))
        assertEquals(2, FingerOffset.clamp(2))
    }

    @Test fun appliesOnlyToFingerAndNotToNoteOrStylusOrOff() {
        assertTrue(FingerOffset.applies(100, true, Tools.PEN))
        assertTrue(FingerOffset.applies(100, true, Tools.ERASER))
        assertTrue(FingerOffset.applies(100, true, Tools.LASSO))
        assertFalse(FingerOffset.applies(100, true, Tools.NOTE))
        assertFalse(FingerOffset.applies(100, false, Tools.PEN))
        assertFalse(FingerOffset.applies(0, true, Tools.PEN))
    }
}
