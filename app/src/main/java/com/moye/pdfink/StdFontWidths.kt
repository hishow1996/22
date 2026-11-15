package com.moye.pdfink

/**
 * 标准 14 字体里最常见的几款（Helvetica/Arial、Times、Courier）在 ASCII 32..126 的
 * 字宽（1/1000 em）。很多生成器对标准字体不写 /Widths，没有它选字框会和文字对不齐。
 */
internal object StdFontWidths {
    private val HELV = floatArrayOf(
        278f, 278f, 355f, 556f, 556f, 889f, 667f, 191f, 333f, 333f, 389f, 584f,
        278f, 333f, 278f, 278f, 556f, 556f, 556f, 556f, 556f, 556f, 556f, 556f,
        556f, 556f, 278f, 278f, 584f, 584f, 584f, 556f, 1015f, 667f, 667f, 722f,
        722f, 667f, 611f, 778f, 722f, 278f, 500f, 667f, 556f, 833f, 722f, 778f,
        667f, 778f, 722f, 667f, 611f, 722f, 667f, 944f, 667f, 667f, 611f, 278f,
        278f, 278f, 469f, 556f, 333f, 556f, 556f, 500f, 556f, 556f, 278f, 556f,
        556f, 222f, 222f, 500f, 222f, 833f, 556f, 556f, 556f, 556f, 333f, 500f,
        278f, 556f, 500f, 722f, 500f, 500f, 500f, 334f, 260f, 334f, 584f
    )
    private val HELVB = floatArrayOf(
        278f, 333f, 474f, 556f, 556f, 889f, 722f, 238f, 333f, 333f, 389f, 584f,
        278f, 333f, 278f, 278f, 556f, 556f, 556f, 556f, 556f, 556f, 556f, 556f,
        556f, 556f, 333f, 333f, 584f, 584f, 584f, 611f, 975f, 722f, 722f, 722f,
        722f, 667f, 611f, 778f, 722f, 278f, 556f, 722f, 611f, 833f, 722f, 778f,
        667f, 778f, 722f, 667f, 611f, 722f, 667f, 944f, 667f, 667f, 611f, 333f,
        278f, 333f, 584f, 556f, 333f, 556f, 611f, 556f, 611f, 556f, 333f, 611f,
        611f, 278f, 278f, 556f, 278f, 889f, 611f, 611f, 611f, 611f, 389f, 556f,
        333f, 611f, 556f, 778f, 556f, 556f, 500f, 389f, 280f, 389f, 584f
    )
    private val TIMES = floatArrayOf(
        250f, 333f, 408f, 500f, 500f, 833f, 778f, 180f, 333f, 333f, 500f, 564f,
        250f, 333f, 250f, 278f, 500f, 500f, 500f, 500f, 500f, 500f, 500f, 500f,
        500f, 500f, 278f, 278f, 564f, 564f, 564f, 444f, 921f, 722f, 667f, 667f,
        722f, 611f, 556f, 722f, 722f, 333f, 389f, 722f, 611f, 889f, 722f, 722f,
        556f, 722f, 667f, 556f, 611f, 722f, 722f, 944f, 722f, 722f, 611f, 333f,
        278f, 333f, 469f, 500f, 333f, 444f, 500f, 444f, 500f, 444f, 333f, 500f,
        500f, 278f, 278f, 500f, 278f, 778f, 500f, 500f, 500f, 500f, 333f, 389f,
        278f, 500f, 500f, 722f, 500f, 500f, 444f, 480f, 200f, 480f, 541f
    )
    private val TIMESB = floatArrayOf(
        250f, 333f, 555f, 500f, 500f, 1000f, 833f, 278f, 333f, 333f, 500f, 570f,
        250f, 333f, 250f, 278f, 500f, 500f, 500f, 500f, 500f, 500f, 500f, 500f,
        500f, 500f, 333f, 333f, 570f, 570f, 570f, 500f, 930f, 722f, 667f, 722f,
        722f, 667f, 611f, 778f, 778f, 389f, 500f, 778f, 667f, 944f, 722f, 778f,
        611f, 778f, 722f, 556f, 667f, 722f, 722f, 1000f, 722f, 722f, 667f, 333f,
        278f, 333f, 581f, 500f, 333f, 500f, 556f, 444f, 556f, 444f, 333f, 500f,
        556f, 278f, 333f, 556f, 278f, 833f, 556f, 500f, 556f, 556f, 444f, 389f,
        333f, 556f, 500f, 722f, 500f, 500f, 444f, 394f, 220f, 394f, 520f
    )
    private val TIMESI = floatArrayOf(
        250f, 333f, 420f, 500f, 500f, 833f, 778f, 214f, 333f, 333f, 500f, 675f,
        250f, 333f, 250f, 278f, 500f, 500f, 500f, 500f, 500f, 500f, 500f, 500f,
        500f, 500f, 333f, 333f, 675f, 675f, 675f, 500f, 920f, 611f, 611f, 667f,
        722f, 611f, 611f, 722f, 722f, 333f, 444f, 667f, 556f, 833f, 667f, 722f,
        611f, 722f, 611f, 500f, 556f, 722f, 611f, 833f, 611f, 556f, 556f, 389f,
        278f, 389f, 422f, 500f, 333f, 500f, 500f, 444f, 500f, 444f, 278f, 500f,
        500f, 278f, 278f, 444f, 278f, 722f, 500f, 500f, 500f, 500f, 389f, 389f,
        278f, 500f, 444f, 667f, 444f, 444f, 389f, 400f, 275f, 400f, 541f
    )
    private val TIMESBI = floatArrayOf(
        250f, 389f, 555f, 500f, 500f, 833f, 778f, 278f, 333f, 333f, 500f, 570f,
        250f, 333f, 250f, 278f, 500f, 500f, 500f, 500f, 500f, 500f, 500f, 500f,
        500f, 500f, 333f, 333f, 570f, 570f, 570f, 500f, 832f, 667f, 667f, 667f,
        722f, 667f, 667f, 722f, 778f, 389f, 500f, 667f, 611f, 889f, 722f, 722f,
        611f, 722f, 667f, 556f, 611f, 722f, 667f, 889f, 667f, 611f, 611f, 333f,
        278f, 333f, 570f, 500f, 333f, 500f, 500f, 444f, 500f, 444f, 333f, 500f,
        556f, 278f, 278f, 500f, 278f, 778f, 556f, 500f, 500f, 500f, 389f, 389f,
        278f, 556f, 444f, 667f, 500f, 444f, 389f, 348f, 220f, 348f, 570f
    )
    private val COURIER = FloatArray(95) { 600f }

    /** 按 /BaseFont 名挑一张表；认不出返回 null。 */
    fun forBaseFont(baseFont: String?): FloatArray? {
        if (baseFont == null) return null
        val l = baseFont.substringAfter('+').lowercase()
        val bold = l.contains("bold")
        val italic = l.contains("italic") || l.contains("oblique")
        return when {
            l.contains("courier") -> COURIER
            l.contains("times") -> when {
                bold && italic -> TIMESBI
                bold -> TIMESB
                italic -> TIMESI
                else -> TIMES
            }
            l.contains("helvetica") || l.contains("arial") -> if (bold) HELVB else HELV
            else -> null
        }
    }
}
