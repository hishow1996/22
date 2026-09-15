# 外交运行时 UI

世界中默认存在 Aurora 和 Sol 两个国家。竖屏外交面板可以按国家索引发起联盟、贸易和战争，控制器负责校验索引、执行领域结算、更新外交统计、发出反馈事件并保存存档。

| 操作 | UI 方法 | 结果 |
|---|---|---|
| 结成联盟 | `FormAlliance(firstNation, secondNation)` | 双方互为盟友，联盟计数增加 |
| 贸易 | `Trade(buyerNation, sellerNation, amount)` | 买方扣除国库，卖方增加国库，贸易计数增加 |
| 战争 | `ResolveWar(attackerNation, defenderNation)` | 按军力结算胜负、损伤和国库转移，战争计数增加 |

HUD 状态包含国家数量、贸易次数、联盟次数、战争次数和最后一次外交行动。`DiplomacyAction` 可绑定到外交提示、地图旗帜动画或战争特效。外交统计会写入 JSON 存档；加载旧存档时使用零值兼容。
