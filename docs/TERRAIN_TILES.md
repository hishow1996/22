# 地形 Tile 资源

## 当前映射

| `TerrainType` | 资源 |
|---|---|
| `Ocean` | `processed-terrain-ocean.png` |
| `Grass` | `processed-terrain-grass.png` |
| `Dirt` | `processed-terrain-dirt.png` |
| `Forest` | `processed-terrain-forest.png` |
| `Mountain` | `processed-terrain-mountain.png` |
| `River` | `processed-terrain-river.png` |

## Unity 导入

六张处理版 Tile 均为 256×256 RGBA PNG。导入 Unity 后将 Texture Type 设为 Sprite (2D and UI)，Sprite Mode 设为 Single，Pixels Per Unit 设为 32，Filter Mode 设为 Point，关闭 Mip Maps，并创建对应的 Tile Asset。把六个 Tile Asset 分别拖入 `WorldTilemapRenderer` 的 `oceanTile`、`grassTile`、`dirtTile`、`forestTile`、`mountainTile` 和 `riverTile` 字段。

## 视觉说明

本批资源来自一张六格地形图集，统一了颜色、像素密度和俯视角。河流、森林和山地 Tile 含有较丰富的地形图案；正式扩大地图尺寸时，建议再补充边缘/转角变体，避免长距离重复纹理，并通过 Rule Tile 处理海岸、河岸和道路过渡。
