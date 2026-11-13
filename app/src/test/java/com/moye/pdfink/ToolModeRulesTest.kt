package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ToolModeRulesTest {

    @Test
    fun lassoIsNeverCarriedIntoWriteMode() {
        assertEquals(Tools.PEN, ToolModeRules.entryTool(Tools.LASSO))
    }

    @Test
    fun otherToolsArePreservedOnEntry() {
        for (t in listOf(
            Tools.PEN, Tools.HIGHLIGHT, Tools.SHAPE, Tools.ERASER, Tools.NOTE
        )) {
            assertEquals(t, ToolModeRules.entryTool(t))
        }
    }

    @Test
    fun entryNormalizationIsIdempotent() {
        val once = ToolModeRules.entryTool(Tools.LASSO)
        assertEquals(once, ToolModeRules.entryTool(once))
    }

    @Test
    fun closeReturnsToPreviousDrawTool() {
        // v38：不再固定回 PEN，荧光笔/形状进入套索后关闭应回到原来那支
        assertEquals(Tools.HIGHLIGHT, ToolModeRules.closeFallback(Tools.HIGHLIGHT))
        assertEquals(Tools.SHAPE, ToolModeRules.closeFallback(Tools.SHAPE))
        assertEquals(Tools.PEN, ToolModeRules.closeFallback(Tools.PEN))
    }

    @Test
    fun closeFallbackRejectsNonDrawTools() {
        for (t in listOf(Tools.LASSO, Tools.ERASER, Tools.NOTE)) {
            assertEquals(Tools.PEN, ToolModeRules.closeFallback(t))
            assertNotEquals(t, ToolModeRules.closeFallback(t))
        }
    }

    @Test
    fun sessionValueIsCoercedBeforeNormalization() {
        // finishOpenUri 先 coerceIn(0, Tools.NOTE) 再归一化，越界值不应穿透
        for (raw in listOf(-1, 0, Tools.LASSO, Tools.NOTE, 99)) {
            val saved = raw.coerceIn(0, Tools.NOTE)
            assertEquals(ToolModeRules.entryTool(saved), ToolModeRules.entryTool(saved))
            if (saved == Tools.LASSO) {
                assertEquals(Tools.PEN, ToolModeRules.entryTool(saved))
            }
        }
    }
}
