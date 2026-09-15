class_name WorldView
extends Node2D

const TILE_SIZE := 12.0
const TERRAIN_NAMES := ["terrain-ocean", "terrain-grass", "terrain-dirt", "terrain-forest", "terrain-mountain", "terrain-river"]
var simulation: CivilizationSimulation
var building_nodes: Array[Node2D] = []
var population_nodes: Array[Node2D] = []
var animal_nodes: Array[Sprite2D] = []
var textures := {}
var pulse := 0.0
var camera_zoom := 1.0
var dragging := false
var last_pointer := Vector2.ZERO
var redraw_pending := true
var redraw_cooldown := 0.0
var last_visual_signature := ""
var palette := [Color("#24517a"), Color("#6fa85c"), Color("#9b7048"), Color("#2e7049"), Color("#707b91"), Color("#3d7191")]

func setup(source: CivilizationSimulation) -> void:
    simulation = source
    _load_textures()
    simulation.changed.connect(refresh)
    refresh()

func _unhandled_input(event: InputEvent) -> void:
    if event is InputEventScreenTouch:
        dragging = event.pressed
        last_pointer = event.position
    elif event is InputEventScreenDrag and dragging:
        position += event.relative
        _clamp_position()
    elif event is InputEventMouseButton and event.button_index == MOUSE_BUTTON_LEFT:
        dragging = event.pressed
        last_pointer = event.position
    elif event is InputEventMouseMotion and dragging:
        position += event.relative
        _clamp_position()
    elif event is InputEventMouseButton and event.button_index in [MOUSE_BUTTON_WHEEL_UP, MOUSE_BUTTON_WHEEL_DOWN] and event.pressed:
        var direction := 1.08 if event.button_index == MOUSE_BUTTON_WHEEL_UP else 0.92
        camera_zoom = clamp(camera_zoom * direction, 0.65, 1.7)
        scale = Vector2.ONE * camera_zoom
        _clamp_position()
    elif event is InputEventMagnifyGesture:
        camera_zoom = clamp(camera_zoom * event.factor, 0.65, 1.7)
        scale = Vector2.ONE * camera_zoom
        _clamp_position()

func _clamp_position() -> void:
    position.x = clamp(position.x, -520.0, 260.0)
    position.y = clamp(position.y, -680.0, 160.0)

func _load_textures() -> void:
    var names := ["campfire", "primordial-hut", "agrarian-farm", "farm-tilled", "crop-seedling", "crop-wheat-ripe", "barn", "animal-pasture", "animal-chicken", "animal-sheep", "animal-cow", "crafting-bench", "forge", "anvil", "tool-stone-axe", "tool-stone-pickaxe", "tool-stone-hoe", "industrial-factory", "modern-research-center", "modern-power-grid", "space-launch-site", "space-station", "primordial-settler", "agrarian-farmer", "industrial-engineer", "modern-scientist", "space-astronaut"]
    names.append_array(["terrain-ocean", "terrain-grass", "terrain-dirt", "terrain-forest", "terrain-mountain", "terrain-river"])
    names.append_array(["transition-shoreline", "transition-riverbank", "transition-cobblestone-road", "transition-stone-bridge", "transition-urban-plaza"])
    for name in names:
        var path = "res://assets/processed-" + name + ".png"
        textures[name] = TextureCache.get_texture(path)

func _process(delta: float) -> void:
    pulse += delta
    if simulation != null:
        var light := simulation.environment.light_factor()
        modulate = Color(light, light, light, 1.0)
    redraw_cooldown -= delta
    if redraw_pending and redraw_cooldown <= 0.0:
        queue_redraw()
        redraw_pending = false
        redraw_cooldown = 0.1
    for node in building_nodes:
        if node != null and node.get_meta("kind", "") == "篝火":
            node.scale = Vector2.ONE * (0.92 + sin(pulse * 5.0) * 0.05)
    for index in population_nodes.size():
        var node := population_nodes[index]
        if node != null: node.position.y += sin(pulse * 2.0 + index) * 0.02

func _draw() -> void:
    if simulation == null: return
    var origin := Vector2(8, 72)
    var x_start := clampi(int((-position.x / camera_zoom - origin.x) / TILE_SIZE) - 2, 0, simulation.WIDTH - 1)
    var x_end := clampi(int(((768.0 - position.x) / camera_zoom - origin.x) / TILE_SIZE) + 3, 1, simulation.WIDTH)
    var y_start := clampi(int((-position.y / camera_zoom - origin.y) / TILE_SIZE) - 2, 0, simulation.HEIGHT - 1)
    var y_end := clampi(int(((1365.0 - position.y) / camera_zoom - origin.y) / TILE_SIZE) + 3, 1, simulation.HEIGHT)
    for y in range(y_start, y_end):
        for x in range(x_start, x_end):
            var index := y * simulation.WIDTH + x
            var terrain_texture = textures.get(TERRAIN_NAMES[simulation.terrain[index]])
            var rect := Rect2(origin + Vector2(x, y) * TILE_SIZE, Vector2(TILE_SIZE + 0.4, TILE_SIZE + 0.4))
            if terrain_texture != null:
                draw_texture_rect(terrain_texture, rect, false)
            else:
                draw_rect(rect, palette[simulation.terrain[index]])
    _draw_overlays(origin)
    draw_rect(Rect2(origin, Vector2(simulation.WIDTH, simulation.HEIGHT) * TILE_SIZE), Color("#10152a"), false, 2.0)

func _draw_overlays(origin: Vector2) -> void:
    var road_texture = textures.get("transition-cobblestone-road")
    var bridge_texture = textures.get("transition-stone-bridge")
    var shoreline_texture = textures.get("transition-shoreline")
    var riverbank_texture = textures.get("transition-riverbank")
    if road_texture != null:
        for x in range(10, simulation.WIDTH - 8, 6):
            draw_texture_rect(road_texture, Rect2(origin + Vector2(x, 48) * TILE_SIZE, Vector2(TILE_SIZE, TILE_SIZE)), false)
    if bridge_texture != null:
        draw_texture_rect(bridge_texture, Rect2(origin + Vector2(32, 42) * TILE_SIZE, Vector2(TILE_SIZE, TILE_SIZE)), false)
    for y in range(8, simulation.HEIGHT - 8, 9):
        var index := y * simulation.WIDTH + 2
        if shoreline_texture != null and simulation.terrain[index] != 0:
            draw_texture_rect(shoreline_texture, Rect2(origin + Vector2(2, y) * TILE_SIZE, Vector2(TILE_SIZE, TILE_SIZE)), false)
        if riverbank_texture != null:
            draw_texture_rect(riverbank_texture, Rect2(origin + Vector2(45, y) * TILE_SIZE, Vector2(TILE_SIZE, TILE_SIZE)), false)

func refresh() -> void:
    if simulation == null: return
    var signature := str(simulation.era) + ":" + str(simulation.buildings) + ":" + str(min(10, max(2, int(simulation.population / 8)))) + ":" + str(simulation.agriculture.total_animals()) + ":" + str(simulation.agriculture.ready_count())
    if signature == last_visual_signature: return
    last_visual_signature = signature
    redraw_pending = true
    for node in building_nodes:
        if node != null:
            node.queue_free()
    for node in population_nodes:
        if node != null:
            node.visible = false
    building_nodes.clear()
    var origin := Vector2(8, 72)
    var texture_by_type := {"篝火": "campfire", "木屋": "primordial-hut", "农田": "farm-tilled", "工坊": "industrial-factory", "工厂": "industrial-factory", "研究中心": "modern-research-center", "现代电网": "modern-power-grid", "医院": "modern-research-center", "发射场": "space-launch-site", "空间站": "space-station"}
    for building in simulation.buildings:
        var node := Sprite2D.new()
        var texture_name: String = texture_by_type.get(str(building.type), "primordial-hut")
        node.texture = textures.get(texture_name)
        node.position = origin + Vector2(float(building.x) + 0.5, float(building.y) + 0.5) * TILE_SIZE
        node.scale = Vector2.ONE * (0.065 if texture_name != "campfire" else 0.08)
        node.z_index = 20
        node.set_meta("kind", str(building.type))
        add_child(node); building_nodes.append(node)
    for node in animal_nodes:
        if node != null: node.visible = false
    var animal_types := ["animal-chicken", "animal-sheep", "animal-cow"]
    var animal_count = min(6, simulation.agriculture.total_animals())
    for index in animal_count:
        var animal: Sprite2D = animal_nodes[index] if index < animal_nodes.size() else Sprite2D.new()
        animal.texture = textures.get(animal_types[index % animal_types.size()])
        animal.position = origin + Vector2(42 + (index % 3) * 3.0, 58 + (index / 3) * 3.0) * TILE_SIZE
        animal.scale = Vector2.ONE * 0.022
        animal.z_index = 25
        animal.visible = true
        if index >= animal_nodes.size(): add_child(animal); animal_nodes.append(animal)
    var farm_texture: Texture2D = textures.get("crop-wheat-ripe" if simulation.agriculture.ready_count() > 0 else "crop-seedling")
    if farm_texture != null:
        var farm_sprite := Sprite2D.new()
        farm_sprite.texture = farm_texture
        farm_sprite.position = origin + Vector2(20, 34) * TILE_SIZE
        farm_sprite.scale = Vector2.ONE * 0.035
        farm_sprite.z_index = 24
        add_child(farm_sprite)
        building_nodes.append(farm_sprite)
    var workshop_texture: Texture2D = textures.get("forge" if simulation.era >= 2 else "crafting-bench")
    if workshop_texture != null:
        var workshop := Sprite2D.new()
        workshop.texture = workshop_texture
        workshop.position = origin + Vector2(54, 35) * TILE_SIZE
        workshop.scale = Vector2.ONE * 0.035
        workshop.z_index = 24
        add_child(workshop)
        building_nodes.append(workshop)
    var people = min(10, max(2, int(simulation.population / 8)))
    var unit_name := "primordial-settler"
    if simulation.era == 1: unit_name = "agrarian-farmer"
    elif simulation.era == 2: unit_name = "industrial-engineer"
    elif simulation.era == 3: unit_name = "modern-scientist"
    elif simulation.era >= 4: unit_name = "space-astronaut"
    for index in people:
        var person: Node2D = population_nodes[index] if index < population_nodes.size() else null
        var animation_prefix := "primordial-settler-walk" if simulation.era < 4 else "space-astronaut-walk"
        var frames := _make_frames(animation_prefix)
        if person == null:
            var animated := AnimatedSprite2D.new()
            if frames != null:
                animated.sprite_frames = frames
                animated.animation = "walk"
                animated.play()
                person = animated
            else:
                var sprite := Sprite2D.new()
                sprite.texture = textures.get(unit_name)
                person = sprite
            add_child(person)
            population_nodes.append(person)
        else:
            var animated_person := person as AnimatedSprite2D
            var static_person := person as Sprite2D
            if animated_person != null and frames != null:
                animated_person.sprite_frames = frames
                animated_person.play("walk")
            elif static_person != null:
                static_person.texture = textures.get(unit_name)
        person.visible = true
        person.position = origin + Vector2(27 + (index % 5) * 2.8, 46 + (index / 5) * 3.0) * TILE_SIZE
        person.scale = Vector2.ONE * 0.025
        person.z_index = 30

func _make_frames(prefix: String) -> SpriteFrames:
    var first_path = "res://assets/processed-" + prefix + "-01.png"
    if TextureCache.get_texture(first_path) == null: return null
    var frames := SpriteFrames.new()
    frames.remove_animation("default")
    frames.add_animation("walk")
    frames.set_animation_speed("walk", 6.0)
    frames.set_animation_loop("walk", true)
    for index in range(1, 5):
        var frame_path = "res://assets/processed-" + prefix + "-%02d.png" % index
        frames.add_frame("walk", TextureCache.get_texture(frame_path))
    return frames
