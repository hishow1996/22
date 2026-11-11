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
        const val FILL = 2      // 使用最大可用尺寸完整显示整页，不裁切

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
            FIT, FILL -> {
                // FILL 也必须 contain：铺满阅读视口不能以裁切 PDF 页面为代价。
                val size = fitPageSize(availW, availH, safeRatio)
                w = size.first
                h = size.second
            }
            else -> {
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
