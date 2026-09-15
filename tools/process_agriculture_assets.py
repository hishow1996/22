from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1]
src = root / "Assets" / "Art" / "Generated"
out = root / "GodotProject" / "assets"
out.mkdir(parents=True, exist_ok=True)

sets = {
    "agriculture-assets-source.png": [
        "farm-tilled", "crop-seedling", "crop-growing", "crop-wheat-ripe",
        "crop-corn", "crop-vegetables", "chicken-coop", "barn",
        "animal-pasture", "water-trough", "hay-bale", "animal-chicken",
        "animal-sheep", "animal-cow", "harvest-basket", "vegetable-basket",
    ],
    "animal-assets-source.png": [
        "animal-chicken-idle", "animal-chicken-walk", "animal-sheep-idle", "animal-sheep-walk",
        "animal-cow-idle", "animal-cow-walk", "animal-pig-idle", "animal-pig-walk",
        "animal-feed", "animal-egg", "animal-milk", "animal-wool",
        "animal-pen", "animal-gate", "animal-water", "animal-hay",
    ],
    "tool-assets-source.png": [
        "tool-stone-axe", "tool-stone-pickaxe", "tool-stone-hoe", "tool-wood-hammer",
        "tool-iron-axe", "tool-iron-pickaxe", "tool-iron-hoe", "tool-iron-hammer",
        "crafting-bench", "forge", "anvil", "rope",
        "wood-planks", "stone-shard", "iron-ingot", "tool-durability",
    ],
    "farming-effects-source.png": [
        "fx-seed-sparkle", "fx-water-splash", "fx-harvest-sparkle", "fx-crop-growth",
        "fx-chicken-dust", "fx-sheep-wool", "fx-cow-dust", "fx-feeding-heart",
        "fx-crafting-sparks", "fx-hammer-sparks", "fx-forge-flame", "fx-anvil-impact",
        "fx-tool-break", "fx-food-burst", "fx-farm-levelup", "fx-leaf",
    ],
}

for filename, names in sets.items():
    image = Image.open(src / filename).convert("RGBA")
    cell = image.width // 4
    for index, name in enumerate(names):
        x, y = (index % 4) * cell, (index // 4) * cell
        tile = image.crop((x, y, x + cell, y + cell))
        alpha = tile.getchannel("A")
        bbox = alpha.getbbox()
        if bbox:
            tile = tile.crop(bbox)
        tile.thumbnail((256, 256), Image.Resampling.LANCZOS)
        canvas = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
        canvas.alpha_composite(tile, ((256 - tile.width) // 2, (256 - tile.height) // 2))
        canvas.save(out / f"processed-{name}.png")
print(f"processed {sum(len(v) for v in sets.values())} agriculture assets")
