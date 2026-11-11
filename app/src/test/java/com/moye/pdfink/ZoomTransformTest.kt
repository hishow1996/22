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
    fun continuousDisplayScaleIsIndependentFromTileResolution() {
        val actualScale = 0.83f
        val renderScale = TiledPageView.bucket(actualScale)
        // 0.83 落在 [0.74,0.89) 档 → 0.8（与 zoom buckets 区间语义一致）
        assertEquals(0.8f, renderScale, 0.0001f)
        assertEquals(actualScale / renderScale,
            ZoomTransform.renderToDisplayFactor(actualScale, renderScale), 0.0001f)
        assertEquals(1f / ZoomTransform.renderToDisplayFactor(actualScale, renderScale),
            ZoomTransform.displayToRender(1f, actualScale, renderScale), 0.0001f)
    }

    @Test
    fun invalidPageViewportValuesAreSanitized() {
        val safe = PageViewportState(Float.NaN, Float.POSITIVE_INFINITY, -8f).normalized()
        assertEquals(1f, safe.zoom, 0.0001f)
        assertEquals(0f, safe.panX, 0.0001f)
        assertEquals(-1f, safe.panY, 0.0001f)
    }
    @Test
    fun canvasTranslationAndTileWindowStayAlignedAcrossBuckets() {
        val hostSize = 1080f
        val baseSize = 700f
        val baseOrigin = (hostSize - baseSize) * 0.5f
        val translation = 83f
        val cases = listOf(
            0.83f to 0.8f,
            1.3f to 1.2f,
            1.65f to 1.6f,
            2.3f to 2.2f
        )

        for ((actualScale, bucket) in cases) {
            val displayFactor =
                ZoomTransform.renderToDisplayFactor(actualScale, bucket)
            val visibleOrigin =
                (hostSize - baseSize * actualScale) * 0.5f + translation
            val canvasOffset = ZoomTransform.canvasTranslationOffset(
                baseOrigin, visibleOrigin
            )

            // 页面边缘应落在连续缩放计算出的屏幕位置。
            assertEquals(visibleOrigin, baseOrigin - canvasOffset, 0.0001f)

            // translate 保持屏幕单位；可见 tile 起点按显示倍率反算后应落在屏幕边缘。
            val visibleRenderLeft = -visibleOrigin / displayFactor
            assertEquals(
                0f,
                baseOrigin - canvasOffset + visibleRenderLeft * displayFactor,
                0.0001f
            )
        }
    }

}
