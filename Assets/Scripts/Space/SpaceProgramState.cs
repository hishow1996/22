using System;

namespace CivilizationSandbox.Simulation
{
    public enum SpaceMission { None, Satellite, SpaceStation, DeepSpaceProbe, CrewedExploration }

    public sealed partial class SpaceProgramState
    {
        public SpaceMission Mission { get; private set; } = SpaceMission.None;
        public bool HasDeepSpaceData { get; private set; }

        public bool TryBuildSpaceStation(WorldState world)
        {
            if (world == null || Mission < SpaceMission.Satellite) return false;
            if (!world.Resources.TrySpend(ResourceType.Metal, 50)) return false;
            if (!world.Resources.TrySpend(ResourceType.Fuel, 50))
            {
                world.Resources.Add(ResourceType.Metal, 50);
                return false;
            }
            Mission = SpaceMission.SpaceStation;
            return true;
        }

        public bool TryLaunchDeepSpaceProbe(WorldState world)
        {
            if (world == null || Mission < SpaceMission.SpaceStation) return false;
            if (!world.Resources.TrySpend(ResourceType.Fuel, 75)) return false;
            if (!world.Resources.TrySpend(ResourceType.Science, 100))
            {
                world.Resources.Add(ResourceType.Fuel, 75);
                return false;
            }
            Mission = SpaceMission.DeepSpaceProbe;
            HasDeepSpaceData = true;
            return true;
        }
    }
}
