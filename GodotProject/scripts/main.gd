extends Node2D

var simulation: CivilizationSimulation
var world_view: WorldView
var hud: CivilizationHud

func _ready() -> void:
    simulation = CivilizationSimulation.new(20260915)
    world_view = WorldView.new()
    world_view.name = "WorldView"
    add_child(world_view)
    world_view.setup(simulation)
    simulation.changed.connect(world_view.refresh)
    hud = CivilizationHud.new()
    hud.name = "CivilizationHud"
    add_child(hud)
    hud.setup(simulation)
