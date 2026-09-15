# 角色动画资源

## 当前动画

| 角色 | 源图集 | 处理帧 |
|---|---|---|
| 原始人 | `primordial-settler-walk-sheet.png` | `processed-primordial-settler-walk-01.png` 至 `04.png` |
| 宇航员 | `space-astronaut-walk-sheet.png` | `processed-space-astronaut-walk-01.png` 至 `04.png` |

## Unity Animator 设置

每个角色创建一个 `Walk` Animation Clip，将四张帧图按 8 FPS 排列，Loop Time 开启，Wrap Mode 使用 Loop。角色静止时使用原始单帧 Sprite；移动速度低于 0.05 时切回 Idle，移动速度达到阈值时切换 Walk。角色 SpriteRenderer 使用 Point Filter、Pixels Per Unit 32 和无损压缩。

## 锚点与排序

所有帧保持 256×256 画布和相同底部锚点，脚底位于画布底部约 12 像素处。单位放置在 Tilemap 上层，使用 `Sorting Layer = Units`，通过 Y 轴排序保证靠近屏幕下方的单位显示在上方单位之前。

## 后续扩展

农业、工业和现代单位沿用同一四帧结构继续制作；采集、建造、工作和火箭发射可以在 Walk 完成后追加独立动作状态，不与移动动画混用。
