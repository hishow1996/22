class_name AgricultureSystem
extends RefCounted

var fields: Array[Dictionary] = []
var animals := {"鸡": 4, "羊": 2, "牛": 0}
var tool_durability := {"石斧": 0, "石镐": 0, "铁锄": 0, "铁镐": 0}
var crops := {
    "小麦": {"days": 8, "food": 32, "season": "夏季"},
    "玉米": {"days": 10, "food": 44, "season": "夏季"},
    "蔬菜": {"days": 6, "food": 24, "season": "春季"}
}
var recipes := {
    "石斧": {"wood": 4, "stone": 6, "metal": 0, "era": 0},
    "石镐": {"wood": 4, "stone": 8, "metal": 0, "era": 0},
    "铁锄": {"wood": 3, "stone": 0, "metal": 8, "era": 2},
    "铁镐": {"wood": 3, "stone": 2, "metal": 10, "era": 2}
}

func plant(crop := "小麦") -> bool:
    if not crops.has(crop) or fields.size() >= 12: return false
    var planting_tool := "铁锄" if tool_durability["铁锄"] > 0 else "石斧"
    if tool_durability[planting_tool] > 0: use_tool(planting_tool, 2)
    fields.append({"crop": crop, "age": 0, "ready": false})
    return true

func advance(days: int, season: String, weather: String) -> Dictionary:
    var food := 0
    var products := {"鸡蛋": 0, "牛奶": 0, "羊毛": 0}
    for field in fields:
        var crop: Dictionary = crops.get(str(field.crop), crops["小麦"])
        var growth := 1
        if season == "冬季" or weather == "暴雨" or weather == "大雪": growth = 0
        field.age += days * growth
        if field.age >= int(crop.days): field.ready = true
    for animal in animals:
        var count := int(animals[animal])
        if animal == "鸡": products["鸡蛋"] += count * days
        elif animal == "牛": products["牛奶"] += count * days
        elif animal == "羊": products["羊毛"] += max(0, int(count * days / 3.0))
        food += count * (1 if animal == "鸡" else 2) * days
    return {"food": food, "products": products, "ready_fields": ready_count()}

func harvest() -> Dictionary:
    var output := {"food": 0, "seeds": 0, "crop": ""}
    for field in fields:
        if field.ready:
            var crop: Dictionary = crops.get(str(field.crop), crops["小麦"])
            output.food += int(crop.food)
            output.seeds += 1
            output.crop = str(field.crop)
            field.age = 0
            field.ready = false
    if int(output.food) > 0: use_tool("铁锄" if tool_durability["铁锄"] > 0 else "石斧", 1)
    return output

func feed_animals(food: int) -> int:
    return min(food, total_animals() * 2)

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

func use_tool(tool: String, amount: int = 1) -> bool:
    if not tool_durability.has(tool) or int(tool_durability[tool]) <= 0: return false
    tool_durability[tool] = max(0, int(tool_durability[tool]) - amount)
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
