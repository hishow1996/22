package com.moye.pdfink

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.Text
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v45：扫描页文字识别。整个 App 里只有这个文件依赖 ML Kit（内置中文 + 拉丁识别模型，
 * 不需要 Google Play 服务，离线可用）；以后想换引擎或去掉依赖，只改这一处。
 */
internal object OcrEngine {
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 阻塞识别，只能在后台线程调用（监听回调在主线程，主线程调用会死等）。失败返回 null。 */
    fun recognize(bmp: Bitmap): List<OcrLine>? {
        val latch = CountDownLatch(1)
        var result: Text? = null
        try {
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { result = it; latch.countDown() }
                .addOnFailureListener { latch.countDown() }
        } catch (e: Exception) {
            return null
        }
        if (!latch.await(60, TimeUnit.SECONDS)) return null
        val text = result ?: return null
        val lines = ArrayList<OcrLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val els = ArrayList<OcrElement>()
                for (e in line.elements) {
                    val r = e.boundingBox ?: continue
                    els.add(OcrElement(e.text, r.left.toFloat(), r.top.toFloat(),
                        r.right.toFloat(), r.bottom.toFloat()))
                }
                if (els.isNotEmpty()) lines.add(OcrLine(els))
            }
        }
        return lines
    }
}
