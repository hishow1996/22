from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'GodotProject'
required = [root / 'project.godot', root / 'main.tscn', root / 'export_presets.cfg']
required += [root / 'scripts' / name for name in ('main.gd', 'civilization_simulation.gd', 'world_view.gd', 'hud.gd', 'save_manager.gd', 'diplomacy_system.gd', 'technology_system.gd', 'population_system.gd', 'space_program.gd', 'event_bridge.gd')]
for path in required:
    assert path.exists() and path.stat().st_size > 0, path

gd = list((root / 'scripts').glob('*.gd'))
assert len(gd) >= 10
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
print(f'PASS: {len(gd)} GDScript files, {len(assets)} PNG assets, expanded systems and Android preset present')
