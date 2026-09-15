# 科技树 UI 接入

竖屏 HUD 现在提供 `ResearchTechnology(string technologyId)`，科技树节点按钮将自己的节点 ID 传入即可发起研究。研究成功时，控制器扣除科研点数、标记科技解锁、发出 `TechnologyResearched` 事件并立即保存存档；研究失败时不扣除资源，也不发出成功特效。

## 节点绑定

| 节点按钮 | 传入 ID |
|---|---|
| 掌握火种 | `fire` |
| 石器工具 | `toolmaking` |
| 定居农业 | `farming` |
| 砖石建筑 | `masonry` |
| 蒸汽动力 | `steam` |
| 电力网络 | `electricity` |
| 计算机 | `computing` |
| 轨道火箭 | `rocketry` |
| 深空飞行 | `spaceflight` |

节点显示应读取 `PortraitHudState.UnlockedTechnologyCount` 和 `AvailableTechnologyCount`，并在 `StateChanged` 事件后刷新锁定、可研究或已完成状态。研究完成提示可绑定到 `VfxEventPresenter.onTechnologyResearched`。
