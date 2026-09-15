# HUD 图标资源清单

## 图集

| 图集 | 内容 |
|---|---|
| `hud-resource-icons.png` | 人口、食物、木材、石材、金属、电力、科研 |
| `hud-era-badges.png` | 原始、农业、工业、现代、太空五个时代徽章 |
| `hud-space-mission-icons.png` | 卫星、空间站、深空探测器、载人探索 |

## Unity 独立图标

处理脚本已将图集拆为 16 个独立透明 PNG，命名为 `processed-hud-*.png`，最大边长 96 像素，适合放入竖屏 HUD 的 Image、Button 和状态卡。独立图标保留 Point Filter 和硬像素边缘，不在运行时重复裁切。

## 绑定建议

- `PortraitHudState.EraLabel` 绑定时代徽章和中文标题。
- 资源数值字段绑定对应 `processed-hud-population`、`food`、`wood`、`stone`、`metal`、`electricity`、`science`。
- `SpaceMissionLabel` 绑定任务图标：`satellite`、`space-station`、`deep-space-probe` 或 `crewed-exploration`。
- 图标建议使用 48–64 像素显示尺寸，并确保按钮触控区域至少 48×48 像素。
- 图集源文件保留为美术归档；正式 Canvas 优先使用拆分后的独立资源。
