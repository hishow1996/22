class_name SpaceProgram
extends RefCounted

var missions := {"卫星": false, "空间站": false, "深空探测": false, "载人探索": false}
var discovered_bodies: Array[String] = []

func launch(name: String, era: int, resources: Dictionary, science: int) -> Dictionary:
    if era < 4 or not missions.has(name): return {"ok": false, "message": "尚未解锁"}
    if missions[name]: return {"ok": false, "message": "任务已完成"}
    var costs := {"卫星": {"metal": 60, "fuel": 30, "science": 0}, "空间站": {"metal": 120, "fuel": 0, "science": 80}, "深空探测": {"metal": 0, "fuel": 100, "science": 180}, "载人探索": {"metal": 80, "fuel": 160, "science": 300}}
    var cost: Dictionary = costs[name]
    if int(resources.metal) < int(cost.metal) or int(resources.fuel) < int(cost.fuel) or science < int(cost.science):
        return {"ok": false, "message": "资源或科研不足"}
    resources.metal -= int(cost.metal)
    resources.fuel -= int(cost.fuel)
    missions[name] = true
    discovered_bodies.append(name)
    return {"ok": true, "message": "太空任务成功：" + name}

func snapshot() -> Dictionary: return {"missions": missions, "discoveries": discovered_bodies}
func restore(data: Dictionary) -> void:
    missions = data.get("missions", missions)
    discovered_bodies = data.get("discoveries", [])
