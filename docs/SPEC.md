# Spec: 文明沙盒：星际纪元

## Objective

制作一款 Unity 2D 像素风 Android 竖屏文明沙盒游戏。玩家以“上帝”视角观察文明从原始部落发展至现代科技，并在达到太空科技后建造火箭、发射卫星或飞船进入外太空。游戏重点是可观察的社会演化、时代视觉变化和自由干预，而不是直接操作单个角色。

## Assumptions

1. 首发平台为 Android 手机，主方向为竖屏。
2. 第一版采用 2D Tilemap 与正交摄像机，不做 3D。
3. 使用 Unity 2022.3 LTS 或更新的 LTS 版本，代码使用 C#。
4. 美术方向为明亮精致的卡通像素风，最终资源不使用灰色方块占位。
5. 第一版为单机离线游戏，不包含账号、联网、支付或多人模式。

## Tech Stack

- Unity 2022.3 LTS+
- C#
- 2D Tilemap、Pixel Perfect Camera、Sprite Atlas
- ScriptableObject 配置时代、科技、建筑和单位
- JSON 存档，版本化 SaveData
- Unity Test Framework
- Android APK（测试构建）与 AAB（发布准备）

## Core Gameplay

- 程序生成由海洋、草地、泥地、森林、山地、河流组成的地图。
- 单位具有身份、需求、职业、阵营、位置和生命周期。
- 单位采集、生产、建造、繁衍、迁徙，并受食物和住房影响。
- 资源包括食物、木材、石材、金属、电力、燃料和科研点。
- 五个时代：原始、农业、工业、现代、太空。
- 科技解锁建筑、职业、资源链、交通和太空项目。
- 玩家可暂停、调速、拖动、缩放、添加资源、改变天气、制造灾害和查看文明信息。
- 火箭项目必须经过科技、资源、发射场和倒计时条件，成功后产生卫星、空间站或外太空探索记录。

## Project Structure

```text
Assets/
  Art/              正式像素素材、调色板、Sprite Atlas
  Audio/            音效与音乐
  Prefabs/          单位、建筑、特效预制体
  Scenes/           主场景与测试场景
  Scripts/          运行时代码
  ScriptableObjects/时代、科技、建筑、单位配置
  Tests/            Unity EditMode/PlayMode 测试
Packages/           Unity 包配置
ProjectSettings/    Unity 工程设置
Tests/              额外测试说明与测试数据
docs/               规格、架构、美术圣经、变更记录
tasks/              实施计划与任务清单
```

## Code Style

运行时代码按领域拆分，纯模拟优先使用无 Unity 依赖的 C# 类型，便于 EditMode 测试。类名使用 PascalCase，字段使用 camelCase，公开配置使用只读属性或序列化字段。

```csharp
public sealed class PopulationSimulator
{
    public void Tick(WorldState world, int elapsedMinutes)
    {
        if (elapsedMinutes <= 0) return;
        // 先更新需求，再更新工作与人口变化。
    }
}
```

## Testing Strategy

- EditMode：测试时代推进、资源消耗、人口增长、火箭解锁条件、存档序列化。
- PlayMode：测试单位生成、地图加载、UI 调速、火箭发射流程。
- 性能验证：目标设备上 500 个详细单位与 20 个远程文明统计实体时维持可玩帧率。
- 构建验证：Unity 无错误导入，Android APK 可安装并启动主场景。

## Boundaries

- Always：先写规格和测试；所有正式美术资源遵循美术规范；保存数据必须可迁移；每个可玩系统必须有可验证入口。
- Ask first：加入第三方付费资产、联网服务、内购、账号体系、改变核心美术方向、正式发布签名。
- Never：提交密钥；使用未授权素材；用占位方块冒充最终美术；在未验证编译的情况下声称 APK 已完成。

## Success Criteria

1. 新建世界后可看到像素地图、资源和至少一个部落。
2. 文明可从原始时代推进至农业、工业、现代和太空时代。
3. 每个时代至少有 3 类可见建筑、2 类单位或职业、3 项科技解锁。
4. 玩家可以暂停、调速、缩放、移动镜头并使用至少 4 种上帝能力。
5. 达到太空科技后，玩家可以建造发射场并完成一次火箭发射事件。
6. 世界可以保存、退出后加载，并保持时代、人口、资源和火箭状态。
7. Android 构建可启动、触控可用、竖屏锁定。
8. 正式美术资源风格统一、非简陋占位，且在手机尺寸下清晰可辨。

## Open Questions

- 游戏名称是否最终使用“文明沙盒：星际纪元”。
- 地图采用固定世界、随机世界，还是二者兼有。
- 是否需要多种文明外观和不同科技路线。
- 太空阶段是地图内展示轨道设施，还是切换到独立星图场景。
