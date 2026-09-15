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
