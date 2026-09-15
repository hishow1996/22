extends Node2D

var simulation: CivilizationSimulation
var world_view: WorldView
var hud: CivilizationHud
var event_fx: EventFx
var starmap: StarMapView
var effect_player: EffectPlayer
var diagnostics := CivilizationDiagnostics.new()

func _ready() -> void:
    simulation = CivilizationSimulation.new(20260915)
    diagnostics.run()
    world_view = WorldView.new()
    world_view.name = "WorldView"
    add_child(world_view)
    world_view.setup(simulation)
    simulation.changed.connect(world_view.refresh)
    event_fx = EventFx.new()
    event_fx.name = "EventFx"
    event_fx.z_index = 40
    add_child(event_fx)
    event_fx.setup(simulation)
    effect_player = EffectPlayer.new()
    effect_player.name = "EffectPlayer"
    add_child(effect_player)
    effect_player.setup(simulation)
    starmap = StarMapView.new()
    starmap.name = "StarMap"
    add_child(starmap)
    starmap.setup(simulation)
    hud = CivilizationHud.new()
    hud.name = "CivilizationHud"
    add_child(hud)
    hud.setup(simulation, starmap, effect_player, diagnostics)

func _notification(what: int) -> void:
    if what == NOTIFICATION_APPLICATION_PAUSED:
        simulation.paused = true
        SaveManager.save_game(simulation)
    elif what == NOTIFICATION_APPLICATION_RESUMED:
        event_fx.queue_redraw()
    elif what == NOTIFICATION_WM_GO_BACK_REQUEST:
        if hud != null and hud.handle_back_request(): return
        SaveManager.save_game(simulation)
        get_tree().quit()
