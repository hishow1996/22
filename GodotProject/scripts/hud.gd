class_name CivilizationHud
extends CanvasLayer

var simulation: CivilizationSimulation
var starmap: StarMapView
var status_label: Label
var resources_label: Label
var log_label: Label
var settings_panel: PanelContainer
var space_status: Label
var info_panel: PanelContainer
var info_label: Label
var building_status: Label
var logs: Array[String] = []
var settings := {"fps": 30, "vsync": false, "particles": true}
var tick_accumulator := 0.0
var autosave_timer := 0.0
var safe_top := 0.0
var safe_bottom := 0.0

func setup(source: CivilizationSimulation, map_view: StarMapView = null) -> void:
    simulation = source
    starmap = map_view
    settings = SaveManager.load_settings()
    simulation.changed.connect(refresh)
    simulation.event_logged.connect(add_log)
    _build_ui()
    refresh()

func _build_ui() -> void:
    var root := Control.new()
    root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
    root.size = Vector2(768, 1365)
    root.theme = CivilizationUiTheme.create()
    _calculate_safe_insets()
    add_child(root)
    var top := ColorRect.new()
    top.color = Color("#151b2b")
    top.position = Vector2(0, safe_top); top.size = Vector2(768, 68)
    root.add_child(top)
    status_label = Label.new()
    status_label.position = Vector2(16, 8); status_label.add_theme_font_size_override("font_size", 22)
    top.add_child(status_label)
    resources_label = Label.new()
    resources_label.position = Vector2(16, 38); resources_label.add_theme_font_size_override("font_size", 13)
    top.add_child(resources_label)
    _add_resource_icon(top, "food", 560)
    _add_resource_icon(top, "science", 606)
    _add_resource_icon(top, "space", 652)
    building_status = Label.new()
    building_status.position = Vector2(16, 82 + safe_top)
    building_status.add_theme_font_size_override("font_size", 14)
    root.add_child(building_status)
    var controls := GridContainer.new()
    controls.columns = 6
    controls.position = Vector2(8, 1240 - safe_bottom); controls.size = Vector2(752, 118)
    controls.add_theme_constant_override("separation", 6)
    root.add_child(controls)
    _button(controls, "暂停", _toggle_pause)
    _button(controls, "慢速", func(): _set_speed(0.5))
    _button(controls, "正常", func(): _set_speed(1.0))
    _button(controls, "加速", func(): _set_speed(2.0))
    _button(controls, "加食物", simulation.grant_food)
    _button(controls, "降雨", simulation.set_rain)
    _button(controls, "陨石", simulation.trigger_meteor)
    _button(controls, "外交", _show_diplomacy)
    _button(controls, "科技树", _show_technology)
    _button(controls, "职业", _show_jobs)
    _button(controls, "保存", func(): add_log("存档成功" if SaveManager.save_game(simulation) else "存档失败"))
    _button(controls, "读取", func(): add_log("读取成功" if SaveManager.load_game(simulation) else "没有存档"))
    var space := GridContainer.new()
    space.columns = 6
    space.position = Vector2(8, 1160 - safe_bottom); space.size = Vector2(752, 64)
    root.add_child(space)
    _button(space, "发射火箭", simulation.try_launch_rocket)
    _button(space, "空间站", simulation.try_build_station)
    _button(space, "深空探测", simulation.try_deep_space)
    _button(space, "载人探索", simulation.try_crewed_exploration)
    _button(space, "星图", _toggle_starmap)
    _button(space, "设置", _toggle_settings)
    space_status = Label.new()
    space_status.position = Vector2(16, 1015 - safe_bottom)
    space_status.add_theme_font_size_override("font_size", 15)
    root.add_child(space_status)
    var systems := HBoxContainer.new()
    systems.position = Vector2(8, 1085 - safe_bottom); systems.size = Vector2(752, 58)
    root.add_child(systems)
    _button(systems, "研究科技", _research_next)
    _button(systems, "结成联盟", simulation.form_alliance)
    _button(systems, "进行贸易", simulation.trade)
    _button(systems, "解决战争", simulation.resolve_war)
    log_label = Label.new()
    log_label.position = Vector2(16, 960 - safe_bottom); log_label.size = Vector2(736, 110)
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
    info_panel = PanelContainer.new()
    info_panel.position = Vector2(70, 250); info_panel.size = Vector2(620, 420)
    info_panel.visible = false
    root.add_child(info_panel)
    info_label = Label.new()
    info_label.position = Vector2(18, 18); info_label.size = Vector2(580, 380)
    info_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
    info_label.add_theme_font_size_override("font_size", 18)
    info_panel.add_child(info_label)

func _button(parent: Container, text: String, callback: Callable) -> void:
    var button := Button.new(); button.text = text; button.custom_minimum_size = Vector2(116, 58); button.size_flags_horizontal = Control.SIZE_EXPAND_FILL; button.focus_mode = Control.FOCUS_ALL; button.mouse_default_cursor_shape = Control.CURSOR_POINTING_HAND; button.pressed.connect(callback); parent.add_child(button)

func _calculate_safe_insets() -> void:
    var safe_area := DisplayServer.get_display_safe_area()
    var window_size := DisplayServer.window_get_size()
    if safe_area.size.x <= 0.0 or safe_area.size.y <= 0.0 or window_size.y <= 0:
        return
    safe_top = clamp(safe_area.position.y / float(window_size.y) * 1365.0, 0.0, 120.0)
    var safe_bottom_px := float(window_size.y - (safe_area.position.y + safe_area.size.y))
    safe_bottom = clamp(safe_bottom_px / float(window_size.y) * 1365.0, 0.0, 140.0)

func _add_resource_icon(parent: Control, name: String, x: float) -> void:
    var path := "res://assets/processed-hud-" + name + ".png"
    if not ResourceLoader.exists(path): return
    var icon := TextureRect.new()
    icon.texture = load(path)
    icon.position = Vector2(x, 8)
    icon.size = Vector2(36, 36)
    icon.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
    icon.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
    parent.add_child(icon)

func refresh() -> void:
    if simulation == null or status_label == null: return
    status_label.text = "文明沙盒 · " + simulation.ERA_NAMES[simulation.era] + " · 第 " + str(simulation.elapsed_days) + " 天 · 人口 " + str(simulation.population)
    resources_label.text = "食物 %d  木材 %d  石材 %d  金属 %d  电力 %d  燃料 %d  科研 %d  | %s" % [simulation.resources.food, simulation.resources.wood, simulation.resources.stone, simulation.resources.metal, simulation.resources.electricity, simulation.resources.fuel, simulation.resources.science, simulation.weather]
    if log_label != null: log_label.text = "\n".join(logs.slice(max(0, logs.size() - 4)))
    if space_status != null:
        space_status.text = "太空任务：" + ", ".join(simulation.space_program.discovered_bodies) if not simulation.space_program.discovered_bodies.is_empty() else "太空任务：尚未完成"
    if building_status != null:
        var names: Array[String] = []
        for building in simulation.buildings:
            names.append(str(building.type))
        building_status.text = "时代建筑：" + " · ".join(names)

func add_log(message: String) -> void:
    logs.append(message)
    refresh()

func _process(delta: float) -> void:
    autosave_timer += delta
    if autosave_timer >= 30.0 and simulation != null:
        SaveManager.save_game(simulation)
        autosave_timer = 0.0
        add_log("自动存档完成")
    if simulation == null or simulation.paused or simulation.time_scale <= 0.0: return
    tick_accumulator += delta * simulation.time_scale
    if tick_accumulator >= 1.0:
        simulation.tick(1)
        tick_accumulator -= 1.0

func _toggle_pause() -> void:
    simulation.paused = not simulation.paused
    add_log("游戏已暂停" if simulation.paused else "游戏继续")

func _set_speed(value: float) -> void:
    simulation.time_scale = value
    add_log("速度：" + str(value) + "x")

func _toggle_settings() -> void:
    settings_panel.visible = not settings_panel.visible

func _toggle_starmap() -> void:
    if starmap != null: starmap.toggle()

func handle_back_request() -> bool:
    if settings_panel != null and settings_panel.visible:
        settings_panel.visible = false
        return true
    if info_panel != null and info_panel.visible:
        info_panel.visible = false
        return true
    if starmap != null and starmap.active:
        starmap.toggle()
        return true
    if simulation != null and not simulation.paused:
        simulation.paused = true
        add_log("游戏已暂停，再按一次返回键退出")
        return true
    return false

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

func _show_diplomacy() -> void:
    simulation.form_alliance()
    add_log("外交面板：已尝试结成联盟")
    _show_info("外交文明\n" + str(simulation.diplomacy.nations))

func _show_technology() -> void:
    var available := simulation.technology.available(simulation.era)
    if available.is_empty():
        add_log("科技树：当前没有可研究项目")
    else:
        add_log("科技树：可研究 " + str(available[0].name) + "，消耗 " + str(available[0].cost) + " 科研点")
    var lines := ["科技树 · " + simulation.ERA_NAMES[simulation.era], "已解锁：" + ", ".join(simulation.technology.unlocked)]
    for item in available: lines.append("可研究：%s（%d 科研点）" % [item.name, item.cost])
    _show_info("\n".join(lines))

func _research_next() -> void:
    var available := simulation.technology.available(simulation.era)
    if available.is_empty():
        add_log("科技树：当前没有可研究项目")
        return
    simulation.research(str(available[0].id))

func _show_jobs() -> void:
    add_log("职业分配：" + simulation.population_system.summary())
    _show_info("文明信息\n人口：" + str(simulation.population) + "\n职业：" + simulation.population_system.summary() + "\n国家：" + str(simulation.diplomacy.nations.size()))

func _show_info(text: String) -> void:
    if info_label != null:
        info_label.text = text + "\n\n点击科技树、外交或职业按钮刷新面板。"
        info_panel.visible = true
