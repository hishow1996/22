package com.moye.pdfink

import android.content.Context
import org.json.JSONObject

/**
 * 仓库层：全局开关、每卷会话、笔刷预设、最近打开的卷子。
 * 与 UI 无耦合，MainActivity 只通过 MainViewModel 持有它。
 */
class SessionRepository(ctx: Context) {

    // 仓库会被 ViewModel 持有，只拿 applicationContext，避免泄漏 Activity
    private val app = ctx.applicationContext
    private val p = app.getSharedPreferences("moye", Context.MODE_PRIVATE)
    private val s = app.getSharedPreferences("moye_sessions", Context.MODE_PRIVATE)

    var lastUri: String?
        get() = p.getString("uri", null)
        set(v) { p.edit().putString("uri", v).apply() }

    // ---- 全局开关 ----
    fun rotation(): Int = p.getInt("pageRotation", 0).coerceIn(0, 270)
    fun setRotation(v: Int) { p.edit().putInt("pageRotation", v).apply() }

    fun volumeFlip(): Boolean = p.getBoolean("volumeFlip", true)
    fun setVolumeFlip(v: Boolean) { p.edit().putBoolean("volumeFlip", v).apply() }

    fun keepAwake(): Boolean = p.getBoolean("keepAwake", true)
    fun setKeepAwake(v: Boolean) { p.edit().putBoolean("keepAwake", v).apply() }

    fun followSystemDark(): Boolean = p.getBoolean("followSystemDark", false)
    fun setFollowSystemDark(v: Boolean) {
        p.edit().putBoolean("followSystemDark", v).apply()
    }

    // ---- 每卷会话 ----
    fun session(fp: String): DocSession = try {
        DocSession.fromJson(JSONObject(s.getString("doc_$fp", "{}") ?: "{}"))
    } catch (e: Exception) { DocSession() }

    fun saveSession(fp: String, v: DocSession) {
        if (fp.isEmpty()) return
        s.edit().putString("doc_$fp", v.toJson().toString()).apply()
    }

    // ---- 笔刷预设 ----
    fun brush(i: Int): BrushPreset? = try {
        val raw = s.getString("brush_$i", null) ?: return null
        BrushPreset.fromJson(JSONObject(raw))
    } catch (e: Exception) { null }

    fun saveBrush(i: Int, v: BrushPreset) {
        s.edit().putString("brush_$i", v.toJson().toString()).apply()
    }

    fun clearBrush(i: Int) { s.edit().remove("brush_$i").apply() }
}
