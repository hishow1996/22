extends Node2D

var simulation: CivilizationSimulation
var world_view: WorldView
var hud: CivilizationHud
var event_fx: EventFx
var starmap: StarMapView

func _ready() -> void:
    simulation = CivilizationSimulation.new(20260915)
    world_view = WorldView.new()
    world_view.name = "WorldView"
    add_child(world_view)
    world_view.setup(simulation)
    simulation.changed.connect(world_view.refresh)
    event_fx = EventFx.new()
    event_fx.name = "EventFx"
    add_child(event_fx)
    event_fx.setup(simulation)
    starmap = StarMapView.new()
    starmap.name = "StarMap"
    add_child(starmap)
    starmap.setup(simulation)
    hud = CivilizationHud.new()
    hud.name = "CivilizationHud"
    add_child(hud)
    hud.setup(simulation, starmap)
