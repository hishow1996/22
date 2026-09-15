# 世界运行时资源接入

经济循环现在支持 `Action<ResourceType, int>` 产出回调。游戏控制器将回调转换为 `VfxEventType.ResourceGathered`，因此采集者、农民、建造者、工程师、科学家和宇航员生产资源时都可以播放统一的采集反馈特效。

`WorldTilemapRenderer` 现在保留基础地形 Tilemap 和独立的 `overlayTilemap`。基础层绑定海洋、草地、泥地、森林、山地和河流；覆盖层绑定海岸线、河岸、草地泥地边缘、石板道路、石桥和城市广场。通过 `RenderOverlays(IEnumerable<WorldOverlayPlacement>)` 可以在不改写基础地形的情况下铺设道路和过渡层。

## 场景绑定

1. 在 `Main` 场景中创建第二个 Tilemap，命名为 `WorldOverlayTilemap`。
2. 将它拖入 `WorldTilemapRenderer.overlayTilemap`。
3. 将六个 `processed-transition-*.png` 创建为 Tile Asset，并分别拖到过渡层字段。
4. 地图生成完成后，构造 `WorldOverlayPlacement` 列表并调用 `RenderOverlays`。
5. 将覆盖层 Sorting Order 放在基础地形之上、单位之下。
