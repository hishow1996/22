package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfViewportGeometryTest {

    @Test
    fun visibleToolbarsReserveOnlyTheirActualArea() {
        val insets = PdfViewportGeometry.contentInsets(
            landscape = false,
            barsHidden = false,
            safeLeft = 24,
            safeRight = 32,
            toolbarTop = 168,
            toolbarBottom = 156
        )

        assertEquals(PdfContentInsets(0, 168, 0, 156), insets)
        assertEquals(2016, insets.contentHeight(2340))
        assertEquals(1080, insets.contentWidth(1080))
    }

    @Test
    fun hiddenToolbarsReleaseTheFullVerticalViewport() {
        val insets = PdfViewportGeometry.contentInsets(
            landscape = false,
            barsHidden = true,
            safeLeft = 24,
            safeRight = 32,
            toolbarTop = 168,
            toolbarBottom = 156
        )

        assertEquals(0, insets.top)
        assertEquals(0, insets.bottom)
        assertEquals(2340, insets.contentHeight(2340))
    }

    @Test
    fun landscapeKeepsPhysicalSideCutoutInsets() {
        val insets = PdfViewportGeometry.contentInsets(
            landscape = true,
            barsHidden = true,
            safeLeft = 36,
            safeRight = 28,
            toolbarTop = 168,
            toolbarBottom = 156
        )

        assertEquals(PdfContentInsets(36, 0, 28, 0), insets)
        assertEquals(1016, insets.contentWidth(1080))
    }

    @Test
    fun hiddenToolbarsHonourExplicitSafeTop() {
        val insets = PdfViewportGeometry.contentInsets(
            landscape = false,
            barsHidden = true,
            safeLeft = 24,
            safeRight = 32,
            toolbarTop = 240,
            toolbarBottom = 156,
            hiddenSafeTop = 72
        )

        assertEquals(72, insets.top)
        assertEquals(0, insets.bottom)
        assertEquals(2268, insets.contentHeight(2340))
    }

    @Test
    fun negativeInsetsAreClampedToZero() {
        val insets = PdfViewportGeometry.contentInsets(
            landscape = true,
            barsHidden = false,
            safeLeft = -8,
            safeRight = -4,
            toolbarTop = -100,
            toolbarBottom = -80
        )

        assertEquals(PdfContentInsets(0, 0, 0, 0), insets)
    }
}
