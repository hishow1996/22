package com.moye.pdfink

/**
 * v48：「重做本页」——清掉这一页的作答，留下题目上的标记，好再做一遍。纯逻辑。
 *
 * 当作「作答」清掉的：手写笔迹、直线 / 矩形 / 椭圆 / 箭头、批改章（✓ ✗ ？）。
 * 留下的：文字高亮 / 下划线 / 删除线（读题时划的重点）、答案遮罩、便签。
 * 只动可见图层，隐藏图层里的东西看不见就不碰；整步可撤销。
 */
object RedoPage {

    fun isAnswer(shape: Int): Boolean = when (shape) {
        Shapes.FREE, Shapes.LINE, Shapes.RECT, Shapes.OVAL, Shapes.ARROW,
        Shapes.STAMP_OK, Shapes.STAMP_BAD, Shapes.STAMP_Q -> true
        else -> false
    }

    /** 返回重做后保留的批注（保持原顺序）；没有可清的就返回原列表内容。 */
    fun keep(marks: List<Mark>, isVisible: (Mark) -> Boolean): List<Mark> =
        marks.filter { !(isAnswer(it.shape) && isVisible(it)) }
}
