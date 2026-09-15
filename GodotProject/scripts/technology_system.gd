class_name TechnologySystem
extends RefCounted

var unlocked: Array[String] = ["fire"]
var catalog := {
    "fire": {"name": "掌握火种", "era": 0, "cost": 0},
    "farming": {"name": "农业", "era": 1, "cost": 35},
    "steam": {"name": "蒸汽动力", "era": 2, "cost": 90},
    "electricity": {"name": "电力网络", "era": 3, "cost": 160},
    "spaceflight": {"name": "航天工程", "era": 4, "cost": 260},
    "deep_space": {"name": "深空导航", "era": 4, "cost": 420}
}

func research(id: String, current_era: int, science: int) -> Dictionary:
    if not catalog.has(id) or unlocked.has(id): return {"ok": false, "cost": 0, "name": ""}
    var tech: Dictionary = catalog[id]
    if int(tech.era) > current_era or science < int(tech.cost): return {"ok": false, "cost": int(tech.cost), "name": str(tech.name)}
    unlocked.append(id)
    return {"ok": true, "cost": int(tech.cost), "name": str(tech.name)}

func available(current_era: int) -> Array[Dictionary]:
    var result: Array[Dictionary] = []
    for id in catalog:
        var tech: Dictionary = catalog[id]
        if int(tech.era) <= current_era and not unlocked.has(id):
            result.append({"id": id, "name": tech.name, "cost": tech.cost})
    return result

func snapshot() -> Array[String]: return unlocked
func restore(data: Array) -> void: unlocked = data
