package com.moye.pdfink

/**
 * 一页一套，绝不共享：ViewPager2 同时存在多个 ZoomHost，
 * 用单例会让相邻页的放大倍数 / 平移量互相串。
 *
 * 全部由 ZoomHost.applyTransform() 统一计算：它同时知道宿主视口尺寸
 * (width/height) 与页面基准尺寸 (baseContentW/H)，子 View 只需照抄，
 * 不再各自推算 —— 否则横屏 WIDTH 下页面比宿主高，TiledPageView 会按
 * 自身高度取可见块，只画到前几行、下半页永远看不到。
 */
class Viewport {
    @Volatile var scale = 1f      // 真实倍率（连续）
    @Volatile var bucket = 1f     // 量化档 1 / 1.5 / 2 / 3 / 4
    @Volatile var lw = 0          // 逻辑画布宽 = baseW * bucket
    @Volatile var lh = 0          // 逻辑画布高 = baseH * bucket
    @Volatile var originX = 0f    // 绘制原点：子 View 局部 (0,0) 对应的逻辑 X
    @Volatile var originY = 0f    // 绘制原点：子 View 局部 (0,0) 对应的逻辑 Y
    @Volatile var visLeft = 0f    // 可见逻辑窗左上角（= -逻辑画布在宿主中的偏移）
    @Volatile var visTop = 0f
    @Volatile var visW = 0        // 可见窗口宽（宿主视口）
    @Volatile var visH = 0        // 可见窗口高（宿主视口）
    // origin 用于 canvas.translate，visLeft/visTop 用于挑要渲染的块。
    // 两者不相等：页面基准框在宿主里居中时 oy=(hostH-bh)/2，
    // 横屏 WIDTH「回页顶」下差 364px，混用会把可见窗算到页面外。
}
