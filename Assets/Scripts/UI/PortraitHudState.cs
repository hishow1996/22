using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.UI
{
    public sealed class PortraitHudState
    {
        public string EraLabel { get; private set; }
        public string SpaceMissionLabel { get; private set; }
        public int Population { get; private set; }
        public int Food { get; private set; }
        public int Wood { get; private set; }
        public int Stone { get; private set; }
        public int Metal { get; private set; }
        public int Electricity { get; private set; }
        public int Science { get; private set; }
        public bool CanLaunch { get; private set; }
        public bool HasDeepSpaceData { get; private set; }
        public int DiscoveredBodies { get; private set; }
        public int UnlockedTechnologyCount { get; private set; }
        public int AvailableTechnologyCount { get; private set; }

        public static PortraitHudState FromWorld(WorldState world)
        {
            var state = new PortraitHudState
            {
                EraLabel = GetEraLabel(world.Progression.CurrentEra),
                SpaceMissionLabel = GetMissionLabel(world.SpaceProgram.Mission),
                Population = world.Population.Count,
                Food = world.Resources.Get(ResourceType.Food),
                Wood = world.Resources.Get(ResourceType.Wood),
                Stone = world.Resources.Get(ResourceType.Stone),
                Metal = world.Resources.Get(ResourceType.Metal),
                Electricity = world.Resources.Get(ResourceType.Electricity),
                Science = world.Resources.Get(ResourceType.Science),
                CanLaunch = world.SpaceProgram.CanLaunch(world),
                HasDeepSpaceData = world.SpaceProgram.HasDeepSpaceData,
                DiscoveredBodies = world.SpaceProgram.DiscoveredBodies,
                UnlockedTechnologyCount = world.Technologies.Unlocked.Count,
                AvailableTechnologyCount = world.Technologies.CountAvailable(world.Progression.CurrentEra)
            };
            if (world.Progression.CurrentEra != Era.Space) state.SpaceMissionLabel = "尚未进入太空时代";
            return state;
        }

        private static string GetEraLabel(Era era)
        {
            switch (era)
            {
                case Era.Primordial: return "原始时代";
                case Era.Agrarian: return "农业时代";
                case Era.Industrial: return "工业时代";
                case Era.Modern: return "现代时代";
                case Era.Space: return "太空时代";
                default: return "未知时代";
            }
        }

        private static string GetMissionLabel(SpaceMission mission)
        {
            switch (mission)
            {
                case SpaceMission.Satellite: return "卫星已部署";
                case SpaceMission.SpaceStation: return "空间站运行中";
                case SpaceMission.DeepSpaceProbe: return "深空探测中";
                case SpaceMission.CrewedExploration: return "载人探索中";
                default: return "等待发射";
            }
        }
    }
}
