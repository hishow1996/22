class_name StarMapView
extends Control

var simulation: CivilizationSimulation
var panel: PanelContainer
var active := false
var nodes := {"卫星": Vector2(120, 190), "空间站": Vector2(300, 130), "深空探测": Vector2(490, 210), "载人探索": Vector2(330, 310)}

func setup(source: CivilizationSimulation) -> void:
    simulation = source
    set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    simulation.changed.connect(queue_redraw)
    panel = PanelContainer.new()
    panel.position = Vector2(60, 180)
    panel.size = Vector2(648, 470)
    panel.visible = false
    add_child(panel)
    var title := Label.new()
    title.text = "星图 · 太空任务轨迹"
    title.position = Vector2(20, 16)
    title.add_theme_font_size_override("font_size", 24)
    panel.add_child(title)

func toggle() -> void:
    active = not active
    if panel != null: panel.visible = active
    queue_redraw()

func _draw() -> void:
    if not active or simulation == null: return
    var origin := panel.position
    var center := origin + Vector2(324, 235)
    draw_circle(center, 28, Color("#f4c95d"))
    draw_circle(center, 42, Color(1.0, 0.8, 0.3, 0.18), false, 3.0)
    var ordered := ["卫星", "空间站", "深空探测", "载人探索"]
    var previous := center
    for mission in ordered:
        var point: Vector2 = origin + nodes[mission]
        draw_line(previous, point, Color("#83d5eb", 0.72), 3.0)
        var completed: bool = simulation.space_program.missions.get(mission, false)
        draw_circle(point, 18 if completed else 12, Color("#83d5eb") if completed else Color("#3b496b"))
        draw_string(ThemeDB.fallback_font, point + Vector2(-30, 35), mission, HORIZONTAL_ALIGNMENT_CENTER, 60, 14, Color("#f0f5ff"))
        previous = point
