using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Nations
{
    public enum AutonomousDiplomacyAction { None, Trade, Alliance, War }

    public readonly struct AutonomousDiplomacyResult
    {
        public AutonomousDiplomacyAction Action { get; }
        public string Summary { get; }

        public AutonomousDiplomacyResult(AutonomousDiplomacyAction action, string summary)
        {
            Action = action;
            Summary = summary;
        }
    }

    public sealed class AutonomousDiplomacySystem
    {
        private readonly DiplomacySystem diplomacy = new DiplomacySystem();
        private int elapsedSinceDecision;

        public AutonomousDiplomacyResult Tick(WorldState world, int elapsedDays)
        {
            if (world == null || world.Nations.Count < 2 || elapsedDays <= 0)
                return new AutonomousDiplomacyResult(AutonomousDiplomacyAction.None, "");
            elapsedSinceDecision += elapsedDays;
            if (elapsedSinceDecision < 30) return new AutonomousDiplomacyResult(AutonomousDiplomacyAction.None, "");
            elapsedSinceDecision = 0;

            var first = world.Nations[0];
            var second = world.Nations[1];
            var selector = (world.Diplomacy.TradeCount + world.Diplomacy.AllianceCount + world.Diplomacy.WarCount) % 3;
            if (selector == 0 && !first.IsAlliedWith(second) && diplomacy.FormAlliance(first, second))
            {
                world.Diplomacy.RecordAlliance();
                return new AutonomousDiplomacyResult(AutonomousDiplomacyAction.Alliance, "国家自动结成联盟");
            }
            if (selector == 1 && diplomacy.ExecuteTrade(first, second, 10))
            {
                world.Diplomacy.RecordTrade();
                return new AutonomousDiplomacyResult(AutonomousDiplomacyAction.Trade, "国家自动完成贸易");
            }

            var result = diplomacy.ResolveWar(first, second);
            world.Diplomacy.RecordWar(result.Winner.Name);
            return new AutonomousDiplomacyResult(AutonomousDiplomacyAction.War, "国家自动战争结算：" + result.Winner.Name);
        }
    }
}
