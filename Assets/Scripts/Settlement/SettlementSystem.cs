using System;
using System.Collections.Generic;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Settlement
{
    public enum BuildingType { Campfire, Hut, Farm, Workshop, Factory, ResearchLab, LaunchSite }

    public sealed class BuildingDefinition
    {
        public BuildingType Type { get; }
        public Era RequiredEra { get; }
        public ResourceType CostType { get; }
        public int Cost { get; }

        public BuildingDefinition(BuildingType type, Era requiredEra, ResourceType costType, int cost)
        {
            Type = type; RequiredEra = requiredEra; CostType = costType; Cost = cost;
        }
    }

    public sealed class SettlementState
    {
        private readonly List<BuildingType> buildings = new List<BuildingType>();
        public IReadOnlyList<BuildingType> Buildings => buildings;

        public bool TryBuild(BuildingDefinition definition, WorldState world)
        {
            if (definition == null || world == null) throw new ArgumentNullException();
            if (world.Progression.CurrentEra < definition.RequiredEra) return false;
            if (!world.Resources.TrySpend(definition.CostType, definition.Cost)) return false;
            buildings.Add(definition.Type);
            if (definition.Type == BuildingType.LaunchSite) world.SpaceProgram.HasLaunchSite = true;
            return true;
        }
    }
}
