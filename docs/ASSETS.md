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
