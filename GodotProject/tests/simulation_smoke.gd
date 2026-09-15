class_name CivilizationSimulationSmokeTest
extends RefCounted

static func run() -> Array[String]:
    var results: Array[String] = []
    var simulation := CivilizationSimulation.new(20260915)
    assert(simulation.terrain.size() == 64 * 96)
    assert(simulation.population == 24)
    results.append("world generation")
    simulation.grant_food(100)
    simulation.form_alliance()
    simulation.trade()
    assert(simulation.diplomacy.action_log.size() == 2)
    results.append("diplomacy")
    assert(simulation.research("farming") == false)
    simulation.resources.science = 100
    assert(simulation.research("farming"))
    results.append("technology")
    simulation.era = 4
    simulation.resources.metal = 500
    simulation.resources.fuel = 500
    simulation.resources.science = 500
    assert(simulation.try_launch_rocket())
    assert(simulation.try_build_station())
    results.append("space program")
    var snapshot := simulation.snapshot()
    var restored := CivilizationSimulation.new(7)
    restored.restore(snapshot)
    assert(restored.era == 4 and restored.discoveries.size() == 2)
    results.append("save restore")
    return results
