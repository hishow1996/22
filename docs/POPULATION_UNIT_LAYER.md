# 人口单位层

`PopulationUnitLayer` 负责把模拟中的 `PopulationAgent` 转换成 Unity 场景对象。初始化时为每个存活人口生成一个 `PopulationUnitPresenter`，优先使用 Inspector 绑定的单位预制体；没有预制体时会生成带有 Presenter 的空对象，保证运行时同步链路仍然可用。

单位层每帧读取 `CivilizationGameController.Movement.Positions`，将网格位置转换为世界坐标，并调用 Presenter 的 `Sync`。Presenter 根据位置是否变化设置 Animator 的 `IsMoving` 参数。

## Unity 绑定

1. 在主场景创建 `PopulationUnitLayer` 对象。
2. 将它拖到 `CivilizationGameController.populationUnitLayer`。
3. 制作带 `PopulationUnitPresenter`、`SpriteRenderer` 和 `Animator` 的单位预制体并绑定到 `unitPrefab`。
4. Animator 创建 `IsMoving` Bool 参数，并绑定 Idle/Walk 状态。
5. 设置单位 Sorting Layer 为 `Units`，排序层级高于建筑、低于事件特效。

单位预制体可以按时代替换：原始时代使用原始人图集，太空时代使用宇航员图集；移动系统和单位层无需修改。
