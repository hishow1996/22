package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PressureCurveTest {

    @Test fun offAndFingerAreAlwaysNeutral() {
        for (p in listOf(0f, 0.3f, 1f)) {
            assertEquals(1f, PressureCurve.factor(PressureCurve.OFF, p, true), 0.0001f)
            assertEquals(1f, PressureCurve.factor(PressureCurve.STRONG, p, false), 0.0001f)
        }
        assertEquals(1f, PressureCurve.startFactor(PressureCurve.OFF, 1f, true), 0.0001f)
    }

    @Test fun strongerLevelWidensRange() {
        val soft = PressureCurve.factor(PressureCurve.SOFT, 1f, true) -
            PressureCurve.factor(PressureCurve.SOFT, 0f, true)
        val normal = PressureCurve.factor(PressureCurve.NORMAL, 1f, true) -
            PressureCurve.factor(PressureCurve.NORMAL, 0f, true)
        val strong = PressureCurve.factor(PressureCurve.STRONG, 1f, true) -
            PressureCurve.factor(PressureCurve.STRONG, 0f, true)
        assertTrue(soft < normal && normal < strong)
    }

    @Test fun standardMatchesLegacyCurve() {
        // v42 的旧曲线 0.65 + 0.5*p；标准档必须逐点等价，避免升级后手感突变。
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val legacy = (0.65f + 0.5f * p).coerceIn(0.7f, 1.3f)
            assertEquals(legacy, PressureCurve.factor(PressureCurve.NORMAL, p, true), 0.0001f)
        }
    }

    @Test fun startFactorSitsBetweenNeutralAndTarget() {
        val target = PressureCurve.factor(PressureCurve.STRONG, 1f, true)
        val start = PressureCurve.startFactor(PressureCurve.STRONG, 1f, true)
        assertTrue(start > 1f && start < target)
    }

    @Test fun smoothIsBoundedAndMonotone() {
        val a = PressureCurve.smooth(1f, 1.6f)
        val b = PressureCurve.smooth(a, 1.6f)
        assertTrue(a > 1f && b > a && b <= 1.6f)
        assertTrue(PressureCurve.smooth(0.1f, 0.1f) >= 0.35f)
    }

    @Test fun levelCyclesThroughFourAndCoerces() {
        var l = PressureCurve.DEFAULT
        val seen = LinkedHashSet<Int>()
        repeat(4) { seen.add(l); l = PressureCurve.next(l) }
        assertEquals(4, seen.size)
        assertEquals(PressureCurve.DEFAULT, l)
        assertEquals(PressureCurve.STRONG, PressureCurve.coerce(99))
        assertEquals(PressureCurve.OFF, PressureCurve.coerce(-3))
    }
}
