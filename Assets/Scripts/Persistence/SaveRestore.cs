using System;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Persistence
{
    public static class SaveRestore
    {
        public static WorldState Restore(SaveData data)
        {
            if (data == null) throw new ArgumentNullException(nameof(data));
            var world = new WorldState(data.seed);
            world.Population.Count = Math.Max(0, data.population);
            world.Progression.SetEraForTests((Era)Math.Max(0, Math.Min((int)Era.Space, data.era)));
            SetResource(world, ResourceType.Food, data.food);
            SetResource(world, ResourceType.Wood, data.wood);
            SetResource(world, ResourceType.Stone, data.stone);
            SetResource(world, ResourceType.Metal, data.metal);
            SetResource(world, ResourceType.Electricity, data.electricity);
            SetResource(world, ResourceType.Fuel, data.fuel);
            SetResource(world, ResourceType.Science, data.science);
            world.SpaceProgram.HasLaunchSite = data.hasLaunchSite;
            world.SpaceProgram.RestoreProgress(
                (SpaceMission)Math.Max(0, Math.Min((int)SpaceMission.CrewedExploration, data.spaceMission)),
                data.hasLaunched,
                data.hasDeepSpaceData,
                data.discoveredBodies);
            world.Technologies.Restore(data.unlockedTechnologyIds);
            world.Diplomacy.Restore(data.tradeCount, data.allianceCount, data.warCount, data.lastDiplomacyAction);
            world.EventLog.Restore(data.eventLog);
            return world;
        }

        private static void SetResource(WorldState world, ResourceType type, int amount)
        {
            world.Resources.Set(type, Math.Max(0, amount));
        }
    }
}
