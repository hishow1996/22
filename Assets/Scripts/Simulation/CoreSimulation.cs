using System;
using System.Collections.Generic;
using CivilizationSandbox.Nations;
using CivilizationSandbox.Technology;

namespace CivilizationSandbox.Simulation
{
    public enum ResourceType { Food, Wood, Stone, Metal, Electricity, Fuel, Science }
    public enum Era { Primordial, Agrarian, Industrial, Modern, Space }

    public sealed class ResourceLedger
    {
        private readonly Dictionary<ResourceType, int> values = new Dictionary<ResourceType, int>();

        public void Add(ResourceType type, int amount)
        {
            if (amount < 0) throw new ArgumentOutOfRangeException(nameof(amount));
            values[type] = Get(type) + amount;
        }

        public int Get(ResourceType type) => values.TryGetValue(type, out var value) ? value : 0;

        public void Set(ResourceType type, int amount)
        {
            if (amount < 0) throw new ArgumentOutOfRangeException(nameof(amount));
            values[type] = amount;
        }

        public bool TrySpend(ResourceType type, int amount)
        {
            if (amount < 0) throw new ArgumentOutOfRangeException(nameof(amount));
            if (Get(type) < amount) return false;
            values[type] = Get(type) - amount;
            return true;
        }
    }

    public sealed class PopulationState
    {
        public int Count { get; set; }
        public int SeasonProgress { get; private set; }

        public void Tick(WorldState world, int elapsedDays)
        {
            if (elapsedDays <= 0 || Count <= 0) return;
            var foodNeed = Math.Max(1, Count / 5) * elapsedDays;
            if (!world.Resources.TrySpend(ResourceType.Food, foodNeed))
            {
                Count = Math.Max(0, Count - Math.Max(1, elapsedDays / 10));
                return;
            }

            SeasonProgress += elapsedDays;
            if (SeasonProgress >= 30)
            {
                Count += Math.Max(1, Count / 10);
                SeasonProgress %= 30;
            }
        }
    }

    public sealed class EraProgression
    {
        public Era CurrentEra { get; private set; } = Era.Primordial;

        public bool TryAdvance(WorldState world)
        {
            switch (CurrentEra)
            {
                case Era.Primordial when world.Resources.Get(ResourceType.Food) >= 50 && world.Resources.Get(ResourceType.Wood) >= 50:
                    CurrentEra = Era.Agrarian;
                    return true;
                case Era.Agrarian when world.Resources.Get(ResourceType.Stone) >= 100 && world.Resources.Get(ResourceType.Food) >= 100:
                    CurrentEra = Era.Industrial;
                    return true;
                case Era.Industrial when world.Resources.Get(ResourceType.Metal) >= 200 && world.Resources.Get(ResourceType.Electricity) >= 100:
                    CurrentEra = Era.Modern;
                    return true;
                case Era.Modern when world.Resources.Get(ResourceType.Science) >= 500 && world.Resources.Get(ResourceType.Fuel) >= 200:
                    CurrentEra = Era.Space;
                    return true;
                default:
                    return false;
            }
        }

        public void SetEraForTests(Era era) => CurrentEra = era;
    }

    public sealed partial class SpaceProgramState
    {
        public bool HasLaunchSite { get; set; }
        public bool HasLaunched { get; private set; }

        public bool CanLaunch(WorldState world)
        {
            return world.Progression.CurrentEra == Era.Space && HasLaunchSite
                && world.Resources.Get(ResourceType.Fuel) >= 50
                && world.Resources.Get(ResourceType.Metal) >= 50;
        }

        public bool TryLaunch(WorldState world)
        {
            if (!CanLaunch(world)) return false;
            world.Resources.TrySpend(ResourceType.Fuel, 50);
            world.Resources.TrySpend(ResourceType.Metal, 50);
            HasLaunched = true;
            Mission = SpaceMission.Satellite;
            return true;
        }
    }

    public sealed class WorldState
    {
        public int Seed { get; }
        public ResourceLedger Resources { get; } = new ResourceLedger();
        public PopulationState Population { get; } = new PopulationState();
        public EraProgression Progression { get; } = new EraProgression();
        public SpaceProgramState SpaceProgram { get; } = new SpaceProgramState();
        public TechnologyState Technologies { get; } = new TechnologyState();
        public PopulationSimulator PopulationSimulator { get; } = new PopulationSimulator();
        public List<NationState> Nations { get; } = new List<NationState>();

        public WorldState(int seed)
        {
            Seed = seed;
            Population.Count = 8;
            Resources.Add(ResourceType.Food, 40);
            Resources.Add(ResourceType.Wood, 40);
            Technologies.UnlockEra(Era.Primordial);
        }
    }

    public sealed class PopulationSimulator
    {
        public void Tick(WorldState world, int elapsedDays)
        {
            if (world == null) throw new ArgumentNullException(nameof(world));
            world.Population.Tick(world, elapsedDays);
        }
    }
}
