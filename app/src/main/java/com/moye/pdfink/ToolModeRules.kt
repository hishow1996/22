package com.moye.pdfink

/**
 * v37：书写模式与工具按钮的边界规则（纯 JVM，便于单测）。
 *
 * 背景：v36 把「工具按钮第二下」统一做成 setBrowseMode(true)，于是用户点套索
 * 想关掉套索，结果整个书写模式被退回了浏览。另一个独立问题是 currentTool 会
 * 从会话 / 进程内 ViewModel 残留 LASSO，按 ⇄ 切进书写模式时直接进圈选。
 *
 * 规则很窄，只解决这两件事：
 *   · 套索不允许被「自动」带进书写模式（⇄ 进入、冷启动恢复会话），一律回默认笔；
 *     用户主动点套索按钮进做题仍然允许（selectTool 会在归一化之后覆盖回来）。
 *   · 关闭某个工具时回到默认笔、留在做题态；退出书写模式只由 ⇄ 负责。
 */
object ToolModeRules {

    /** 工具按钮第二下「关闭这个工具」时回到的默认绘图工具。 */
    fun closeFallback(): Int = Tools.PEN

    /** 进入书写模式 / 恢复会话时，把残留的套索换成默认笔；其余工具原样。 */
    fun entryTool(saved: Int): Int =
        if (saved == Tools.LASSO) closeFallback() else saved
}
