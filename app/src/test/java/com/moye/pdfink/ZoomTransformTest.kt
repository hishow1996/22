package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomTransformTest {

    @Test
    fun anchoredZoomKeepsTappedDocumentPointInPlace() {
        val oldScale = 1f
        val newScale = 1.65f
        val viewport = 900f
        val focus = 235f
        val oldTranslation = 90f
        val nextTranslation = ZoomTransform.anchoredTranslation(
            oldScale, newScale, viewport, focus, oldTranslation)

        val oldDocumentCoordinate =
            (focus - viewport * 0.5f - oldTranslation) / oldScale
        val newDocumentCoordinate =
            (focus - viewport * 0.5f - nextTranslation) / newScale
        assertEquals(oldDocumentCoordinate, newDocumentCoordinate, 0.0001f)
    }

    @Test
    fun normalizedPanSurvivesDifferentViewportBounds() {
        val fraction = ZoomTransform.fraction(120f, 240f)
        assertEquals(0.5f, fraction, 0.0001f)
        assertEquals(360f, ZoomTransform.offset(720f, fraction), 0.0001f)
    }

    @Test
    fun zoomOutCanReachHalfScaleAndValuesAreClampedConsistently() {
        assertEquals(0.5f, PageViewportState(0.1f).normalized().zoom, 0.0001f)
        assertEquals(4f, PageViewportState(8f).normalized().zoom, 0.0001f)
    }

    @Test
    fun smallerPageIsCenteredAndCannotBeDraggedIntoBlankSpace() {
        assertEquals(0f, ZoomTransform.panLimit(1000, 0.5f, 900), 0.0001f)
        assertEquals(150f, ZoomTransform.panLimit(1200, 1f, 900), 0.0001f)
    }

    @Test
    fun inverseMappingKeepsInkUnderTheFingerAtAnyZoomAndPan() {
        val pagePoint = 246f
        val bucket = 0.67f
        val origin = -172f
        val screenPoint = pagePoint * bucket - origin
        assertEquals(pagePoint,
            ZoomTransform.pageCoordinate(screenPoint, origin, bucket), 0.0001f)
    }

    @Test
    fun invalidPageViewportValuesAreSanitized() {
        val safe = PageViewportState(Float.NaN, Float.POSITIVE_INFINITY, -8f).normalized()
        assertEquals(1f, safe.zoom, 0.0001f)
        assertEquals(0f, safe.panX, 0.0001f)
        assertEquals(-1f, safe.panY, 0.0001f)
    }
}
