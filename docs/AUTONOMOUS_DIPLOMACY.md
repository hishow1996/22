# 国家自主外交 AI

`AutonomousDiplomacySystem` 每累计 30 个模拟日为国家执行一次确定性外交决策。决策不使用随机数，而是根据种子、外交历史计数和当前国家状态保持可复现，便于测试与存档恢复。

决策优先级为：在尚未结盟时尝试结盟；满足国库条件时尝试贸易；否则进行战争结算。每次成功行动都会更新 `DiplomacyState`，控制器发出 `DiplomacyAction` 反馈并保存存档。

国家 AI 不会绕过领域系统，所有贸易、联盟和战争仍通过 `DiplomacySystem` 执行。后续可以把人口、时代、军力、资源短缺和国家性格加入评分模型，但不改变当前的确定性时间接口。

## 运行时表现

国家 AI 不直接操作 UI。HUD 通过 `PortraitHudState` 读取外交统计和最后行动文本，地图或提示特效通过 `VfxEventPresenter.onDiplomacyAction` 接收事件。
