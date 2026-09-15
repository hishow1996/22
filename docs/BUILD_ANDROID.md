# Android 构建说明

## 前置条件

安装 Unity 2022.3 LTS，并勾选 Android Build Support、Android SDK & NDK Tools 和 OpenJDK。打开项目后，在 Package Manager 中确认 2D Tilemap、2D Pixel Perfect、Input System 和 Test Framework 已导入。

## Unity Editor 构建

1. 打开 `Assets/Scenes/Main.unity`。
2. 将正式像素 Tile 资源绑定到 `WorldTilemapRenderer`。
3. 在 Test Runner 中运行 EditMode 和 PlayMode 测试。
4. 选择菜单 `Civilization Sandbox > Build Android APK`。
5. APK 输出到 `Builds/CivilizationSandbox.apk`。

## 命令行构建

在项目根目录执行：

```bash
Unity -batchmode -quit -nographics \
  -projectPath /absolute/path/to/civilization-sandbox \
  -executeMethod CivilizationSandbox.EditorTools.AndroidBuild.BuildFromCommandLine \
  -logFile Builds/android-build.log
```

## 构建验收

- Unity 构建日志无 error。
- APK 文件存在且大小大于 1 MB。
- 使用 `adb install -r Builds/CivilizationSandbox.apk` 安装成功。
- 启动后锁定竖屏，地图可拖动和缩放。
- 新世界可生成，时间可暂停/加速，资源和人口会变化。
- 至少推进至太空时代并验证火箭发射入口。
- 保存、退出、重新进入后，人口、资源、时代和发射场状态保持一致。

## 当前环境限制

本执行环境目前没有 Unity Editor、Android SDK/NDK 或 OpenJDK，所以构建脚本已经准备好，但不能在这里实际运行 Unity 编译。不能把未通过 Unity BuildPipeline 的文件称为 APK。
