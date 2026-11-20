package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AimGateTest {
    private fun gate() = AimGate(slopPx = 20f, tapMs = 200L)

    @Test
    fun smallJitterHoldsAndMovingPastSlopStartsInk() {
        val g = gate()
        g.down(100f, 100f, 0L)
        assertEquals(AimGate.Phase.AIMING, g.phase)
        assertEquals(AimGate.Action.HOLD, g.move(105f, 103f))
        assertEquals(AimGate.Action.HOLD, g.move(100f, 119f))      // 刚好在 slop 内
        assertEquals(AimGate.Action.START, g.move(100f, 121f))     // 超过
        assertEquals(AimGate.Phase.INKING, g.phase)
        assertEquals(AimGate.Action.PASS, g.move(100f, 140f))
        assertEquals(AimGate.Action.PASS, g.up(900L))
        assertFalse(g.active())
    }

    @Test
    fun quickTapDrawsDotButLongAimDoesNot() {
        val g = gate()
        g.down(10f, 10f, 1000L)
        assertEquals(AimGate.Action.TAP, g.up(1150L))
        g.down(10f, 10f, 2000L)
        assertEquals(AimGate.Action.HOLD, g.move(12f, 11f))
        assertEquals(AimGate.Action.DISCARD, g.up(2600L))          // 瞄准后抬手：不画
    }

    @Test
    fun startingPointStaysTheOriginalDownPoint() {
        val g = gate()
        g.down(50f, 60f, 0L)
        g.move(55f, 60f)
        g.move(90f, 60f)
        assertEquals(50f, g.downX, 0f)
        assertEquals(60f, g.downY, 0f)
    }

    @Test
    fun secondFingerWhileAimingAbandonsTheGesture() {
        val g = gate()
        g.down(0f, 0f, 0L)
        assertEquals(AimGate.Action.DISCARD, g.secondPointer())
        assertEquals(AimGate.Phase.IGNORED, g.phase)
        assertEquals(AimGate.Action.HOLD, g.move(500f, 500f))      // 之后的移动一律吞掉
        assertEquals(AimGate.Action.HOLD, g.up(10L))
        assertFalse(g.active())
    }

    @Test
    fun secondFingerWhileInkingPassesThrough() {
        val g = gate()
        g.down(0f, 0f, 0L)
        g.move(50f, 0f)
        assertEquals(AimGate.Action.PASS, g.secondPointer())
        assertEquals(AimGate.Phase.INKING, g.phase)
    }

    @Test
    fun cancelResetsAndOnlyPassesWhenInking() {
        val g = gate()
        g.down(0f, 0f, 0L)
        assertEquals(AimGate.Action.DISCARD, g.cancel())
        assertFalse(g.active())
        g.down(0f, 0f, 0L); g.move(50f, 0f)
        assertEquals(AimGate.Action.PASS, g.cancel())
        assertTrue(g.phase == AimGate.Phase.IDLE)
    }
}
