class_name CivilizationHud
extends CanvasLayer

var simulation: CivilizationSimulation
var status_label: Label
var resources_label: Label
var log_label: Label
var settings_panel: PanelContainer
var logs: Array[String] = []
var settings := {"fps": 30, "vsync": false, "particles": true}

func setup(source: CivilizationSimulation) -> void:
    simulation = source
    settings = SaveManager.load_settings()
    simulation.changed.connect(refresh)
    simulation.event_logged.connect(add_log)
    _build_ui()
    refresh()

func _build_ui() -> void:
    var root := Control.new()
    root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    add_child(root)
    var top := ColorRect.new()
    top.color = Color("#151b2b")
    top.position = Vector2(0, 0); top.size = Vector2(768, 68)
    root.add_child(top)
    status_label = Label.new()
    status_label.position = Vector2(16, 8); status_label.add_theme_font_size_override("font_size", 22)
    top.add_child(status_label)
    resources_label = Label.new()
    resources_label.position = Vector2(16, 38); resources_label.add_theme_font_size_override("font_size", 13)
    top.add_child(resources_label)
    var controls := HBoxContainer.new()
    controls.position = Vector2(8, 1240); controls.size = Vector2(752, 72)
    controls.add_theme_constant_override("separation", 6)
    root.add_child(controls)
    _button(controls, "暂停", _toggle_pause)
    _button(controls, "慢速", func(): simulation.time_scale = 0.5)
    _button(controls, "正常", func(): simulation.time_scale = 1.0)
    _button(controls, "加食物", simulation.grant_food)
    _button(controls, "降雨", simulation.set_rain)
    _button(controls, "陨石", simulation.trigger_meteor)
    _button(controls, "保存", func(): add_log("存档成功" if SaveManager.save_game(simulation) else "存档失败"))
    _button(controls, "读取", func(): add_log("读取成功" if SaveManager.load_game(simulation) else "没有存档"))
    var space := HBoxContainer.new()
    space.position = Vector2(8, 1160); space.size = Vector2(752, 64)
    root.add_child(space)
    _button(space, "发射火箭", simulation.try_launch_rocket)
    _button(space, "空间站", simulation.try_build_station)
    _button(space, "深空探测", simulation.try_deep_space)
    _button(space, "设置", _toggle_settings)
    log_label = Label.new()
    log_label.position = Vector2(16, 1060); log_label.size = Vector2(736, 90)
    log_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
    log_label.add_theme_font_size_override("font_size", 15)
    root.add_child(log_label)
    settings_panel = PanelContainer.new()
    settings_panel.position = Vector2(70, 260); settings_panel.size = Vector2(620, 350)
    settings_panel.visible = false
    root.add_child(settings_panel)
    var setting_box := VBoxContainer.new()
    settings_panel.add_child(setting_box)
    var title := Label.new(); title.text = "性能设置"; title.add_theme_font_size_override("font_size", 24); setting_box.add_child(title)
    var fps := OptionButton.new(); fps.add_item("30 FPS"); fps.add_item("45 FPS"); fps.add_item("60 FPS"); fps.selected = 0; fps.item_selected.connect(_set_fps); setting_box.add_child(fps)
    var vsync := CheckButton.new(); vsync.text = "垂直同步"; vsync.button_pressed = bool(settings.vsync); vsync.toggled.connect(_set_vsync); setting_box.add_child(vsync)
    var particles := CheckButton.new(); particles.text = "粒子效果"; particles.button_pressed = bool(settings.particles); particles.toggled.connect(_set_particles); setting_box.add_child(particles)
    var close := Button.new(); close.text = "关闭"; close.pressed.connect(_toggle_settings); setting_box.add_child(close)

func _button(parent: Container, text: String, callback: Callable) -> void:
    var button := Button.new(); button.text = text; button.custom_minimum_size = Vector2(82, 58); button.pressed.connect(callback); parent.add_child(button)

func refresh() -> void:
    if simulation == null or status_label == null: return
    status_label.text = "文明沙盒 · " + simulation.ERA_NAMES[simulation.era] + " · 第 " + str(simulation.elapsed_days) + " 天 · 人口 " + str(simulation.population)
    resources_label.text = "食物 %d  木材 %d  石材 %d  金属 %d  电力 %d  燃料 %d  科研 %d  | %s" % [simulation.resources.food, simulation.resources.wood, simulation.resources.stone, simulation.resources.metal, simulation.resources.electricity, simulation.resources.fuel, simulation.resources.science, simulation.weather]
    if log_label != null: log_label.text = "\n".join(logs.slice(max(0, logs.size() - 4)))

func add_log(message: String) -> void:
    logs.append(message)
    refresh()

func _process(_delta: float) -> void:
    if simulation != null and not simulation.paused:
        simulation.tick(max(1, int(simulation.time_scale * 1.0)))

func _toggle_pause() -> void:
    simulation.paused = not simulation.paused
    add_log("游戏已暂停" if simulation.paused else "游戏继续")

func _toggle_settings() -> void:
    settings_panel.visible = not settings_panel.visible

func _set_fps(index: int) -> void:
    settings.fps = [30, 45, 60][index]
    Engine.max_fps = settings.fps
    SaveManager.save_settings(settings)

func _set_vsync(enabled: bool) -> void:
    settings.vsync = enabled
    DisplayServer.window_set_vsync_mode(DisplayServer.VSYNC_ENABLED if enabled else DisplayServer.VSYNC_DISABLED)
    SaveManager.save_settings(settings)

func _set_particles(enabled: bool) -> void:
    settings.particles = enabled
    SaveManager.save_settings(settings)
