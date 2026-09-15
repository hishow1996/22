# 时代建筑布局

`SettlementLayoutPlanner` 根据当前时代和地图地形，自动选择可建设的草地、泥地或森林格，并放置对应建筑视觉：原始时代使用篝火和木屋，农业时代增加农田，工业时代增加工坊和工厂，现代时代增加研究中心，太空时代增加发射场。

`WorldTilemapRenderer` 新增独立 `buildingTilemap` 和七个建筑 Tile 字段。启动时先渲染基础地形，再渲染过渡/道路覆盖层，最后渲染建筑层。这样建筑不会被地形覆盖，也不会改变世界模拟数据。

## Unity 场景绑定

1. 在主场景创建第三个 Tilemap，命名为 `BuildingTilemap`。
2. 将它拖入 `WorldTilemapRenderer.buildingTilemap`。
3. 将木屋、农田、工坊、工厂、研究中心、篝火和发射场 Sprite 创建为 Tile Asset。
4. 分别绑定到对应建筑字段。
5. 设置 Sorting Order：基础地形 0，覆盖层 10，建筑层 20，单位层 30，特效层 40，Canvas UI 最上层。

规划器是确定性的，同一张地图和同一时代会产生相同的建筑位置，便于测试和存档恢复。正式版本可把自动布局替换为玩家放置或国家城市扩张系统。
