package com.moye.pdfink

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * 按 PDF 页宽高比摆放页面，保证底图层与笔迹层使用完全相同的像素区域。
 *
 * FIT / FILL 都完整显示整页；FILL 代表尽可能使用整个阅读视口，
 * 不是把页面放大到视口之外。页面比例与屏幕比例不同时允许自然留白。
 */
class PageContainer @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        const val FIT = 0       // 完整显示整页，宽高都不超过阅读视口
        const val WIDTH = 1     // 页面宽度适配视口，高度可滚动
        const val FILL = 2      // v29：严格铺满阅读视口，允许横向裁切 + 平移

        /**
         * 在给定视口内按原始宽高比取得最大完整页面尺寸。
         * 返回尺寸始终不超过视口；整数像素取整产生的误差不改变页面比例。
         */
        fun fitPageSize(availW: Int, availH: Int, ratio: Float): Pair<Int, Int> {
            if (availW <= 0 || availH <= 0 || !ratio.isFinite() || ratio <= 0f) {
                return 1 to 1
            }
            var w = availW
            var h = (w / ratio).roundToInt().coerceAtLeast(1)
            if (h > availH) {
                h = availH
                w = (h * ratio).roundToInt().coerceAtLeast(1)
            }
            if (w > availW) {
                w = availW
                h = (w / ratio).roundToInt().coerceAtLeast(1)
            }
            return w.coerceIn(1, availW) to h.coerceIn(1, availH)
        }

        /** v29：严格 cover 的防御上限，只防异常 ratio，不制造留白。 */
        const val MAX_FILL_SCALE = 8f

        /**
         * v29：严格 cover —— 页面至少铺满一个方向，另一方向超出可平移，
         * 四边永远零留白。用户口径「有栏铺满栏间、隐藏栏铺满整屏」。
         *
         * s = max(1, availH·ratio / availW)：
         *  - A4 竖页 → w = availH·ratio（横向略超出），h = availH（纵向铺满）
         *  - 细长页   → w = availW（横向铺满），h = availW/ratio（纵向超出可拖）
         *  - 宽幅页   → h = availH（纵向铺满），横向裁切较多，可切「适应屏幕」
         */
        fun fillScaleExact(availW: Int, availH: Int, ratio: Float): Float {
            if (availW <= 0 || availH <= 0 ||
                !ratio.isFinite() || ratio <= 0f) return 1f
            val baseH = availW / ratio
            if (baseH <= 0f || !baseH.isFinite()) return 1f
            return maxOf(1f, availH / baseH).coerceAtMost(MAX_FILL_SCALE)
        }
    }

    var ratio: Float = 0.75f
        set(value) {
            if (value.isFinite() && value > 0f && value != field) {
                field = value
                requestLayout()
            }
        }

    var fitMode: Int = FIT
        set(value) {
            if (value != field) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val availH = MeasureSpec.getSize(heightMeasureSpec)
        val safeRatio = ratio.takeIf { it.isFinite() && it > 0f } ?: 0.75f
        var w = availW.coerceAtLeast(1)
        var h = (w / safeRatio).roundToInt().coerceAtLeast(1)

        when (fitMode) {
            WIDTH -> {
                // 按宽度适配；长页面保留完整高度，由阅读视口滚动查看。
            }
            FILL -> {
                // v29：严格 cover，页高严格等于可用高（有栏=栏间 / 隐藏栏=整屏）。
                // 与并行提交新增的 fitPageSize(contain) 并存：FIT 仍完整显示整页，
                // 只有用户要的「铺满屏幕」才裁切 + 平移。
                val sc = fillScaleExact(availW, availH, safeRatio)
                w = (availW * sc).roundToInt().coerceAtLeast(1)
                h = (w / safeRatio).roundToInt().coerceAtLeast(1)
            }
            else -> {
                // FIT 及未知模式：完整显示整页，不裁切（并行提交的 contain 几何）
                val size = fitPageSize(availW, availH, safeRatio)
                w = size.first
                h = size.second
            }
        }

        val ew = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val eh = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        super.onMeasure(ew, eh)
        setMeasuredDimension(w, h)
    }
}
