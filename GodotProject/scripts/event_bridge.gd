class_name EventBridge
extends RefCounted

signal event_raised(event_name: String, intensity: float)

func raise_event(event_name: String, intensity := 1.0) -> void:
    event_raised.emit(event_name, intensity)
