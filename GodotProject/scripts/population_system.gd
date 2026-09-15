class_name PopulationSystem
extends RefCounted

var jobs := {"采集者": 8, "农民": 0, "工程师": 0, "科学家": 0, "宇航员": 0}
var movement_revision := 0

func assign_for_era(era: int, population: int) -> void:
    jobs["采集者"] = max(1, int(population * 0.38))
    jobs["农民"] = int(population * (0.18 if era >= 1 else 0.0))
    jobs["工程师"] = int(population * (0.14 if era >= 2 else 0.0))
    jobs["科学家"] = int(population * (0.12 if era >= 3 else 0.0))
    jobs["宇航员"] = int(population * (0.05 if era >= 4 else 0.0))
    movement_revision += 1

func tick(population: int) -> void:
    if population > 0: movement_revision += 1

func summary() -> String:
    return "采集 %d  农民 %d  工程师 %d  科学家 %d  宇航员 %d" % [jobs["采集者"], jobs["农民"], jobs["工程师"], jobs["科学家"], jobs["宇航员"]]

func snapshot() -> Dictionary: return jobs.duplicate()
func restore(data: Dictionary) -> void: jobs = data.duplicate()
