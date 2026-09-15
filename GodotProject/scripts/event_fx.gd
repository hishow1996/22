class_name EventFx
extends Node2D

var notices: Array[Dictionary] = []
var weather := "晴朗"

func setup(simulation: CivilizationSimulation) -> void:
    simulation.events.event_raised.connect(_on_event)
    simulation.changed.connect(func(): weather = simulation.weather; queue_redraw())

func _on_event(event_name: String, intensity: float) -> void:
    notices.append({"name": event_name, "life": 2.2, "intensity": intensity})
    queue_redraw()

func _process(delta: float) -> void:
    for notice in notices:
        notice.life -= delta
    notices = notices.filter(func(item: Dictionary): return item.life > 0.0)
    queue_redraw()

func _draw() -> void:
    var center := Vector2(384, 640)
    if weather == "降雨":
        for index in 28:
            var x := float((index * 83) % 760)
            var y := float((index * 47) % 980) + 90.0
            draw_line(Vector2(x, y), Vector2(x - 7, y + 18), Color(0.45, 0.75, 1.0, 0.42), 2.0)
    elif weather == "大雪":
        for index in 24:
            var snow_x := float((index * 97) % 760)
            var snow_y := float((index * 61) % 980) + 90.0
            draw_circle(Vector2(snow_x, snow_y), 3.0, Color(0.88, 0.95, 1.0, 0.72))
    elif weather == "多云":
        draw_rect(Rect2(0, 70, 768, 980), Color(0.2, 0.25, 0.38, 0.12))
    elif weather == "陨石灾害":
        draw_circle(center, 42.0 + sin(Time.get_ticks_msec() * 0.01) * 8.0, Color(0.9, 0.25, 0.15, 0.2), false, 6.0)
    for index in notices.size():
        var notice: Dictionary = notices[index]
        var radius := (2.2 - notice.life) * 70.0
        var alpha := min(1.0, notice.life)
        var color := Color("#ffd35a", alpha)
        if str(notice.name).contains("meteor"): color = Color("#ed6b57", alpha)
        elif str(notice.name).contains("space"): color = Color("#8fe8ff", alpha)
        draw_arc(center, radius + index * 12.0, 0.0, TAU, 32, color, 3.0)
