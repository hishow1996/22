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
        if (fitMode == WIDTH) {
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
