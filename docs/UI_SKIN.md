# 竖屏 UI 皮肤资源

## 控制按钮

`processed-ui-control-*.png` 包含暂停、减速、正常速度、加速、资源赠送、降雨、陨石和科技树八个按钮图标。它们绑定到 `PortraitHudController` 的控制入口，按钮的可点击区域应扩展到至少 48×48 像素，图标本身保持 Point Filter。

## 科技树节点

`processed-ui-tech-*.png` 包含火种、农业、蒸汽、现代科学和太空五个节点图标。节点状态使用材质或 CanvasGroup 表示：已解锁使用全亮色，未解锁使用低饱和度，当前可研究使用金色外环。

## 太空任务卡

`processed-ui-space-mission-card.png` 是竖屏任务卡背景，显示尺寸建议不超过 322×512 设计像素。文字、任务图标和按钮由 Unity UI 文本与独立图标叠加绑定，生成图本身不包含可变文字，因此可以适配中文和不同任务状态。

## 导入设置

所有 UI 资源使用 Sprite (2D and UI)、Point Filter、关闭 Mip Maps、无损压缩或不压缩。按钮和节点图标不应使用九宫格拉伸；任务卡背景可以使用九宫格，但必须先在 Sprite Editor 中设置边界，保护发光边框和四角装饰。
