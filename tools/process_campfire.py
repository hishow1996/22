from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1] / 'Assets' / 'Art' / 'Generated'
source = root / 'campfire.png'
target = root / 'processed-campfire.png'
image = Image.open(source).convert('RGBA')
bbox = image.getchannel('A').getbbox()
if bbox is not None:
    image = image.crop(bbox)
scale = min(1.0, 256 / max(image.size))
size = (max(1, int(image.width * scale)), max(1, int(image.height * scale)))
image.resize(size, Image.Resampling.NEAREST).save(target, optimize=True)
print(f'{source.name} -> {target.name} {size[0]}x{size[1]} alpha_bbox={bbox}')
