# 竖屏科技树面板

`TechnologyTreePanel` 根据 `EraTechnologyCatalog.All` 动态生成科技节点按钮，并显示三种状态：已完成、可研究、锁定。节点的时代条件和前置科技由 `TechnologyState` 统一判断；点击可研究节点会调用 `CivilizationGameController.TryResearchTechnology`。

## Unity 绑定

1. 在竖屏 Canvas 创建科技树窗口和滚动区域。
2. 添加 `TechnologyTreePanel` 组件。
3. 将 `CivilizationGameController` 绑定到 `game`。
4. 创建一个带 `Button` 和子级 `Text` 的节点预制体，绑定到 `nodeTemplate`。
5. 将滚动区域的 Content 绑定到 `nodeRoot`。
6. 将顶部统计文本绑定到 `summaryText`。

面板在时代升级、科技研究成功和存档加载后刷新，不会每帧重建 UI。研究节点按钮会根据状态自动设置 `interactable`；完成或锁定节点不可点击。
