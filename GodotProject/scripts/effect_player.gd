class_name EffectPlayer
extends Node2D

var textures := {}
var effects_enabled := true

func setup(simulation: CivilizationSimulation) -> void:
    for prefix in ["effect-resource-gathering", "effect-rocket-launch", "effect-weather-disaster"]:
        for frame in range(1, 5):
            var path := "res://assets/processed-" + prefix + "-%02d.png" % frame
            if ResourceLoader.exists(path): textures[prefix + str(frame)] = load(path)
    simulation.events.event_raised.connect(_on_event)

func _on_event(event_name: String, _intensity: float) -> void:
    if not effects_enabled: return
    var prefix := ""
    if event_name == "space_mission": prefix = "effect-rocket-launch"
    elif event_name == "meteor_impact" or event_name == "rain_started": prefix = "effect-weather-disaster"
    elif event_name == "resource_gathered": prefix = "effect-resource-gathering"
    if prefix.is_empty(): return
    var frames := SpriteFrames.new()
    frames.remove_animation("default")
    frames.add_animation("event")
    frames.set_animation_speed("event", 8.0)
    frames.set_animation_loop("event", false)
    for index in range(1, 5):
        if textures.get(prefix + str(index)) != null: frames.add_frame("event", textures[prefix + str(index)])
    if frames.get_frame_count("event") == 0: return
    var sprite := AnimatedSprite2D.new()
    sprite.sprite_frames = frames
    sprite.animation = "event"
    sprite.position = Vector2(390, 620)
    sprite.scale = Vector2.ONE * 0.25
    sprite.z_index = 45
    add_child(sprite)
    sprite.play()
    sprite.animation_finished.connect(sprite.queue_free)
