class_name DeviceProfile
extends RefCounted

const LOW := "low"
const BALANCED := "balanced"
const HIGH := "high"

static func detect() -> Dictionary:
    var cores := OS.get_processor_count()
    var memory_info := OS.get_memory_info()
    var memory_bytes := int(memory_info.get("physical", 0))
    var memory_gb := memory_bytes / 1073741824.0 if memory_bytes > 0 else 0.0
    var adapter := RenderingServer.get_video_adapter_name().to_lower()
    var tier := BALANCED
    if cores <= 4 or (memory_gb > 0.0 and memory_gb < 3.5): tier = LOW
    elif cores >= 8 and memory_gb >= 6.0 and not adapter.contains("software"): tier = HIGH
    return {"tier": tier, "cores": cores, "memory_gb": memory_gb, "adapter": adapter}

static func resolve(saved: Dictionary) -> Dictionary:
    var result := saved.duplicate(true)
    var device := detect()
    result["device_tier"] = device.tier
    result["device_cores"] = device.cores
    result["device_memory_gb"] = device.memory_gb
    result["device_adapter"] = device.adapter
    if not bool(result.get("manual_quality", false)):
        result["fps"] = 60 if device.tier == HIGH else (45 if device.tier == BALANCED else 30)
        result["particles"] = device.tier != LOW
        result["effect_cap"] = 12 if device.tier == HIGH else (8 if device.tier == BALANCED else 4)
    return result

static func summary(settings: Dictionary) -> String:
    return "设备档位：%s | CPU：%d 核 | 内存：%.1f GB | GPU：%s" % [settings.get("device_tier", BALANCED), int(settings.get("device_cores", 0)), float(settings.get("device_memory_gb", 0.0)), str(settings.get("device_adapter", "unknown"))]
