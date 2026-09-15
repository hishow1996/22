class_name EventFx
extends Node2D

var notices: Array[Dictionary] = []

func setup(simulation: CivilizationSimulation) -> void:
    simulation.events.event_raised.connect(_on_event)

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
    for index in notices.size():
        var notice: Dictionary = notices[index]
        var radius := (2.2 - notice.life) * 70.0
        var alpha := min(1.0, notice.life)
        var color := Color("#ffd35a", alpha)
        if str(notice.name).contains("meteor"): color = Color("#ed6b57", alpha)
        elif str(notice.name).contains("space"): color = Color("#8fe8ff", alpha)
        draw_arc(center, radius + index * 12.0, 0.0, TAU, 32, color, 3.0)
