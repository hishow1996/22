# VFX 运行时接入

`VfxEventBridge` 负责从游戏运行时发出结构化事件，`VfxEventPresenter` 负责把事件转成 Unity Inspector 可绑定的 `UnityEvent<int>`。这样模拟层不依赖具体 Sprite、Animator 或粒子预制体，场景设计者可以在不改领域代码的情况下替换特效表现。

## 已接入事件

| 事件 | 触发位置 | 绑定动画 |
|---|---|---|
| `RainStarted` | 竖屏 HUD 降雨按钮 | 天气特效帧 01 |
| `MeteorWarning` | 竖屏 HUD 陨石按钮 | 天气特效帧 03 |
| `MeteorImpact` | 陨石结算后 | 天气特效帧 04 |
| `RocketLaunch` | 火箭成功发射 | 火箭发射特效 01–04 |
| `ResourceGathered` | 采集事件接入点 | 资源采集特效 01–04 |

## 场景绑定

在主场景创建 `VfxEventPresenter`，把 `game` 绑定到 `CivilizationGameController`。每个 UnityEvent 绑定到一个特效播放组件或 Animator 播放方法，并用 `Intensity` 控制粒子数量、缩放或播放速度。事件播放器放在 `Effects` Sorting Layer，Canvas UI 仍保持在最上层。

## 设计约束

VFX 事件是单向通知，不改变资源、人口或科技数据。实际世界变化先由领域系统结算，再发出表现事件，避免特效播放成功却没有对应游戏结果。
