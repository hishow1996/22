from pathlib import Path
import re
from PIL import Image

root = Path(__file__).resolve().parents[1]
asset_script = (root / "tools" / "process_agriculture_assets.py").read_text()
expected = set(re.findall(r'"([a-z0-9-]+)"', asset_script))
expected = {name for name in expected if name != "assets" and "assets-source" not in name}
files = [root / "GodotProject" / "assets" / f"processed-{name}.png" for name in expected]
assert len(files) >= 64, len(files)
for path in files:
    assert path.exists(), path
    image = Image.open(path)
    assert image.mode == "RGBA" and image.size == (256, 256), path
print(f"PASS: {len(files)} new agriculture assets, RGBA 256x256")
