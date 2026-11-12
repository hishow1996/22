package com.moye.pdfink

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * 阅读区容器：内部放两套「翻页」实现，对外接口与 ViewPager2 一致，
 * MainActivity 几乎不用区分当前是哪一套。
 *
 *  - 逐页翻页（ViewPager2）：横屏、适应屏幕、适应宽度、双页，行为完全沿用原来的。
 *  - 连续滚动（竖向 RecyclerView）：竖屏「铺满屏幕」。页宽贴满屏幕，
 *    一页接一页往下排，页间一条细缝，像 WPS / Microsoft 365。
 *
 * 同一个 adapter 同一时刻只挂在其中一套上；切换时先摘后挂并回到原来那一页。
 */
class ReaderPager @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 连续滚动列表。userScroll=false 只挡手指拖动，程序化滚动（翻页键 / 进度条）不受影响。 */
    private class ScrollList(context: Context) : RecyclerView(context) {
        var userScroll = true
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean =
            userScroll && super.onInterceptTouchEvent(e)

        override fun onTouchEvent(e: MotionEvent): Boolean =
            userScroll && super.onTouchEvent(e)
    }

    private val vp = ViewPager2(context)
    private val list = ScrollList(context)
    private val lm = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
    private val callbacks = ArrayList<ViewPager2.OnPageChangeCallback>()
    private val gap = (6 * resources.displayMetrics.density).toInt().coerceAtLeast(2)

    private var continuousOn = false
    private var cur = 0
    private var ad: RecyclerView.Adapter<*>? = null

    init {
        list.layoutManager = lm
        list.itemAnimator = null
        list.setItemViewCacheSize(2)
        list.visibility = View.GONE
        list.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
            ) {
                outRect.bottom = gap
            }
        })
        addView(vp, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (!continuousOn) dispatchSelected(position)
            }
        })
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // dy==0 是布局完成后的空回调（例如程序化定位），不据此改当前页
                if (continuousOn && dy != 0) updateCurrentFromScroll()
            }
        })
    }

    val isContinuous: Boolean get() = continuousOn

    var adapter: RecyclerView.Adapter<*>?
        get() = ad
        set(value) {
            ad = value
            cur = 0
            if (continuousOn) list.adapter = value else vp.adapter = value
        }

    /** 当前页（连续滚动时是盖住列表中线的那一页）。 */
    var currentItem: Int
        get() = if (continuousOn) cur else vp.currentItem
        set(value) = setCurrentItem(value, false)

    var orientation: Int
        get() = vp.orientation
        set(value) { vp.orientation = value }

    /** 关掉后手指不能拖动翻页 / 滚动；翻页键、进度条仍可用。 */
    var isUserInputEnabled: Boolean
        get() = vp.isUserInputEnabled
        set(value) {
            vp.isUserInputEnabled = value
            list.userScroll = value
        }

    var offscreenPageLimit: Int
        get() = vp.offscreenPageLimit
        set(value) { vp.offscreenPageLimit = value }

    fun registerOnPageChangeCallback(callback: ViewPager2.OnPageChangeCallback) {
        callbacks.add(callback)
    }

    fun setEdgeEffectFactory(factory: RecyclerView.EdgeEffectFactory) {
        list.edgeEffectFactory = factory
        (vp.getChildAt(0) as? RecyclerView)?.edgeEffectFactory = factory
    }

    /** 切换逐页翻页 / 连续滚动；adapter 会换挂到另一套上，并回到原来那一页。 */
    fun setContinuous(on: Boolean) {
        if (on == continuousOn) return
        val keep = currentItem
        continuousOn = on
        val a = ad
        if (on) {
            vp.adapter = null
            list.adapter = a
        } else {
            list.adapter = null
            vp.adapter = a
        }
        vp.visibility = if (on) View.GONE else View.VISIBLE
        list.visibility = if (on) View.VISIBLE else View.GONE
        cur = keep
        val n = a?.itemCount ?: 0
        if (n > 0) {
            val p = keep.coerceIn(0, n - 1)
            cur = p
            if (on) lm.scrollToPositionWithOffset(p, 0) else vp.setCurrentItem(p, false)
        }
    }

    fun setCurrentItem(item: Int, smooth: Boolean) {
        if (!continuousOn) {
            vp.setCurrentItem(item, smooth)
            return
        }
        val n = ad?.itemCount ?: 0
        if (n <= 0) return
        val p = item.coerceIn(0, n - 1)
        val changed = p != cur
        cur = p
        if (smooth) {
            val scroller = object : LinearSmoothScroller(context) {
                // 翻页键：目标页顶对齐到列表顶，而不是「露出来就行」
                override fun getVerticalSnapPreference(): Int = SNAP_TO_START
            }
            scroller.targetPosition = p
            lm.startSmoothScroll(scroller)
        } else {
            lm.scrollToPositionWithOffset(p, 0)
        }
        if (changed) dispatchSelected(p)
    }

    private fun updateCurrentFromScroll() {
        val n = list.childCount
        if (n == 0) return
        val tops = IntArray(n)
        val bottoms = IntArray(n)
        for (i in 0 until n) {
            val c = list.getChildAt(i)
            tops[i] = c.top
            bottoms[i] = c.bottom
        }
        val hit = ContinuousMath.indexAtCenter(tops, bottoms, list.height / 2, gap)
        if (hit < 0) return
        val position = list.getChildAdapterPosition(list.getChildAt(hit))
        if (position == RecyclerView.NO_POSITION || position == cur) return
        cur = position
        dispatchSelected(position)
    }

    private fun dispatchSelected(position: Int) {
        for (cb in callbacks.toList()) cb.onPageSelected(position)
    }
}
