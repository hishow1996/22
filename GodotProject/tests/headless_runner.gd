extends SceneTree

func _init() -> void:
    var failures: Array[String] = []
    var simulation := CivilizationSimulation.new(20260915)
    _check(simulation.terrain.size() == 6144, "deterministic 64x96 terrain", failures)
    _check(simulation.population == 24, "initial population", failures)
    var initial_clock := simulation.environment.clock_text()
    simulation.tick(48)
    _check(simulation.environment.clock_text() != initial_clock, "day night clock", failures)
    _check(simulation.environment.season() == "春季" or simulation.environment.season() == "夏季", "season cycle", failures)
    simulation.set_rain()
    _check(simulation.environment.weather() == "降雨", "weather override", failures)
    simulation.resources.science = 500
    _check(simulation.research("farming"), "farming research", failures)
    simulation.form_alliance()
    simulation.trade()
    _check(simulation.diplomacy.action_log.size() == 2, "diplomacy actions", failures)
    simulation.era = 4
    simulation.resources.metal = 500
    simulation.resources.fuel = 500
    simulation.resources.science = 500
    _check(simulation.try_launch_rocket(), "satellite mission", failures)
    _check(simulation.try_build_station(), "space station mission", failures)
    var snapshot := simulation.snapshot()
    var restored := CivilizationSimulation.new(7)
    restored.restore(snapshot)
    _check(restored.era == 4, "save restore era", failures)
    _check(restored.discoveries.size() == 2, "save restore discoveries", failures)
    for asset in ["processed-terrain-ocean.png", "processed-campfire.png", "processed-space-launch-site.png"]:
        _check(ResourceLoader.exists("res://assets/" + asset), "asset " + asset, failures)
    if failures.is_empty():
        print("HEADLESS PASS: simulation, save restore, diplomacy, technology, space and assets")
        quit(0)
    else:
        for failure in failures: push_error("FAIL: " + failure)
        quit(1)

func _check(condition: bool, name: String, failures: Array[String]) -> void:
    if not condition: failures.append(name)
