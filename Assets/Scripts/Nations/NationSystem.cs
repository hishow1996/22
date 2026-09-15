using System;
using System.Collections.Generic;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Nations
{
    public sealed class NationState
    {
        private readonly HashSet<NationState> allies = new HashSet<NationState>();
        public string Name { get; }
        public Era Era { get; private set; }
        public int MilitaryStrength { get; set; }
        public int Treasury { get; set; }

        public NationState(string name, Era era)
        {
            Name = name ?? throw new ArgumentNullException(nameof(name));
            Era = era;
            MilitaryStrength = 10;
            Treasury = 50;
        }

        public void SetEra(Era era) => Era = era;
        internal void AddAlly(NationState nation) => allies.Add(nation);
        public bool IsAlliedWith(NationState nation) => allies.Contains(nation);
    }

    public sealed class WarResult
    {
        public NationState Winner { get; }
        public NationState Loser { get; }
        public int Damage { get; }

        public WarResult(NationState winner, NationState loser, int damage)
        {
            Winner = winner; Loser = loser; Damage = damage;
        }
    }

    public sealed class DiplomacySystem
    {
        public bool FormAlliance(NationState first, NationState second)
        {
            if (first == null || second == null || ReferenceEquals(first, second)) return false;
            first.AddAlly(second);
            second.AddAlly(first);
            return true;
        }

        public WarResult ResolveWar(NationState attacker, NationState defender)
        {
            if (attacker == null || defender == null) throw new ArgumentNullException();
            var attackerWins = attacker.MilitaryStrength >= defender.MilitaryStrength;
            var winner = attackerWins ? attacker : defender;
            var loser = attackerWins ? defender : attacker;
            var damage = Math.Max(1, loser.MilitaryStrength / 2);
            loser.MilitaryStrength = Math.Max(0, loser.MilitaryStrength - damage);
            winner.Treasury += Math.Max(0, loser.Treasury / 4);
            loser.Treasury = Math.Max(0, loser.Treasury - loser.Treasury / 4);
            return new WarResult(winner, loser, damage);
        }
    }
}
