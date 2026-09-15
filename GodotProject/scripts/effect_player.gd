class_name EffectPlayer
extends Node2D

var textures := {}
var effects_enabled := true
var max_active_effects := 4
var active_effects: Array[AnimatedSprite2D] = []

func set_quality_cap(value: int) -> void:
    max_active_effects = clampi(value, 2, 16)

func setup(simulation: CivilizationSimulation) -> void:
    for prefix in ["effect-resource-gathering", "effect-rocket-launch", "effect-weather-disaster"]:
        for frame in range(1, 5):
            var path = "res://assets/processed-" + prefix + "-%02d.png" % frame
            textures[prefix + str(frame)] = TextureCache.get_texture(path)
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
    for item in active_effects:
        if not is_instance_valid(item): active_effects.erase(item)
    if active_effects.size() >= max_active_effects:
        var oldest = active_effects.pop_front()
        if is_instance_valid(oldest): oldest.queue_free()
    var sprite := AnimatedSprite2D.new()
    sprite.sprite_frames = frames
    sprite.animation = "event"
    sprite.position = Vector2(390, 620)
    sprite.scale = Vector2.ONE * 0.25
    sprite.z_index = 45
    add_child(sprite)
    active_effects.append(sprite)
    sprite.play()
    sprite.animation_finished.connect(func(): active_effects.erase(sprite); sprite.queue_free())
