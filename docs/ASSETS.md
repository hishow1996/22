# 美术资源清单

## 原始时代第一批正式资源

| 资源 | 原始文件 | Unity 使用文件 | 用途 |
|---|---|---|---|
| 原始人 | `Assets/Art/Generated/primordial-settler.png` | `processed-primordial-settler.png` | 人口单位、采集者基础外观 |
| 部落木屋 | `Assets/Art/Generated/primordial-hut.png` | `processed-primordial-hut.png` | 原始时代住宅/聚落建筑 |
| 资源物件 | `Assets/Art/Generated/primordial-resource-cluster.png` | `processed-primordial-resource-cluster.png` | 树木、石块和食物资源视觉参考 |

## 处理规则

原始生成图保留在 `Generated/` 作为高分辨率源资产；处理版使用透明通道、Alpha 保留和最近邻缩放，便于在 Unity 中使用 Point Filter。处理版不是简单颜色占位，而是从正式生成素材裁切和缩放得到的可用游戏资源。

## Unity 导入设置

- Texture Type：Sprite (2D and UI)
- Sprite Mode：Single；资源簇后续可按透明区域拆分为多个 Sprite
- Pixels Per Unit：32
- Filter Mode：Point (no filter)
- Compression：None 或无损压缩
- Generate Mip Maps：关闭
- Read/Write：关闭，除非运行时需要像素采样
- Mesh Type：Tight；需要稳定锚点的角色动画改为 Full Rect

## 风格检查

这批资源采用明亮精致卡通像素风、轻微斜俯视、左上光源、右下投影和深蓝紫轮廓。它们用于建立原始时代的视觉基准；后续农业、工业、现代和太空时代资源必须沿用同一像素密度、阴影方向和调色规则。

## 农业时代第一批正式资源

| 资源 | 原始文件 | Unity 使用文件 | 用途 |
|---|---|---|---|
| 农民 | `Assets/Art/Generated/agrarian-farmer.png` | `processed-agrarian-farmer.png` | 农业时代职业单位 |
| 农田 | `Assets/Art/Generated/agrarian-farm.png` | `processed-agrarian-farm.png` | 食物生产建筑/资源区 |
| 粮仓 | `Assets/Art/Generated/agrarian-granary.png` | `processed-agrarian-granary.png` | 农业时代储存与贸易建筑 |

农业时代资源沿用原始时代的像素密度、左上光源、右下投影和深蓝紫轮廓，同时增加麦田金色、砖墙暖红和绿色农作物，以体现文明从部落采集进入定居农业的视觉升级。

## 工业时代第一批正式资源

| 资源 | 原始文件 | Unity 使用文件 | 用途 |
|---|---|---|---|
| 工程师 | `Assets/Art/Generated/industrial-engineer.png` | `processed-industrial-engineer.png` | 工业时代职业单位 |
| 工厂 | `Assets/Art/Generated/industrial-factory.png` | `processed-industrial-factory.png` | 金属与工业生产建筑 |
| 蒸汽动力设施 | `Assets/Art/Generated/industrial-steamworks.png` | `processed-industrial-steamworks.png` | 电力/蒸汽科技建筑 |

工业时代资源加入钢铁、铜、砖墙、煤炭、齿轮、烟囱和蒸汽等视觉语言，让地图从农业生产自然升级为工业化城市。

## 现代时代第一批正式资源

| 资源 | 原始文件 | Unity 使用文件 | 用途 |
|---|---|---|---|
| 科学家 | `Assets/Art/Generated/modern-scientist.png` | `processed-modern-scientist.png` | 现代时代科研职业单位 |
| 研究中心 | `Assets/Art/Generated/modern-research-center.png` | `processed-modern-research-center.png` | 科研和现代科技建筑 |
| 电力设施 | `Assets/Art/Generated/modern-power-grid.png` | `processed-modern-power-grid.png` | 现代电网和电力生产建筑 |

现代时代资源引入白色混凝土、蓝色玻璃、太阳能板、卫星天线和青色电能光效，形成从工业时代钢铁蒸汽到现代科技社会的视觉升级。

## 太空时代第一批正式资源

| 资源 | 原始文件 | Unity 使用文件 | 用途 |
|---|---|---|---|
| 宇航员 | `Assets/Art/Generated/space-astronaut.png` | `processed-space-astronaut.png` | 太空时代职业单位 |
| 火箭发射场 | `Assets/Art/Generated/space-launch-site.png` | `processed-space-launch-site.png` | 火箭制造与发射建筑 |
| 空间站 | `Assets/Art/Generated/space-station.png` | `processed-space-station.png` | 轨道设施与深空探索建筑 |

太空时代资源引入白色与深海军蓝结构、青色灯光、太阳能板、天线和轨道舱体，完成原始、农业、工业、现代、太空五个时代的第一批视觉基准。
