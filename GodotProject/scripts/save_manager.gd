class_name SaveManager
extends RefCounted

const SAVE_PATH := "user://civilization_save.json"
const SETTINGS_PATH := "user://civilization_settings.json"
const SAVE_VERSION := 2

static func save_game(simulation: CivilizationSimulation) -> bool:
    var file := FileAccess.open(SAVE_PATH, FileAccess.WRITE)
    if file == null: return false
    file.store_string(JSON.stringify({"version": SAVE_VERSION, "saved_at": Time.get_datetime_string_from_system(), "world": simulation.snapshot()}))
    return true

static func load_game(simulation: CivilizationSimulation) -> bool:
    if not FileAccess.file_exists(SAVE_PATH): return false
    var file := FileAccess.open(SAVE_PATH, FileAccess.READ)
    if file == null: return false
    var data = JSON.parse_string(file.get_as_text())
    if typeof(data) != TYPE_DICTIONARY: return false
    var version := int(data.get("version", 1))
    var world: Dictionary = data.get("world", data)
    if version < SAVE_VERSION:
        world = _migrate_legacy_save(world, version)
    simulation.restore(world)
    return true

static func _migrate_legacy_save(world: Dictionary, _version: int) -> Dictionary:
    var migrated := world.duplicate(true)
    if not migrated.has("diplomacy"): migrated["diplomacy"] = {}
    if not migrated.has("technology"): migrated["technology"] = ["fire"]
    if not migrated.has("population_jobs"): migrated["population_jobs"] = {}
    if not migrated.has("space"): migrated["space"] = {}
    return migrated

static func save_settings(settings: Dictionary) -> void:
    var file := FileAccess.open(SETTINGS_PATH, FileAccess.WRITE)
    if file != null: file.store_string(JSON.stringify(settings))

static func load_settings() -> Dictionary:
    if not FileAccess.file_exists(SETTINGS_PATH): return {"fps": 30, "vsync": false, "particles": true}
    var file := FileAccess.open(SETTINGS_PATH, FileAccess.READ)
    var data = JSON.parse_string(file.get_as_text()) if file != null else null
    return data if typeof(data) == TYPE_DICTIONARY else {"fps": 30, "vsync": false, "particles": true}
