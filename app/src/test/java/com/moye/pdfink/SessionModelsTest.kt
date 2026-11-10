package com.moye.pdfink

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class SessionModelsTest {

    @Test
    fun brushPresetRoundTrip() {
        val b = BrushPreset(Tools.HIGHLIGHT, 2, 0, 2, Shapes.OVAL, Layers.CORRECTION)
        val c = BrushPreset.fromJson(JSONObject(b.toJson().toString()))
        assertEquals(Tools.HIGHLIGHT, c.brushTool)
        assertEquals(2, c.color)
        assertEquals(0, c.width)
        assertEquals(2, c.eraser)
        assertEquals(Shapes.OVAL, c.brushShape)
        assertEquals(Layers.CORRECTION, c.layer)
    }

    @Test
    fun docSessionRoundTrip() {
        val d = DocSession(7, 2.25f, Tools.ERASER, Layers.CORRECTION,
            PageFilter.NIGHT, -0.4f, 270, false)
        val c = DocSession.fromJson(JSONObject(d.toJson().toString()))
        assertEquals(7, c.page)
        assertEquals(2.25f, c.zoom, 0.0001f)
        assertEquals(Tools.ERASER, c.brushTool)
        assertEquals(Layers.CORRECTION, c.layer)
        assertEquals(PageFilter.NIGHT, c.tint)
        assertEquals(-0.4f, c.brightness, 0.0001f)
        assertEquals(270, c.rotation)
        assertEquals(false, c.showCorrection)
    }

    @Test
    fun defaultsAreSafeForFirstLaunch() {
        val d = DocSession.fromJson(JSONObject("{}"))
        assertEquals(0, d.page)
        assertEquals(1f, d.zoom, 0.0001f)
        assertEquals(PageFilter.WHITE, d.tint)
        assertEquals(true, d.showCorrection)
    }
}
