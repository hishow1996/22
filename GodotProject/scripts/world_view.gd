class_name WorldView
extends Node2D

const TILE_SIZE := 12.0
var simulation: CivilizationSimulation
var building_nodes: Array[Sprite2D] = []
var population_nodes: Array[Sprite2D] = []
var textures := {}
var pulse := 0.0
var palette := [Color("#24517a"), Color("#6fa85c"), Color("#9b7048"), Color("#2e7049"), Color("#707b91"), Color("#3d7191")]

func setup(source: CivilizationSimulation) -> void:
    simulation = source
    _load_textures()
    simulation.changed.connect(refresh)
    refresh()

func _load_textures() -> void:
    var names := ["campfire", "primordial-hut", "agrarian-farm", "industrial-factory", "modern-research-center", "space-launch-site", "primordial-settler"]
    for name in names:
        var path := "res://assets/processed-" + name + ".png"
        if ResourceLoader.exists(path): textures[name] = load(path)

func _process(delta: float) -> void:
    pulse += delta
    for node in building_nodes:
        if node != null and node.get_meta("kind", "") == "篝火":
            node.scale = Vector2.ONE * (0.92 + sin(pulse * 5.0) * 0.05)
    for index in population_nodes.size():
        var node := population_nodes[index]
        if node != null: node.position.y += sin(pulse * 2.0 + index) * 0.02

func _draw() -> void:
    if simulation == null: return
    var origin := Vector2(8, 72)
    for y in simulation.HEIGHT:
        for x in simulation.WIDTH:
            var index := y * simulation.WIDTH + x
            draw_rect(Rect2(origin + Vector2(x, y) * TILE_SIZE, Vector2(TILE_SIZE + 0.4, TILE_SIZE + 0.4)), palette[simulation.terrain[index]])
    draw_rect(Rect2(origin, Vector2(simulation.WIDTH, simulation.HEIGHT) * TILE_SIZE), Color("#10152a"), false, 2.0)

func refresh() -> void:
    if simulation == null: return
    queue_redraw()
    for node in building_nodes:
        if node != null:
            node.queue_free()
    for node in population_nodes:
        if node != null:
            node.queue_free()
    building_nodes.clear(); population_nodes.clear()
    var origin := Vector2(8, 72)
    var texture_by_type := {"篝火": "campfire", "木屋": "primordial-hut", "农田": "agrarian-farm", "工坊": "industrial-factory", "工厂": "industrial-factory", "研究中心": "modern-research-center", "发射场": "space-launch-site"}
    for building in simulation.buildings:
        var node := Sprite2D.new()
        var texture_name: String = texture_by_type.get(str(building.type), "primordial-hut")
        node.texture = textures.get(texture_name)
        node.position = origin + Vector2(float(building.x) + 0.5, float(building.y) + 0.5) * TILE_SIZE
        node.scale = Vector2.ONE * (0.065 if texture_name != "campfire" else 0.08)
        node.z_index = 20
        node.set_meta("kind", str(building.type))
        add_child(node); building_nodes.append(node)
    var people := min(10, max(2, int(simulation.population / 8)))
    for index in people:
        var person := Sprite2D.new()
        person.texture = textures.get("primordial-settler")
        person.position = origin + Vector2(27 + (index % 5) * 2.8, 46 + (index / 5) * 3.0) * TILE_SIZE
        person.scale = Vector2.ONE * 0.025
        person.z_index = 30
        add_child(person); population_nodes.append(person)
