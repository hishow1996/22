# 文明沙盒能力地图

| 模块 | 责任 | 依赖 |
|---|---|---|
| world-generation | 生成像素地图、地形、水域与资源 | — |
| population-simulation | 人口、需求、职业、移动与生命周期 | world-generation |
| settlement-economy | 建筑、资源、生产、城市扩张 | world-generation, population-simulation |
| era-technology | 时代推进、科技树、解锁条件 | settlement-economy |
| nations-conflict | 国家、贸易、外交与战争 | settlement-economy, era-technology |
| god-controls | 上帝能力、天气、灾害、时间控制 | world-generation, population-simulation |
| space-program | 卫星、火箭、发射、空间探索 | era-technology, settlement-economy |
| presentation | 像素美术、动画、竖屏 UI、音效 | all gameplay modules |
| persistence | 世界存档、加载、版本迁移 | all gameplay modules |

## 构建顺序

world-generation → population-simulation → settlement-economy → era-technology → nations-conflict → god-controls → space-program → presentation → persistence → Android build.

## 一次性交付版本边界

第一版覆盖原始、农业、工业、现代、太空五个时代；每个时代提供可玩的代表性单位、建筑、资源和科技。远离玩家视野的文明使用统计模拟，玩家附近区域使用详细模拟，以适配 Android 性能。大型内容库、多人联网和复杂 3D 不属于第一版。
