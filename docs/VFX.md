# 事件特效资源

## 当前特效

| 特效 | 源图集 | 处理帧 | 触发入口 |
|---|---|---|---|
| 资源采集 | `effect-resource-gathering-sheet.png` | `processed-effect-resource-gathering-01.png` 至 `04.png` | 人口采集完成、资源增加 |
| 火箭发射 | `effect-rocket-launch-sheet.png` | `processed-effect-rocket-launch-01.png` 至 `04.png` | 火箭、探测器或载人任务发射 |
| 天气灾害 | `effect-weather-disaster-sheet.png` | `processed-effect-weather-disaster-01.png` 至 `04.png` | 降雨、闪电、陨石预警和撞击 |

## Unity 播放设置

每组特效创建独立 Animation Clip。资源采集使用 12 FPS 并播放一次；火箭发射使用 8 FPS 并播放一次；天气灾害根据事件类型选择 8–12 FPS，陨石预警先播放第 3 帧再播放第 4 帧。所有 Clip 关闭 Loop，结束后由事件控制器销毁或切换为空 Sprite。

所有特效帧为 256×256 RGBA PNG，使用 Point Filter、关闭 Mip Maps、无损压缩。特效使用 `Sorting Layer = Effects`，显示在单位和建筑之上，但保持在 UI Canvas 之下。
