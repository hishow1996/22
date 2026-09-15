# 太空运行时 UI

竖屏太空面板现在通过 `CivilizationGameController` 统一执行空间站、深空探测和载人探索。按钮不再直接操作领域对象，因此成功任务会自动播放反馈并保存存档，失败任务不会产生成功事件。

| 按钮 | 控制器方法 | 成功事件 |
|---|---|---|
| 建造空间站 | `TryBuildSpaceStation()` | `SpaceStationBuilt` |
| 发射深空探测器 | `TryLaunchDeepSpaceProbe()` | `DeepSpaceProbeLaunched` |
| 发射载人探索 | `TryLaunchCrewedExploration()` | `CrewedExplorationLaunched`，若有新发现则追加 `CelestialBodyDiscovered` |

HUD 状态包含 `HasDeepSpaceData` 和 `DiscoveredBodies`，可用于显示深空资料和星图进度。所有成功任务都会立即写入运行时 JSON 存档，加载后会恢复任务状态和已发现星体。

`VfxEventPresenter` 提供四个 UnityEvent 入口，可绑定发射动画、空间站建造动画、发现提示和星图 UI 刷新。
