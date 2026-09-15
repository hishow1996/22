class_name CivilizationDiagnostics
extends RefCounted

const LOG_PATH := "user://civilization_diagnostics.log"
var last_report := ""
var missing_assets: Array[String] = []

func run() -> String:
    missing_assets.clear()
    var required_assets := [
        "processed-terrain-ocean.png", "processed-terrain-grass.png", "processed-terrain-dirt.png",
        "processed-terrain-forest.png", "processed-terrain-mountain.png", "processed-terrain-river.png",
        "processed-primordial-hut.png", "processed-agrarian-farm.png", "processed-industrial-factory.png",
        "processed-modern-research-center.png", "processed-space-launch-site.png", "processed-campfire.png"
    ]
    for asset in required_assets:
        if not ResourceLoader.exists("res://assets/" + asset): missing_assets.append(asset)
    var lines := ["Civilization Sandbox diagnostics", "viewport=768x1365 portrait", "time=" + Time.get_datetime_string_from_system()]
    lines.append("required_assets=%d missing=%d" % [required_assets.size(), missing_assets.size()])
    lines.append("texture_cache_entries=" + str(TextureCache.size()))
    if missing_assets.is_empty(): lines.append("assets=OK")
    else: lines.append("missing=" + ",".join(missing_assets))
    last_report = "\n".join(lines)
    var file := FileAccess.open(LOG_PATH, FileAccess.WRITE)
    if file != null: file.store_string(last_report)
    return last_report

func show_summary() -> String:
    if last_report.is_empty(): run()
    return last_report
