class_name CivilizationUiTheme
extends RefCounted

static func create() -> Theme:
    var theme := Theme.new()
    theme.default_font_size = 16
    theme.default_base_scale = 1.0
    theme.set_color("font_color", "Label", Color("#edf3ff"))
    theme.set_color("font_shadow_color", "Label", Color(0, 0, 0, 0.75))
    theme.set_constant("shadow_offset_x", "Label", 2)
    theme.set_constant("shadow_offset_y", "Label", 2)
    theme.set_color("font_color", "Button", Color("#f2f5ff"))
    theme.set_color("font_hover_color", "Button", Color("#fff1a8"))
    theme.set_color("font_pressed_color", "Button", Color("#ffffff"))
    theme.set_color("font_focus_color", "Button", Color("#fff1a8"))
    theme.set_stylebox("normal", "Button", _box("#202b45", "#52658f", 2))
    theme.set_stylebox("hover", "Button", _box("#304365", "#f0c95a", 2))
    theme.set_stylebox("pressed", "Button", _box("#172037", "#f7e28a", 3))
    theme.set_stylebox("focus", "Button", _box("#304365", "#fff1a8", 3))
    theme.set_stylebox("disabled", "Button", _box("#171d2d", "#303a54", 1))
    theme.set_stylebox("panel", "PanelContainer", _box("#111a2c", "#51658d", 2))
    theme.set_stylebox("panel", "Panel", _box("#111a2c", "#51658d", 2))
    theme.set_color("font_color", "OptionButton", Color("#edf3ff"))
    theme.set_stylebox("normal", "OptionButton", _box("#202b45", "#52658f", 2))
    theme.set_stylebox("hover", "OptionButton", _box("#304365", "#f0c95a", 2))
    theme.set_stylebox("normal", "CheckButton", _box("#202b45", "#52658f", 2))
    theme.set_stylebox("hover", "CheckButton", _box("#304365", "#f0c95a", 2))
    return theme

static func _box(background: String, border: String, width: int) -> StyleBoxFlat:
    var box := StyleBoxFlat.new()
    box.bg_color = Color(background)
    box.border_color = Color(border)
    box.set_border_width_all(width)
    box.corner_radius_top_left = 0
    box.corner_radius_top_right = 0
    box.corner_radius_bottom_left = 0
    box.corner_radius_bottom_right = 0
    box.content_margin_left = 10
    box.content_margin_right = 10
    box.content_margin_top = 6
    box.content_margin_bottom = 6
    return box
