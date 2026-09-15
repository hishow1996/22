from pathlib import Path
from PIL import Image

root = Path('/home/ubuntu/civilization-sandbox/Assets/Art/Generated')
outputs = {
    'primordial-settler.png': 256,
    'primordial-hut.png': 256,
    'primordial-resource-cluster.png': 384,
    'agrarian-farmer.png': 256,
    'agrarian-farm.png': 384,
    'agrarian-granary.png': 256,
    'industrial-engineer.png': 256,
    'industrial-factory.png': 384,
    'industrial-steamworks.png': 384,
    'modern-scientist.png': 256,
    'modern-research-center.png': 384,
    'modern-power-grid.png': 384,
    'space-astronaut.png': 256,
    'space-launch-site.png': 384,
    'space-station.png': 384,
}
for name, size in outputs.items():
    source = root / name
    image = Image.open(source).convert('RGBA')
    alpha = image.getchannel('A')
    bbox = alpha.getbbox()
    if bbox is not None:
        image = image.crop(bbox)
    scale = min(1.0, size / max(image.size))
    target = (max(1, int(image.width * scale)), max(1, int(image.height * scale)))
    image = image.resize(target, Image.Resampling.NEAREST)
    image.save(root / f'processed-{name}', optimize=True)
    print(name, '->', target, 'alpha_bbox=', bbox)

sprite_sheets = {
    'hud-resource-icons.png': ['population', 'food', 'wood', 'stone', 'metal', 'electricity', 'science'],
    'hud-era-badges.png': ['primordial', 'agrarian', 'industrial', 'modern', 'space'],
    'hud-space-mission-icons.png': ['satellite', 'space-station', 'deep-space-probe', 'crewed-exploration'],
    'ui-control-buttons.png': ['pause', 'slower', 'normal-speed', 'faster', 'add-food', 'rain', 'meteor', 'technology-tree'],
    'ui-tech-tree-nodes.png': ['fire', 'farming', 'steam', 'modern-science', 'space'],
}
for sheet_name, labels in sprite_sheets.items():
    image = Image.open(root / sheet_name).convert('RGBA')
    cell_width = image.width // len(labels)
    for index, label in enumerate(labels):
        left = index * cell_width
        right = image.width if index == len(labels) - 1 else (index + 1) * cell_width
        cell = image.crop((left, 0, right, image.height))
        bbox = cell.getchannel('A').getbbox()
        if bbox is not None:
            cell = cell.crop(bbox)
        scale = min(1.0, 96 / max(cell.size))
        target = (max(1, int(cell.width * scale)), max(1, int(cell.height * scale)))
        prefix = 'hud' if sheet_name.startswith('hud-') else ('ui-control' if sheet_name == 'ui-control-buttons.png' else 'ui-tech')
        cell.resize(target, Image.Resampling.NEAREST).save(root / f'processed-{prefix}-{label}.png', optimize=True)
        print(sheet_name, label, '->', target)

panel = Image.open(root / 'ui-space-mission-card.png').convert('RGBA')
bbox = panel.getchannel('A').getbbox()
if bbox is not None:
    panel = panel.crop(bbox)
scale = min(1.0, 512 / max(panel.size))
target = (max(1, int(panel.width * scale)), max(1, int(panel.height * scale)))
panel.resize(target, Image.Resampling.NEAREST).save(root / 'processed-ui-space-mission-card.png', optimize=True)
print('ui-space-mission-card.png ->', target)

terrain_sheet = Image.open(root / 'world-terrain-tiles.png').convert('RGBA')
terrain_names = ['ocean', 'grass', 'dirt', 'forest', 'mountain', 'river']
cell_width = terrain_sheet.width // 3
cell_height = terrain_sheet.height // 2
for index, name in enumerate(terrain_names):
    x = index % 3
    y = index // 3
    tile = terrain_sheet.crop((x * cell_width, y * cell_height, (x + 1) * cell_width, (y + 1) * cell_height))
    tile = tile.resize((256, 256), Image.Resampling.NEAREST)
    tile.save(root / f'processed-terrain-{name}.png', optimize=True)
    print('world-terrain-tiles.png', name, '->', tile.size)

transition_sheet = Image.open(root / 'world-transition-tiles.png').convert('RGBA')
transition_names = ['shoreline', 'riverbank', 'grass-dirt-edge', 'cobblestone-road', 'stone-bridge', 'urban-plaza']
cell_width = transition_sheet.width // 3
cell_height = transition_sheet.height // 2
for index, name in enumerate(transition_names):
    x = index % 3
    y = index // 3
    tile = transition_sheet.crop((x * cell_width, y * cell_height, (x + 1) * cell_width, (y + 1) * cell_height))
    tile = tile.resize((256, 256), Image.Resampling.NEAREST)
    tile.save(root / f'processed-transition-{name}.png', optimize=True)
    print('world-transition-tiles.png', name, '->', tile.size)

animation_sheets = {
    'primordial-settler-walk-sheet.png': 'primordial-settler-walk',
    'space-astronaut-walk-sheet.png': 'space-astronaut-walk',
}
for sheet_name, prefix in animation_sheets.items():
    sheet = Image.open(root / sheet_name).convert('RGBA')
    cell_width = sheet.width // 2
    cell_height = sheet.height // 2
    for index in range(4):
        x = index % 2
        y = index // 2
        frame = sheet.crop((x * cell_width, y * cell_height, (x + 1) * cell_width, (y + 1) * cell_height))
        frame = frame.resize((256, 256), Image.Resampling.NEAREST)
        frame.save(root / f'processed-{prefix}-{index + 1:02d}.png', optimize=True)
        print(sheet_name, index + 1, '->', frame.size)
