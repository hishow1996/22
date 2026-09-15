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
        public string[] unlockedTechnologyIds = new string[0];
        public int tradeCount;
        public int allianceCount;
        public int warCount;
        public string lastDiplomacyAction;
        public string[] eventLog = new string[0];
    }

    public static class SaveSystem
    {
        public static SaveData Capture(WorldState world)
        {
            var unlocked = new string[world.Technologies.Unlocked.Count];
            var index = 0;
            foreach (var id in world.Technologies.Unlocked) unlocked[index++] = id;
            var events = new string[world.EventLog.Entries.Count];
            for (var i = 0; i < events.Length; i++) events[i] = world.EventLog.Entries[i];
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
                discoveredBodies = world.SpaceProgram.DiscoveredBodies,
                unlockedTechnologyIds = unlocked,
                tradeCount = world.Diplomacy.TradeCount,
                allianceCount = world.Diplomacy.AllianceCount,
                warCount = world.Diplomacy.WarCount,
                lastDiplomacyAction = world.Diplomacy.LastAction,
                eventLog = events
            };
        }

        public static string ToJson(WorldState world) => UnityEngine.JsonUtility.ToJson(Capture(world), true);
    }
}
