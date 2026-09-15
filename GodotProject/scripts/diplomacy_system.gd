class_name DiplomacySystem
extends RefCounted

var nations: Array[Dictionary] = []
var action_log: Array[String] = []

func _init() -> void:
    nations = [{"name": "曙光", "era": 0, "power": 60, "relation": 20}, {"name": "太阳", "era": 0, "power": 52, "relation": -5}]

func tick(days: int, current_era: int) -> String:
    for nation in nations:
        nation.era = min(current_era, int(nation.era) + (1 if days % 20 == 0 else 0))
    if days > 0 and days % 15 == 0:
        return "外交动态：" + nations[0].name + "与" + nations[1].name + "进行了边境谈判"
    return ""

func form_alliance(first := 0, second := 1) -> bool:
    if first >= nations.size() or second >= nations.size(): return false
    nations[first].relation = min(100, int(nations[first].relation) + 25)
    nations[second].relation = min(100, int(nations[second].relation) + 25)
    action_log.append("联盟：" + nations[first].name + " / " + nations[second].name)
    return true

func trade(amount := 20) -> bool:
    if amount <= 0: return false
    nations[0].power += amount
    nations[1].power += amount / 2
    action_log.append("贸易：双方交换了 " + str(amount) + " 单位资源")
    return true

func resolve_war() -> String:
    var winner = nations[0].name if nations[0].power >= nations[1].power else nations[1].name
    nations[0].relation -= 20
    nations[1].relation -= 20
    action_log.append("战争结束：胜者为 " + winner)
    return winner

func snapshot() -> Dictionary:
    return {"nations": nations, "actions": action_log}

func restore(data: Dictionary) -> void:
    nations = data.get("nations", nations)
    action_log = data.get("actions", [])
