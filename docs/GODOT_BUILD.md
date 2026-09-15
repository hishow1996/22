# Godot 4 构建说明

本项目目标平台是 **Android 手机竖屏**，不是横屏项目。设计视口为 `768×1365`，导出预设锁定 portrait，HUD 使用 Android 安全区和大触控按钮适配刘海屏、导航栏及不同手机尺寸。

适配策略包括：Godot `canvas_items` 保持比例缩放、禁止窗口自由变形、HUD 使用六列网格而不是固定横向按钮串、按钮最小触控区域为 `116×58`、根据真实窗口和 `DisplayServer.get_display_safe_area()` 计算顶部/底部安全内边距，并为资源图标、日志和状态栏设置固定设计坐标。这样在常见 16:9、19.5:9、20:9、刘海屏和打孔屏上会等比缩放并保留安全边界，而不会把 UI 拉宽。

Android 生命周期也已处理：切入后台会暂停模拟并自动保存，恢复时保留当前状态；系统返回键会依次关闭设置、文明信息或星图面板，面板关闭后再次返回会暂停游戏，再次返回才退出并保存。

图形设置启动时会从 `user://civilization_settings.json` 恢复，FPS 和垂直同步立即应用，粒子开关会同步控制火箭、灾害和采集动画播放器；设置面板的当前选项会显示已保存值。

启动时会运行资源诊断器，检查地形、篝火、时代建筑等关键 PNG 是否存在，并写入 `user://civilization_diagnostics.log`。游戏内“运行诊断”按钮会把报告显示在信息面板中，便于在 Android 真机上排查黑屏、缺图和导出资源遗漏。

Godot 迁移工程位于 `GodotProject/`，与原 Unity 工程并存。使用 Godot 4.x 打开 `GodotProject/project.godot`。

## 编辑器运行

打开项目后运行 `main.tscn`。主场景会创建 64×96 像素地图、原始聚落、资源 HUD、模拟时间和竖屏控制栏。正式资源位于 `GodotProject/assets/`，导入时保持 nearest/Point 过滤。

## 命令行检查

```bash
godot --headless --path GodotProject --editor --quit
```

## Android 导出

安装 Godot Android Export Templates、Android SDK、Android Build Tools、Android NDK 和 OpenJDK 后执行：

```bash
mkdir -p GodotProject/Builds
godot --headless --path GodotProject --export-debug "Android" Builds/CivilizationSandbox-Godot.apk
```

当前执行环境没有 Godot 编辑器、Android SDK 或 NDK，因此本仓库只提交了完整 Godot 工程和导出配置，不能声称 APK 已经生成。

## 迁移边界

Unity 工程仍保留在仓库原位置，Godot 工程不依赖 Unity API。现有处理版 PNG 已复用；Unity 场景、Tilemap、C# 组件和 ScriptableObject 已用 Godot Node2D、GDScript、JSON 和 CanvasLayer 等价重建。

当前 Godot 迁移版本还包含独立的外交系统（联盟、贸易、战争记录）、科技树系统、职业人口分配、星图任务（卫星、空间站、深空探测、载人探索）和事件桥接。它们通过 HUD 入口和 JSON 存档连接到主模拟。

表现层已接入处理版 PNG：篝火、木屋、农田、工业工厂、现代研究中心、发射场和原始人口单位会在地图上显示；篝火带有轻微脉冲动效。`GodotProject/tests/simulation_smoke.gd` 提供世界生成、外交、科技、太空任务和存档恢复的烟雾测试入口。

太空阶段另有“星图”按钮，显示太阳核心、四个任务节点和连接轨迹；完成卫星、空间站、深空探测或载人探索后，对应节点会变为已发现状态。
