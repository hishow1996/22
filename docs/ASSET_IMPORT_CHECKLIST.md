# Unity 美术资源导入检查清单

本清单对应 `Assets/Art/Generated/` 中的正式 PNG 资源。当前资源扫描结果为 **105 张 PNG**，其中 **77 张为处理版资源**。由于当前执行环境没有 Unity Editor，以下检查需要在 Unity 2022.3 LTS 中首次导入时完成。

## 通用导入设置

| 资源类别 | Texture Type | Filter Mode | Compression | Mip Maps | Pixels Per Unit |
|---|---|---|---|---|---:|
| 地形、过渡、建筑、角色 | Sprite (2D and UI) | Point | None | Off | 32 |
| UI 图标与按钮 | Sprite (2D and UI) | Point | None | Off | 96 |
| 特效帧 | Sprite (2D and UI) | Point | None | Off | 32 |
| 源图集 | Sprite (2D and UI) | Point | None | Off | 按切片尺寸设置 |

处理版资源默认使用 `Single` Sprite Mode。源图集需要在 Sprite Editor 中按规范切片，或继续使用 `tools/process_art.py` 生成单帧处理版。

## 场景字段绑定

| 组件 | 字段 | 文件 |
|---|---|---|
| `WorldTilemapRenderer` | `oceanTile` 至 `riverTile` | `processed-terrain-*.png` 创建的 Tile |
| `WorldTilemapRenderer` | `shorelineTile` 至 `urbanPlazaTile` | `processed-transition-*.png` 创建的 Tile |
| `WorldTilemapRenderer` | `campfireTile` | `processed-campfire.png` 创建的 Tile |
| `WorldTilemapRenderer` | 其他建筑 Tile 字段 | 对应五时代建筑处理版 PNG 创建的 Tile |
| `GraphicsSettingsPanel` | `antiAliasingToggle`、`shadowsToggle`、`particlesToggle`、`anisotropicToggle`、`vSyncToggle` | Canvas Toggle |
| `GraphicsSettingsPanel` | `textureQualityDropdown`、`frameRateDropdown` | Canvas Dropdown |
| `PortraitHudController` | `game`、`portraitCanvas` | 主控制器与竖屏 Canvas |

## 角色和特效

将 `processed-primordial-settler-walk-01.png` 至 `04.png` 和 `processed-space-astronaut-walk-01.png` 至 `04.png` 创建为 Animator 帧。资源采集、火箭发射、天气灾害特效同样分别使用 `01` 至 `04` 帧，并放置在 `Effects` Sorting Layer。

## 首次导入验收

1. 确认所有 PNG 在 Project 窗口中无紫色材质、无缺失引用和无导入错误。
2. 确认地形、过渡和建筑 Tile 的 Filter Mode 为 Point，避免移动端出现模糊边缘。
3. 确认 UI 图标的点击区域至少为 48×48 像素，不要直接以窄图标尺寸作为 Button RectTransform。
4. 打开 `Main.unity`，运行场景并确认三层 Tilemap 的排序顺序为地形、覆盖层、建筑层。
5. 运行 Android 真机测试，确认竖屏、像素清晰度、特效开关和低端预设均生效。

## 当前扫描结论

文档引用的处理版资源均已在 `Assets/Art/Generated/` 找到。文档中的 `01..04` 表示连续帧范围，不是单个文件名；实际文件应逐一检查 `01`、`02`、`03`、`04` 是否存在。
