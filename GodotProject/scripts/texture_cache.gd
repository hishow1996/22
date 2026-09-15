class_name TextureCache
extends RefCounted

static var _cache: Dictionary = {}

static func get_texture(path: String) -> Texture2D:
    if _cache.has(path): return _cache[path]
    if not ResourceLoader.exists(path): return null
    var texture: Texture2D = load(path)
    if texture != null: _cache[path] = texture
    return texture

static func clear() -> void:
    _cache.clear()

static func size() -> int:
    return _cache.size()
