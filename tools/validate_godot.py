from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'GodotProject'
required = [root / 'project.godot', root / 'main.tscn', root / 'export_presets.cfg']
required += [root / 'scripts' / name for name in ('main.gd', 'civilization_simulation.gd', 'world_view.gd', 'hud.gd', 'save_manager.gd', 'diplomacy_system.gd', 'technology_system.gd', 'population_system.gd', 'space_program.gd', 'event_bridge.gd', 'event_fx.gd', 'starmap_view.gd', 'effect_player.gd')]
required.append(root / 'tests' / 'simulation_smoke.gd')
for path in required:
    assert path.exists() and path.stat().st_size > 0, path

gd = list((root / 'scripts').glob('*.gd'))
assert len(gd) >= 13
for path in gd:
    text = path.read_text(encoding='utf-8')
    assert text.count('func ') >= 1, path
    assert 'UnityEngine' not in text, path

assets = list((root / 'assets').glob('*.png'))
assert len(assets) >= 78, len(assets)
project = (root / 'project.godot').read_text(encoding='utf-8')
scene = (root / 'main.tscn').read_text(encoding='utf-8')
preset = (root / 'export_presets.cfg').read_text(encoding='utf-8')
for token in ('run/main_scene="res://main.tscn"', 'size/viewport_width=768', 'size/viewport_height=1365', 'textures/canvas_textures/default_texture_filter=0'):
    assert token in project, token
assert 'script = ExtResource("1_main")' in scene
for token in ('name="Android"', 'screen/handheld/orientation=1', 'package/unique_name='):
    assert token in preset, token
simulation = (root / 'scripts' / 'civilization_simulation.gd').read_text(encoding='utf-8')
for token in ('DiplomacySystem', 'TechnologySystem', 'PopulationSystem', 'SpaceProgram', 'EventBridge', 'try_crewed_exploration'):
    assert token in simulation, token
world_view = (root / 'scripts' / 'world_view.gd').read_text(encoding='utf-8')
for token in ('processed-" + name', 'terrain-ocean', 'transition-cobblestone-road', 'transition-stone-bridge', '_draw_overlays', 'draw_texture_rect', 'primordial-settler-walk', 'AnimatedSprite2D', 'agrarian-farmer', 'space-astronaut', 'Sprite2D', 'InputEventScreenDrag', 'InputEventMagnifyGesture', 'camera_zoom', '篝火'):
    assert token in world_view, token
main = (root / 'scripts' / 'main.gd').read_text(encoding='utf-8')
assert 'EventFx' in main and 'event_fx.setup' in main
assert 'EffectPlayer' in main and 'effect_player.setup' in main
assert 'StarMapView' in main and 'starmap.setup' in main
assert 'event_fx.z_index = 40' in main
hud = (root / 'scripts' / 'hud.gd').read_text(encoding='utf-8')
for token in ('_set_speed', 'processed-hud-', 'space_status', 'building_status', 'get_display_safe_area', 'try_crewed_exploration', 'autosave_timer', '_show_info', '_toggle_starmap'):
    assert token in hud, token
save_manager = (root / 'scripts' / 'save_manager.gd').read_text(encoding='utf-8')
for token in ('SAVE_VERSION := 2', 'saved_at', '_migrate_legacy_save', 'data.get("world", data)'):
    assert token in save_manager, token
fx = (root / 'scripts' / 'event_fx.gd').read_text(encoding='utf-8')
for token in ('降雨', '陨石灾害', 'draw_line', 'draw_circle'):
    assert token in fx, token
starmap = (root / 'scripts' / 'starmap_view.gd').read_text(encoding='utf-8')
for token in ('卫星', '空间站', '深空探测', '载人探索', 'simulation.space_program.missions', 'draw_line'):
    assert token in starmap, token
smoke = (root / 'tests' / 'simulation_smoke.gd').read_text(encoding='utf-8')
for token in ('diplomacy', 'technology', 'space program', 'save restore'):
    assert token in smoke, token
effect_player = (root / 'scripts' / 'effect_player.gd').read_text(encoding='utf-8')
for token in ('effect-resource-gathering', 'effect-rocket-launch', 'effect-weather-disaster', 'AnimatedSprite2D', 'animation_finished'):
    assert token in effect_player, token
print(f'PASS: {len(gd)} GDScript files, {len(assets)} PNG assets, expanded systems and Android preset present')
