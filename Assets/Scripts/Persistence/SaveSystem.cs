using System;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Persistence
{
    [Serializable]
    public sealed class SaveData
    {
        public int version = 1;
        public int seed;
        public int population;
        public int era;
        public int food;
        public int wood;
        public int stone;
        public int metal;
        public int electricity;
        public int fuel;
        public int science;
        public bool hasLaunchSite;
        public bool hasLaunched;
        public int spaceMission;
        public bool hasDeepSpaceData;
        public int discoveredBodies;
    }

    public static class SaveSystem
    {
        public static SaveData Capture(WorldState world)
        {
            return new SaveData
            {
                seed = world.Seed,
                population = world.Population.Count,
                era = (int)world.Progression.CurrentEra,
                food = world.Resources.Get(ResourceType.Food),
                wood = world.Resources.Get(ResourceType.Wood),
                stone = world.Resources.Get(ResourceType.Stone),
                metal = world.Resources.Get(ResourceType.Metal),
                electricity = world.Resources.Get(ResourceType.Electricity),
                fuel = world.Resources.Get(ResourceType.Fuel),
                science = world.Resources.Get(ResourceType.Science),
                hasLaunchSite = world.SpaceProgram.HasLaunchSite,
                hasLaunched = world.SpaceProgram.HasLaunched,
                spaceMission = (int)world.SpaceProgram.Mission,
                hasDeepSpaceData = world.SpaceProgram.HasDeepSpaceData,
                discoveredBodies = world.SpaceProgram.DiscoveredBodies
            };
        }

        public static string ToJson(WorldState world) => UnityEngine.JsonUtility.ToJson(Capture(world), true);
    }
}
