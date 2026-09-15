class_name WorldView
extends Node2D

const TILE_SIZE := 12.0
var simulation: CivilizationSimulation
var sprites: Dictionary = {}
var palette := [Color("#24517a"), Color("#6fa85c"), Color("#9b7048"), Color("#2e7049"), Color("#707b91"), Color("#3d7191")]

func setup(source: CivilizationSimulation) -> void:
    simulation = source
    queue_redraw()

func _draw() -> void:
    if simulation == null: return
    var origin := Vector2(8, 72)
    for y in simulation.HEIGHT:
        for x in simulation.WIDTH:
            var index := y * simulation.WIDTH + x
            var color := palette[simulation.terrain[index]]
            draw_rect(Rect2(origin + Vector2(x, y) * TILE_SIZE, Vector2(TILE_SIZE + 0.4, TILE_SIZE + 0.4)), color)
    for building in simulation.buildings:
        var pos := origin + Vector2(float(building.x), float(building.y)) * TILE_SIZE
        var color := Color("#f3a23b") if building.type == "篝火" else Color("#d5c47a")
        if building.type == "发射场": color = Color("#b9e7ef")
        draw_rect(Rect2(pos + Vector2(1, 1), Vector2(10, 10)), Color("#23203d"))
        draw_rect(Rect2(pos + Vector2(3, 3), Vector2(6, 6)), color)
        if building.type == "篝火":
            draw_circle(pos + Vector2(6, 5), 2.5, Color("#fff29a"))
    var center := origin + Vector2(32, 48) * TILE_SIZE
    draw_circle(center, 5, Color("#f6dfb0"))
    draw_circle(center + Vector2(14, -8), 4, Color("#f6dfb0"))

func refresh() -> void:
    queue_redraw()
