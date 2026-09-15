# 竖屏文明事件面板

`WorldEventLogPanel` 将 `WorldEventLog` 的最近事件转换为可直接显示的多行文本，默认显示最近 8 条并按最新事件在前排列。事件面板每帧同步世界日志，加载存档或时代升级后会自动刷新。

## Unity 绑定

1. 在竖屏 Canvas 中创建事件面板背景和文本区域。
2. 添加 `WorldEventLogPanel` 组件。
3. 将 `CivilizationGameController` 拖入 `game`。
4. 将面板内的 `Text` 组件拖入 `eventText`。
5. 根据屏幕空间调整 `visibleEntries`，建议竖屏显示 6–8 条。

事件面板位于底部控制栏上方，不遮挡地图中心区域。正式 UI 可将 `Text` 替换为 TextMeshPro 适配器，但事件日志数据协议保持不变。
