package com.moye.pdfink

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 按天保留最近 3 份批注快照，只依赖 File，便于 JVM 单测。
 * 清除全部前自动留档，杀进程也不会丢掉可撤销的底稿。
 */
class SnapshotKeeper(private val root: File) {

    companion object {
        const val KEEP = 3
        private fun day(): String =
            SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    }

    private fun dir(fp: String): File = File(root, "ink_$fp")

    fun save(fp: String, snap: Map<String, List<Mark>>) {
        if (fp.isEmpty() || snap.isEmpty()) return
        val d = dir(fp)
        if (!d.exists() && !d.mkdirs()) return
        val pages = JSONObject()
        for ((k, v) in snap) {
            val a = JSONArray()
            for (m in v) a.put(m.toJson())
            pages.put(k, a)
        }
        val o = JSONObject().put("day", day()).put("pages", pages)
        val f = File(d, day() + ".json")
        try {
            val tmp = File(d, day() + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) { return }
        prune(d)
    }

    fun days(fp: String): List<String> {
        val d = dir(fp)
        if (!d.exists()) return emptyList()
        return d.listFiles()
            ?.filter { it.name.endsWith(".json") && !it.name.endsWith(".tmp") }
            ?.map { it.name.removeSuffix(".json") }
            ?.sortedDescending() ?: emptyList()
    }

    fun restore(fp: String, stamp: String): Map<String, List<Mark>> {
        if (!stamp.matches(Regex("\\d{8}"))) return emptyMap()
        val f = File(dir(fp), "$stamp.json")
        if (!f.exists()) return emptyMap()
        return try {
            val pages = JSONObject(f.readText()).optJSONObject("pages") ?: JSONObject()
            val out = HashMap<String, List<Mark>>()
            val keys = pages.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val a = pages.optJSONArray(k) ?: JSONArray()
                val l = ArrayList<Mark>(a.length())
                for (i in 0 until a.length()) {
                    try { l.add(Mark.fromJson(a.getJSONObject(i))) } catch (e: Exception) { }
                }
                out[k] = l
            }
            out
        } catch (e: Exception) { emptyMap() }
    }

    private fun prune(d: File) {
        val all = d.listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.sortedByDescending { it.name } ?: return
        for (i in KEEP until all.size) {
            try { all[i].delete() } catch (e: Exception) { }
        }
    }
}
