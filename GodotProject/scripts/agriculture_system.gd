class_name AgricultureSystem
extends RefCounted

var fields: Array[Dictionary] = []
var animals := {"鸡": 4, "羊": 2, "牛": 0}
var tool_durability := {"石斧": 0, "石镐": 0, "铁锄": 0, "铁镐": 0}
var recipes := {
    "石斧": {"wood": 4, "stone": 6, "metal": 0, "era": 0},
    "石镐": {"wood": 4, "stone": 8, "metal": 0, "era": 0},
    "铁锄": {"wood": 3, "stone": 0, "metal": 8, "era": 2},
    "铁镐": {"wood": 3, "stone": 2, "metal": 10, "era": 2}
}

func plant(crop := "小麦") -> bool:
    if fields.size() >= 12: return false
    fields.append({"crop": crop, "age": 0, "ready": false})
    return true

func advance(days: int, season: String, weather: String) -> Dictionary:
    var food := 0
    for field in fields:
        var growth := 1
        if season == "冬季": growth = 0
        if weather == "暴雨" or weather == "大雪": growth = 0
        field.age += days * growth
        if field.age >= 8: field.ready = true
    for animal in animals:
        food += int(animals[animal] * (1 if animal == "鸡" else 2) * days)
    return {"food": food, "ready_fields": ready_count()}

func harvest() -> int:
    var amount := 0
    for field in fields:
        if field.ready:
            amount += 32
            field.age = 0
            field.ready = false
    return amount

func feed_animals(food: int) -> int:
    var used := min(food, total_animals() * 2)
    return used

func breed(animal := "鸡") -> bool:
    if not animals.has(animal) or total_animals() >= 24: return false
    animals[animal] += 1
    return true

func craft(tool: String, era: int, resources: Dictionary) -> bool:
    if not recipes.has(tool): return false
    var recipe: Dictionary = recipes[tool]
    if era < int(recipe.era): return false
    for key in ["wood", "stone", "metal"]:
        if int(resources.get(key, 0)) < int(recipe[key]): return false
    for key in ["wood", "stone", "metal"]: resources[key] -= int(recipe[key])
    tool_durability[tool] = 100
    return true

func repair(tool: String, resources: Dictionary) -> bool:
    if not tool_durability.has(tool) or int(tool_durability[tool]) >= 100 or int(resources.wood) < 2: return false
    resources.wood -= 2
    tool_durability[tool] = 100
    return true

func ready_count() -> int:
    var count := 0
    for field in fields:
        if field.ready: count += 1
    return count

func total_animals() -> int:
    var total := 0
    for animal in animals: total += int(animals[animal])
    return total

func summary() -> String:
    return "田地 %d（成熟 %d）· 鸡 %d 羊 %d 牛 %d · 工具 %s" % [fields.size(), ready_count(), animals["鸡"], animals["羊"], animals["牛"], str(tool_durability)]

func snapshot() -> Dictionary:
    return {"fields": fields, "animals": animals, "tools": tool_durability}

func restore(data: Dictionary) -> void:
    fields = data.get("fields", [])
    animals = data.get("animals", animals)
    tool_durability = data.get("tools", tool_durability)
