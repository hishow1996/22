# 主场景配置清单

## 场景对象

| 对象 | 必需组件 | 绑定 |
|---|---|---|
| Main Camera | Camera、PixelCameraSettings、PortraitCameraController | 主摄像机 |
| World | CivilizationGameController | WorldTilemapRenderer |
| World Tilemap | Grid、Tilemap、TilemapRenderer、WorldTilemapRenderer | 六类正式像素 Tile |
| Canvas | Canvas、CanvasScaler、PortraitHudController | 游戏控制器 |
| EventSystem | EventSystem、InputSystemUIInputModule | Canvas 输入 |

## 自动引导基线

`RuntimeSceneBootstrap` 会在场景启动前检查核心控制器；如果 `Main.unity` 尚未在编辑器中完成手工绑定，它会自动创建 `World`、三层 Tilemap、人口单位层、Canvas、HUD、性能设置和 EventSystem，并锁定为竖屏安全基线。这样项目可以先启动并运行模拟，不会因为空引用直接退出。

自动引导器不会猜测正式美术资源。六类地形 Tile、过渡层、道路层、建筑层和 UI 图标仍应在 Unity Editor 中按下表绑定；未绑定的 Tile 会被安全跳过，而不是伪造占位美术。

## UI 布局

竖屏安全区采用顶部资源条、中部地图、底部控制条。底部控制条必须至少包含暂停、减速、正常、加速、资源赠送、天气、灾害和科技树入口。所有按钮使用 48 px 以上触控区域，避免在手机上误触。

## 正式资源绑定

Tile、单位、建筑和 UI 图标必须来自同一份 `docs/ART_BIBLE.md` 规范。导入设置使用 Point Filter、无损压缩和统一 Pixels Per Unit；资源未通过风格审核前不能绑定到正式场景。
