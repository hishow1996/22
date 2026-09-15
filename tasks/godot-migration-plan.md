# Godot Migration Implementation Plan

> **Goal:** 将文明沙盒迁移为可独立打开的 Godot 4 Android 竖屏项目。

**Architecture:** `Main` 负责场景编排，`CivilizationSimulation` 负责纯数据与 Tick，`WorldView` 负责地图绘制，`Hud` 负责输入和状态展示，`SaveManager` 负责 JSON 持久化。所有模块通过信号和公开方法通信。

**Tech Stack:** Godot 4.x、GDScript、Node2D、CanvasLayer、JSON、PNG。

**Spec:** `GODOT_MIGRATION_SPEC.md`

## Global Constraints

- 目标设备为 Android 竖屏。
- 复用 `Assets/Art/Generated` 中处理版 PNG，不依赖 Unity API。
- 五个时代必须可推进。
- 不在没有 Godot/Android 工具链时声称 APK 已完成。

---

### Task 1: Project scaffold

**Files:** `GodotProject/project.godot`, `GodotProject/main.tscn`, `GodotProject/scripts/main.gd`

- [x] 创建 Godot 项目配置、竖屏窗口、输入动作和主场景。
- [x] 验证项目文件包含 Godot 4 配置段。

### Task 2: Simulation and world

**Files:** `GodotProject/scripts/civilization_simulation.gd`, `GodotProject/scripts/world_view.gd`

- [x] 实现确定性地图、资源、人口、时代、建筑和太空任务。
- [x] 实现地图重绘和时代颜色变化。

### Task 3: HUD and persistence

**Files:** `GodotProject/scripts/hud.gd`, `GodotProject/scripts/save_manager.gd`

- [x] 实现竖屏 HUD、控制按钮、事件日志、设置开关。
- [x] 实现 JSON 保存、加载和 PlayerPrefs 等价配置。

### Task 4: Assets and Android handoff

**Files:** `GodotProject/assets/*`, `GodotProject/export_presets.cfg`, `docs/GODOT_BUILD.md`

- [x] 复制处理版 PNG 并设置 Point Filter。
- [x] 写入 Android 导出预设和构建说明。

### Task 5: Verification

- [ ] 运行 Godot headless 检查（若编辑器可用）。
- [x] 运行静态 GDScript、资源和配置检查。
- [ ] 提交到 GitHub `hishow1996/33`。
