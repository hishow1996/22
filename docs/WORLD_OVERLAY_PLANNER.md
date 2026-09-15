# 地图覆盖层自动规划

`WorldOverlayPlanner` 在世界生成后根据相邻地形自动规划覆盖层：陆地邻接海洋时放置海岸线，陆地邻接河流时放置河岸，草地邻接泥地时放置地形边缘；同时沿地图中心规划一条道路，遇到河流时放置石桥。进入现代时代后，在地图中心增加城市广场。

`CivilizationGameController.Awake` 已在基础 Tilemap 渲染完成后调用规划器，并将结果交给 `WorldTilemapRenderer.RenderOverlays`。因此启动场景只要绑定基础 Tilemap、覆盖层 Tilemap 和对应 Tile Asset，生成地图即可自动出现道路和过渡视觉。

规划器使用固定输入地图和时代，不依赖随机数，便于测试、复现和存档加载。后续可把中心道路替换为聚落图、国家边界或玩家绘制的道路网络。
