class_name CivilizationSimulation
extends RefCounted

signal changed
signal event_logged(message: String)

const WIDTH := 64
const HEIGHT := 96
const ERA_NAMES := ["原始时代", "农业时代", "工业时代", "现代时代", "太空时代"]
const RESOURCE_NAMES := ["食物", "木材", "石材", "金属", "电力", "燃料", "科研点"]

var seed_value: int = 20260915
var elapsed_days := 0
var era := 0
var population := 24
var paused := false
var time_scale := 1.0
var weather := "晴朗"
var resources := {"food": 180, "wood": 120, "stone": 80, "metal": 0, "electricity": 0, "fuel": 0, "science": 0}
var discoveries: Array[String] = []
var buildings: Array[Dictionary] = []
var terrain: Array[int] = []
var rng := RandomNumberGenerator.new()
var diplomacy := DiplomacySystem.new()
var technology := TechnologySystem.new()
var population_system := PopulationSystem.new()
var space_program := SpaceProgram.new()
var events := EventBridge.new()

func _init(world_seed: int = 20260915) -> void:
    seed_value = world_seed
    rng.seed = seed_value
    _generate_world()
    _plan_buildings()
    population_system.assign_for_era(era, population)

func _generate_world() -> void:
    terrain.clear()
    for y in HEIGHT:
        for x in WIDTH:
            var value := (x * 17 + y * 31 + seed_value) % 100
            var type := 1
            if value < 12: type = 0
            elif value < 22: type = 2
            elif value < 34: type = 3
            elif value < 42: type = 4
            elif (x + y * 3) % 29 == 0: type = 5
            terrain.append(type)

func _plan_buildings() -> void:
    buildings.clear()
    var types: Array[String] = ["篝火", "木屋"]
    if era >= 1: types.append("农田")
    if era >= 2: types.append("工坊"); types.append("工厂")
    if era >= 3: types.append("研究中心")
    if era >= 4: types.append("发射场")
    for i in types.size(): buildings.append({"type": types[i], "x": 18 + i * 8, "y": 24 + (i % 3) * 10})

func tick(days: int = 1) -> void:
    if paused or days <= 0: return
    elapsed_days += days
    var production := 0.65 if weather == "暴雨" else 1.0
    resources.food += int(population * 0.7 * production)
    resources.wood += int(max(1, population * 0.22))
    resources.stone += int(max(1, population * 0.12))
    resources.science += int(max(1, population * 0.08 * (era + 1)))
    if era >= 2: resources.metal += int(max(1, population * 0.16))
    if era >= 3: resources.electricity += int(max(1, population * 0.18))
    if era >= 4: resources.fuel += int(max(1, population * 0.1))
    resources.food -= int(population * 0.35)
    population_system.tick(population)
    population_system.assign_for_era(era, population)
    var diplomacy_event := diplomacy.tick(days, era)
    if not diplomacy_event.is_empty(): event_logged.emit(diplomacy_event)
    if resources.food < 0:
        population = max(4, population - 1)
        resources.food = 0
        event_logged.emit("粮食不足，人口出现损失")
    elif elapsed_days % 8 == 0:
        population += max(1, int(population * 0.03))
        event_logged.emit("人口自然增长")
    _check_era()
    changed.emit()

func _check_era() -> void:
    var thresholds := [0, 80, 260, 620, 1200]
    if era < 4 and resources.science >= thresholds[era + 1]:
        era += 1
        _plan_buildings()
        population_system.assign_for_era(era, population)
        event_logged.emit("时代跃迁：" + ERA_NAMES[era])
        events.raise_event("era_advanced", float(era + 1))

func grant_food(amount := 50) -> void:
    resources.food += amount
    event_logged.emit("上帝干预：获得 " + str(amount) + " 食物")
    changed.emit()

func set_rain() -> void:
    weather = "降雨"
    event_logged.emit("天气变化：降雨开始")
    events.raise_event("rain_started")
    changed.emit()

func trigger_meteor() -> void:
    weather = "陨石灾害"
    population = max(4, population - max(1, int(population * 0.05)))
    resources.stone += 20
    event_logged.emit("灾害事件：陨石撞击")
    events.raise_event("meteor_impact")
    changed.emit()

func research(id: String) -> bool:
    var result := technology.research(id, era, resources.science)
    if not result.ok:
        event_logged.emit("科技研究条件不足：" + result.name)
        return false
    resources.science -= result.cost
    event_logged.emit("科技突破：" + result.name)
    events.raise_event("technology_researched")
    changed.emit()
    return true

func form_alliance() -> void:
    if diplomacy.form_alliance(): event_logged.emit("外交行动：结成联盟")

func trade() -> void:
    if diplomacy.trade(): event_logged.emit("外交行动：完成贸易")

func resolve_war() -> void:
    event_logged.emit("外交行动：战争结束，胜者为 " + diplomacy.resolve_war())

func try_launch_rocket() -> bool: return _launch_space("卫星")
func try_build_station() -> bool: return _launch_space("空间站")
func try_deep_space() -> bool: return _launch_space("深空探测")
func try_crewed_exploration() -> bool: return _launch_space("载人探索")

func _launch_space(name: String) -> bool:
    var result := space_program.launch(name, era, resources, resources.science)
    event_logged.emit(result.message)
    if result.ok:
        _record_discovery(name)
        events.raise_event("space_mission")
    return result.ok

func _record_discovery(name: String) -> void:
    if not discoveries.has(name): discoveries.append(name)
    event_logged.emit("太空行动成功：" + name)
    changed.emit()

func snapshot() -> Dictionary:
    return {"seed": seed_value, "days": elapsed_days, "era": era, "population": population, "paused": paused, "time_scale": time_scale, "weather": weather, "resources": resources, "discoveries": discoveries, "diplomacy": diplomacy.snapshot(), "technology": technology.snapshot(), "population_jobs": population_system.snapshot(), "space": space_program.snapshot()}

func restore(data: Dictionary) -> void:
    seed_value = int(data.get("seed", seed_value))
    elapsed_days = int(data.get("days", 0))
    era = int(data.get("era", 0))
    population = int(data.get("population", 24))
    paused = bool(data.get("paused", false))
    time_scale = float(data.get("time_scale", 1.0))
    weather = str(data.get("weather", "晴朗"))
    resources = data.get("resources", resources)
    discoveries = data.get("discoveries", [])
    diplomacy.restore(data.get("diplomacy", {}))
    technology.restore(data.get("technology", ["fire"]))
    population_system.restore(data.get("population_jobs", {}))
    space_program.restore(data.get("space", {}))
    rng.seed = seed_value
    _generate_world()
    _plan_buildings()
    changed.emit()
