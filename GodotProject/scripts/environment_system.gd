class_name EnvironmentSystem
extends RefCounted

const SEASONS := ["春季", "夏季", "秋季", "冬季"]
const WEATHER_NAMES := ["晴朗", "多云", "降雨", "暴雨", "大雪"]
var day := 1
var hour := 8.0
var season_index := 0
var weather_index := 0
var weather_locked := false
var rng := RandomNumberGenerator.new()

func _init(seed_value: int = 20260915) -> void:
    rng.seed = seed_value

func advance(days: int) -> void:
    hour += days * 0.5
    while hour >= 24.0:
        hour -= 24.0
        day += 1
        if day % 30 == 0: season_index = (season_index + 1) % SEASONS.size()
        if not weather_locked and day % 3 == 0: weather_index = rng.randi_range(0, WEATHER_NAMES.size() - 1)

func set_weather(name: String, locked := true) -> void:
    var index := WEATHER_NAMES.find(name)
    if index >= 0:
        weather_index = index
        weather_locked = locked

func clear_weather_override() -> void:
    weather_locked = false

func season() -> String: return SEASONS[season_index]
func weather() -> String: return WEATHER_NAMES[weather_index]
func is_daylight() -> bool: return hour >= 6.0 and hour < 18.0

func light_factor() -> float:
    if hour < 5.0 or hour >= 21.0: return 0.52
    if hour < 7.0: return 0.72
    if hour < 9.0: return 0.9
    if hour < 17.0: return 1.0
    if hour < 19.0: return 0.86
    return 0.68

func production_factor() -> float:
    var factor := 1.0
    if weather() == "暴雨": factor *= 0.65
    elif weather() == "大雪": factor *= 0.72
    elif weather() == "降雨": factor *= 0.88
    if season() == "冬季": factor *= 0.82
    elif season() == "夏季": factor *= 1.05
    return factor

func clock_text() -> String:
    return "%02d:%02d" % [int(hour), int((hour - int(hour)) * 60.0)]

func snapshot() -> Dictionary:
    return {"day": day, "hour": hour, "season": season_index, "weather": weather_index, "locked": weather_locked}

func restore(data: Dictionary) -> void:
    day = int(data.get("day", 1))
    hour = float(data.get("hour", 8.0))
    season_index = clampi(int(data.get("season", 0)), 0, SEASONS.size() - 1)
    weather_index = clampi(int(data.get("weather", 0)), 0, WEATHER_NAMES.size() - 1)
    weather_locked = bool(data.get("locked", false))
