namespace CivilizationSandbox.Nations
{
    public sealed class DiplomacyState
    {
        public int TradeCount { get; private set; }
        public int AllianceCount { get; private set; }
        public int WarCount { get; private set; }
        public string LastAction { get; private set; } = "暂无外交行动";

        public void RecordTrade() { TradeCount++; LastAction = "贸易协定"; }
        public void RecordAlliance() { AllianceCount++; LastAction = "结成联盟"; }
        public void RecordWar(string winner) { WarCount++; LastAction = "战争结束：" + winner; }
        public void Restore(int trades, int alliances, int wars, string lastAction)
        {
            TradeCount = trades < 0 ? 0 : trades;
            AllianceCount = alliances < 0 ? 0 : alliances;
            WarCount = wars < 0 ? 0 : wars;
            LastAction = string.IsNullOrEmpty(lastAction) ? "暂无外交行动" : lastAction;
        }
    }
}
