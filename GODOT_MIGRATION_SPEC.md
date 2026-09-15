# Godot 迁移规格：文明沙盒：星际纪元

## Objective

在现有 Unity 项目旁建立一个 Godot 4 原生项目，复用处理版像素 PNG，并用 GDScript 重写文明沙盒的核心可玩循环。目标平台为 Android 竖屏，首个 Godot 版本必须能生成地图、显示聚落、推进时代、更新资源和人口、触发上帝能力、保存/加载，并提供低端设备图形设置。

## Tech Stack

- Godot 4.x
- GDScript
- 2D Node2D 与 CanvasLayer
- Android 导出模板
- PNG Sprite2D / ImageTexture
- JSON 存档

## Commands

- Editor：用 Godot 4 打开 `GodotProject/project.godot`
- Headless 检查：`godot --headless --path GodotProject --editor --quit`
- Android APK：`godot --headless --path GodotProject --export-debug "Android" Builds/CivilizationSandbox-Godot.apk`

## Project Structure

```text
GodotProject/
  project.godot       Godot 项目设置与 Android 竖屏配置
  main.tscn           主场景
  scripts/             模拟、世界、存档与 UI 脚本
  assets/              复用的处理版像素资源
  tests/               可执行的轻量脚本测试
```

## Core Systems

1. 确定性 64×96 世界生成：海洋、草地、泥地、森林、山地、河流。
2. 资源：食物、木材、石材、金属、电力、燃料、科研点。
3. 五时代：原始、农业、工业、现代、太空。
4. 人口增长、职业分配、资源生产和时代推进。
5. 建筑布局：篝火、木屋、农田、工坊、工厂、研究中心、发射场。
6. 上帝能力：暂停、时间倍率、降雨、陨石、资源赠送。
7. 太空任务：卫星、空间站、深空探测、载人探索。
8. JSON 保存和加载。
9. 竖屏 HUD、触控按钮、图形性能设置与 PlayerPrefs 保存。

## Success Criteria

- Godot 能识别 `project.godot` 并打开 `main.tscn`。
- 运行时显示像素风地图、聚落和 HUD。
- 点击速度、降雨、陨石、加食物、保存、加载按钮有实际效果。
- 资源和人口随模拟时间更新，满足条件时时代推进。
- 太空时代可以触发火箭、空间站和深空任务记录。
- 竖屏窗口和 Android 导出配置已写入项目。
- 无 Unity API 依赖，核心 Godot 脚本可被静态检查。

## Boundaries

- Always：保留 Unity 工程作为历史版本；Godot 项目不引用 Unity API；资源保持 Point Filter。
- Never：不声称在没有 Godot Editor/Android SDK 时已经导出 APK；不删除原 Unity 工程。
- Migration note：本版本优先保证完整可玩纵切片，Unity 中复杂视觉效果会用 Godot 2D 等价实现。
