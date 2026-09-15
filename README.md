# 文明沙盒：星际纪元

Unity 2D 像素风 Android 竖屏文明沙盒。

## 当前状态

当前仓库同时包含 Unity 原始工程和 Godot 4 迁移工程。Godot 版本入口为 `GodotProject/project.godot`，复用了处理版像素资源，并用 GDScript 重建地图、模拟、HUD、存档、性能设置和 Android 导出配置。

## 导入

使用 Unity Hub 安装 Unity 2022.3 LTS，并勾选 Android Build Support、Android SDK & NDK Tools 和 OpenJDK。然后在 Unity Hub 中选择本目录打开。

## 目标

文明从原始时代逐步发展到农业、工业、现代和太空时代；玩家可以使用上帝能力干预世界，并在满足条件后发射火箭进入外太空。

## 重要说明

Unity 编辑器与 Android SDK 不在当前执行环境中，因此本阶段不能声称已经完成 APK 编译。获得 Unity 构建环境后，需要运行 Unity Test Runner、打开 `Assets/Scenes/Main.unity`，再执行 Android Build 验证。

Godot 迁移工程同样需要 Godot 4、Android Export Templates、Android SDK/NDK 和 OpenJDK 才能生成 APK。详细步骤见 `docs/GODOT_BUILD.md`。两个工程并存，便于回滚和比较；Godot 工程不依赖 Unity API。
