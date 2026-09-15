# 人口移动与动画接入

`PopulationMovementSystem` 为每个存活人口维护确定性的网格坐标，并在模拟时间推进时沿四个方向移动。单位不能进入海洋和山地，边界会阻止越界。相同地图、人口 ID 和天数会得到相同位置结果，便于测试和存档复现。

`PopulationUnitPresenter` 将网格坐标转换为 Unity 世界坐标，并在位置变化时设置 Animator 的 `IsMoving` 参数。动画控制器使用 `Idle` 和 `Walk` 两个状态，`Walk` 使用现有四帧角色动画，建议 8 FPS 循环。

## 场景绑定

为每个单位预制体添加 `PopulationUnitPresenter`、`SpriteRenderer` 和 `Animator`。Animator 创建 `IsMoving` bool 参数；`false` 进入 Idle，`true` 进入 Walk。单位放置在 `Units` Sorting Layer，使用 Y 轴排序。

运行时生成单位对象后调用 `Bind(agent.Id)`，每次游戏更新从 `CivilizationGameController.Movement.Positions` 取出对应位置并调用 `Sync`。不同时代和职业可以替换 Sprite/Animator Controller，但共用同一移动坐标协议。
