# 地形过渡与道路资源

## 资源映射

| 资源 | 处理文件 | 用途 |
|---|---|---|
| 海岸线 | `processed-transition-shoreline.png` | Ocean 与沙滩/陆地边缘 |
| 河岸 | `processed-transition-riverbank.png` | River 与 Grass 边缘 |
| 草地泥地边缘 | `processed-transition-grass-dirt-edge.png` | Grass 与 Dirt 过渡 |
| 石板道路 | `processed-transition-cobblestone-road.png` | 聚落与农业时代道路 |
| 石桥 | `processed-transition-stone-bridge.png` | 跨越河流的连接设施 |
| 城市广场 | `processed-transition-urban-plaza.png` | 现代时代城市道路与中心区域 |

## 接入策略

这些资源作为 Rule Tile 的边缘和装饰变体使用，不直接替换现有六种 `TerrainType` 基础 Tile。海岸、河岸和草地泥地边缘先作为过渡层绘制；道路、桥梁和城市广场作为聚落基础设施层绘制。这样不会破坏现有世界生成器的地形枚举，同时允许后续加入道路网络和城市扩张系统。

所有处理版均为 256×256 RGBA PNG，导入 Unity 时使用 Point Filter、Pixels Per Unit 32、关闭 Mip Maps 和无损压缩。
