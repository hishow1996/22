# 运行时存档

`SaveFileService` 使用 Unity `Application.persistentDataPath` 保存 `civilization-sandbox-save.json`。存档内容包括种子、人口、时代、资源、火箭发射状态、太空任务和星图发现进度。旧版本字段缺失时使用默认值，当前版本号保持为 1。

`CivilizationGameController` 启动后支持自动保存，默认间隔为 30 秒；进入后台或暂停应用时也会保存。`SaveGame` 和 `LoadGame` 可直接绑定到竖屏 HUD 的保存/加载按钮。

加载时会恢复 `WorldState`，重新生成同一种子地图，重新规划道路、过渡层和时代建筑，并重建人口移动坐标与单位对象。因此视觉层不会残留加载前的建筑或人口。

## Unity 绑定

将 `CivilizationGameController.autoSaveIntervalSeconds` 设置为需要的秒数；设为 0 可关闭自动保存。HUD 的保存按钮绑定 `PortraitHudController.SaveGame`，加载按钮绑定 `PortraitHudController.LoadGame`。正式发布前应在 Android 真机上验证 `Application.persistentDataPath` 的读写权限和进程被系统终止后的恢复行为。
