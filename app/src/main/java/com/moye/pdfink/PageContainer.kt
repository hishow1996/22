package com.moye.pdfink

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** 按 PDF 页宽高比摆页面，保证图片层与笔迹层像素区域完全一致。 */
class PageContainer @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        const val FIT = 0       // contain：整页放进可用区域
        const val WIDTH = 1     // 宽度铺满，高度按比例，可超出容器
        const val FILL = 2      // 智能铺满：按可用高度放大，横向超出可平移

        /** 自动放大上限：宽幅页面不该被 cover 放大到裁掉大半内容。 */
        const val MAX_AUTO_SCALE = 1.8f

        /**
         * P1 智能 cover：以「按宽度铺满」为基准，放大到至少让上下铺满。
         * A4 竖页在 19:9 屏上约 1.27x（有栏）/ 1.53x（隐藏栏），都在上限内，
         * 四边零留白；宽幅页面钳到 1.8x，宁可留一点白也不裁掉整页内容。
         * 抽成纯函数便于 JVM 单测。
         */
        fun fillScale(availW: Int, availH: Int, ratio: Float): Float {
            if (availW <= 0 || availH <= 0 || ratio <= 0f) return 1f
            val baseH = availW / ratio
            if (baseH <= 0f) return 1f
            return (availH / baseH).coerceIn(1f, MAX_AUTO_SCALE)
        }
    }

    var ratio: Float = 0.75f
        set(value) {
            if (value > 0f && value != field) {
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
        var w = availW.coerceAtLeast(1)
        var h = (w / ratio).roundToInt().coerceAtLeast(1)
        if (fitMode == FILL) {
            // 智能铺满：页面高度向可用高对齐，宽度按比例一起放大；
            // 横向超出交给 ZoomHost 的 panLimitX（双指横向拖动可看全）。
            val s = fillScale(availW, availH, ratio)
            w = (availW * s).roundToInt().coerceAtLeast(1)
            h = (w / ratio).roundToInt().coerceAtLeast(1)
        } else if (fitMode == WIDTH) {
            // 按宽度铺满：不再受 availH 约束，超出部分由 ZoomHost 双指上下平移
        } else {
            if (h > availH) {
                h = availH.coerceAtLeast(1)
                w = (h * ratio).roundToInt().coerceAtLeast(1)
            }
            if (w > availW) {
                w = availW.coerceAtLeast(1)
                h = (w / ratio).roundToInt().coerceAtLeast(1)
            }
        }
        val ew = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val eh = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        super.onMeasure(ew, eh)
        setMeasuredDimension(w, h)
    }
}
