package com.moye.pdfink

/**
 * v48：错题本要导出哪些页。纯 JVM。
 * 打过 ✗ 的页 ∪ 书签页，去重、升序，并丢掉越界页码。
 */
object WrongBook {
    fun pages(badPages: Collection<Int>, bookmarks: Collection<Int>, count: Int): List<Int> =
        (badPages + bookmarks).filter { it in 0 until count }.distinct().sorted()
}
