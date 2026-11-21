package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextBoxMathTest {

    @Test
    fun sizeIsContinuousAndClamped() {
        assertEquals(0.012f, TextBoxMath.clampSize(0.001f), 0f)
        assertEquals(0.09f, TextBoxMath.clampSize(0.5f), 0f)
        assertEquals(0.03f, TextBoxMath.clampSize(0.03f), 0f)
    }

    @Test
    fun cornerDragScalesFontWithWidth() {
        // 宽度拉到 1.5 倍，字号也是 1.5 倍
        assertEquals(0.039f, TextBoxMath.scaledSize(0.026f, 0.4f, 0.6f), 1e-6f)
        // 缩到很窄：字号停在下限
        assertEquals(TextBoxMath.MIN_SIZE, TextBoxMath.scaledSize(0.026f, 0.4f, 0.05f), 0f)
        // 放到很大：停在上限
        assertEquals(TextBoxMath.MAX_SIZE, TextBoxMath.scaledSize(0.05f, 0.3f, 0.9f), 0f)
        // 起始宽为 0：不动
        assertEquals(0.026f, TextBoxMath.scaledSize(0.026f, 0f, 0.5f), 0f)
    }

    @Test
    fun widthAndOriginStayInsideThePage() {
        assertEquals(0.12f, TextBoxMath.clampW(0.01f), 0f)
        assertEquals(1f, TextBoxMath.clampW(3f), 0f)
        assertEquals(0.3f, TextBoxMath.clampX(0.3f, 0.5f), 0f)
        assertEquals(0.4f, TextBoxMath.clampX(0.9f, 0.6f), 1e-6f)   // 右边放不下：整体左移
        assertEquals(0f, TextBoxMath.clampX(-0.2f, 0.5f), 0f)
        // 新建：点在页面右侧时默认宽收窄，不挤出页面
        assertEquals(0.6f, TextBoxMath.initialW(0.1f), 1e-6f)
        assertEquals(0.3f, TextBoxMath.initialW(0.7f), 1e-6f)
        assertEquals(0.12f, TextBoxMath.initialW(0.99f), 1e-6f)
    }

    @Test
    fun screenWidthConvertsBackToPageFraction() {
        // 页面布局宽 1000px，屏幕上放大 2 倍；屏幕上拖出 600px = 页宽 30%
        assertEquals(0.3f, TextBoxMath.normWidth(600f, 2f, 1000f), 1e-6f)
        assertEquals(TextBoxMath.DEFAULT_W, TextBoxMath.normWidth(600f, 0f, 1000f), 0f)
    }

    @Test
    fun backgroundFlagDefaultsToOn() {
        assertTrue(TextBoxMath.hasBg(null))
        assertTrue(TextBoxMath.hasBg(TextBoxMath.bgWs(true)))
        assertFalse(TextBoxMath.hasBg(TextBoxMath.bgWs(false)))
        assertEquals(2, TextBoxMath.bgWs(true).size)   // 长度 = 点数（两个角点），JSON 往返才不会丢
    }
}
