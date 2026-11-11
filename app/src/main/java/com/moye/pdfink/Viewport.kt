package com.moye.pdfink

/** A page's layout rectangle expressed in its ZoomHost coordinates. */
data class PageFrame(
    val left: Float,
    val top: Float,
    val width: Int,
    val height: Int
) {
    val right: Float get() = left + width.coerceAtLeast(0)
    val bottom: Float get() = top + height.coerceAtLeast(0)
}

/** Geometry computed independently for one page, from the shared spread gesture. */
data class PageViewportMapping(
    val logicalWidth: Int,
    val logicalHeight: Int,
    val originX: Float,
    val originY: Float,
    val visibleLeft: Float,
    val visibleTop: Float,
    val visibleWidth: Int,
    val visibleHeight: Int
)

/**
 * One page / spread's drawing transform.
 * The actual zoom and pan are shared by a spread, but each page owns a distinct instance.
 */
class Viewport {
    @Volatile var scale = 1f
    @Volatile var bucket = 1f
    @Volatile var lw = 0
    @Volatile var lh = 0
    @Volatile var originX = 0f
    @Volatile var originY = 0f
    @Volatile var visLeft = 0f
    @Volatile var visTop = 0f
    @Volatile var visW = 0
    @Volatile var visH = 0
}

/** Allowed horizontal or vertical translation after applying the shared zoom. */
data class TranslationRange(
    val minimum: Float = 0f,
    val maximum: Float = 0f
) {
    val hasMovement: Boolean get() = maximum - minimum > 0.5f
    val maxAbs: Float get() = maxOf(kotlin.math.abs(minimum), kotlin.math.abs(maximum))

    fun clamp(value: Float): Float = value.coerceIn(minimum, maximum)

    /** Keep normalized -1..1 session values; zero means centered in the allowed range. */
    fun fraction(offset: Float): Float {
        if (!hasMovement) return 0f
        val center = (minimum + maximum) * 0.5f
        val half = (maximum - minimum) * 0.5f
        return ((clamp(offset) - center) / half).coerceIn(-1f, 1f)
    }

    fun offset(fraction: Float): Float {
        if (!hasMovement) return 0f
        val center = (minimum + maximum) * 0.5f
        val half = (maximum - minimum) * 0.5f
        return center + half * fraction.coerceIn(-1f, 1f)
    }
}
