# 文明沙盒 Unity 实施计划

> **For agentic workers:** 按任务逐项执行，每项完成后运行对应验证并提交一个原子变更。

**Goal:** 构建一款可在 Android 竖屏运行、从原始时代发展到太空时代的 2D 像素文明沙盒核心版本。

**Architecture:** 使用纯 C# 模拟核心保存 WorldState、Population、Settlement、Technology 和 SpaceProgram 数据；Unity MonoBehaviour 仅负责场景适配、渲染、输入和时间驱动。远程区域走统计模拟，视野内区域走实体模拟。

**Tech Stack:** Unity 2022.3 LTS+、C#、2D Tilemap、Pixel Perfect Camera、ScriptableObject、JSON、Unity Test Framework、Android Build Support。

**Spec:** `docs/SPEC.md`

## Global Constraints

- 首发平台为 Android 手机，主方向为竖屏。
- 第一版采用 2D Tilemap 与正交摄像机，不做 3D。
- 美术方向为明亮精致的卡通像素风，最终资源不使用灰色方块占位。
- 第一版为单机离线游戏。

---

### Task 1: 创建可导入工程骨架

**Files:** `ProjectSettings/ProjectVersion.txt`, `Packages/manifest.json`, `.gitignore`, `README.md`

- [ ] 写入 Unity 版本、包配置、Android/Unity 忽略项和导入说明。
- [ ] 验证：目录符合 Unity 工程结构，所有 JSON 可解析。

### Task 2: 建立领域模型与时代配置

**Files:** `Assets/Scripts/Simulation/`, `Assets/ScriptableObjects/Eras/`

- [ ] 先写 EditMode 测试：资源增减、时代条件、科技解锁。
- [ ] 实现 `WorldState`、`ResourceLedger`、`EraProgression` 和 `TechnologyDefinition`。
- [ ] 验证：Unity Test Framework 全部通过。

### Task 3: 实现地图与人口垂直切片

**Files:** `Assets/Scripts/World/`, `Assets/Scripts/Population/`, `Assets/Scenes/Main.unity`

- [ ] 实现固定种子地图生成、部落生成、基础采集和人口增长。
- [ ] 验证：新世界 30 秒内出现资源变化与人口状态变化。

### Task 4: 实现时代、建筑和城市扩张

**Files:** `Assets/Scripts/Settlement/`, `Assets/Scripts/Technology/`, `Assets/Prefabs/`

- [ ] 加入五时代配置及代表性建筑、职业和科技。
- [ ] 验证：科技条件满足后时代推进，地图出现相应建筑风格。

### Task 5: 实现上帝操作与竖屏 UI

**Files:** `Assets/Scripts/UI/`, `Assets/Scripts/GodControls/`, `Assets/Scenes/Main.unity`

- [ ] 加入暂停、调速、缩放、拖动、资源注入、天气和灾害按钮。
- [ ] 验证：触控与编辑器鼠标操作均可使用，竖屏布局不遮挡地图。

### Task 6: 实现火箭与外太空事件

**Files:** `Assets/Scripts/Space/`, `Assets/ScriptableObjects/Space/`

- [ ] 实现发射场、资源消耗、倒计时、发射结果和探索记录。
- [ ] 验证：只有太空时代满足条件后才可发射，成功状态可存档。

### Task 7: 接入正式像素美术资源

**Files:** `Assets/Art/`, `Assets/Animations/`, `docs/ART_BIBLE.md`

- [ ] 制作并导入统一调色板、地形、单位、建筑、特效和 UI 资源。
- [ ] 验证：Point Filter、像素比例、Sprite Atlas 和动画帧一致。

### Task 8: 存档、性能和 Android 构建

**Files:** `Assets/Scripts/Persistence/`, `ProjectSettings/`, `Build/`

- [ ] 实现版本化 JSON 存档、对象池、分帧模拟和 Android 设置。
- [ ] 验证：Unity 无错误导入，APK 安装启动，存档往返一致。
