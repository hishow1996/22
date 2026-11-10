package com.moye.pdfink

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat

/**
 * 荣耀 10 是 19:9 刘海屏，横屏与竖屏都要用。
 *
 * - Android 9 / EMUI 9.x：DisplayCutout 的 safeInset 已按屏幕方向换算过，
 *   竖屏时刘海在顶部，必须取 safeInsetTop，否则会压住顶栏。
 * - Android 8.1 / EMUI 8.1：没有平台 cutout，走华为私有 HwNotchSizeUtil。
 *   getNotchSize() 返回竖屏下 [刘海宽, 刘海高]；旋转到横屏后刘海贴左边，
 *   占的横向宽度其实是「高」，因此统一取 [1] 作为避让深度，取 [0] 会大得离谱。
 * - 华为「隐藏显示区域」开关打开时系统已代我们限制内容，App 不再重复补边。
 *
 * 不声明 notch_support meta-data：不使用刘海区显示内容，只把工具条、
 * 页面、翻页热区与悬浮键推进安全区。
 */
object NotchCompat {

    private const val HUAWEI_NOTCH_STATUS = "display_notch_status"
    private const val HW_NOTCH_CLASS = "com.huawei.android.util.HwNotchSizeUtil"

    /** 华为「隐藏显示区域」开关：1 = 已隐藏，此时系统已代我们处理安全区。 */
    fun huaweiNotchHidden(ctx: Context): Boolean = try {
        Settings.Secure.getInt(ctx.contentResolver, HUAWEI_NOTCH_STATUS, 0) == 1
    } catch (e: Exception) {
        false
    }

    /** 华为 O 版本私有接口：返回需要避让的刘海深度（px），失败为 0。 */
    private fun huaweiNotchDepth(ctx: Context): Int = try {
        val c = Class.forName(HW_NOTCH_CLASS)
        val has = c.getMethod("hasNotch").invoke(null) as? Boolean ?: false
        if (!has) {
            0
        } else {
            val size = c.getMethod("getNotchSize").invoke(null) as? IntArray
            ((size?.getOrNull(1) ?: size?.getOrNull(0) ?: 0)).coerceAtLeast(0)
        }
    } catch (e: Exception) {
        0
    }

    /** 返回 [left, top, right, bottom] 四个方向需要额外让出的像素。 */
    fun safeInsets(ctx: Context, ins: WindowInsetsCompat): IntArray {
        if (huaweiNotchHidden(ctx)) return intArrayOf(0, 0, 0, 0)

        val cut = ins.displayCutout
        if (cut != null) {
            return intArrayOf(
                cut.safeInsetLeft, cut.safeInsetTop,
                cut.safeInsetRight, cut.safeInsetBottom
            )
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            val d = huaweiNotchDepth(ctx)
            if (d > 0) {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                val rot = wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
                return when (rot) {
                    Surface.ROTATION_0 -> intArrayOf(0, d, 0, 0)
                    Surface.ROTATION_180 -> intArrayOf(0, 0, 0, d)
                    Surface.ROTATION_90 -> intArrayOf(d, 0, 0, 0)
                    Surface.ROTATION_270 -> intArrayOf(0, 0, d, 0)
                    else -> intArrayOf(0, 0, 0, 0)
                }
            }
        }
        return intArrayOf(0, 0, 0, 0)
    }

    /** 兼容旧调用：只取左右。 */
    fun safeHorizontal(ctx: Context, ins: WindowInsetsCompat): Pair<Int, Int> {
        val a = safeInsets(ctx, ins)
        return a[0] to a[2]
    }
}
