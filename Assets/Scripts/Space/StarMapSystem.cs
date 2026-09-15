using System;
using System.Collections.Generic;

namespace CivilizationSandbox.Simulation
{
    public enum CelestialBodyType { Moon, Planet, Asteroid, Nebula }

    public sealed class CelestialBody
    {
        public string Name { get; }
        public CelestialBodyType Type { get; }
        public bool Discovered { get; internal set; }

        public CelestialBody(string name, CelestialBodyType type)
        {
            Name = name;
            Type = type;
        }
    }

    public sealed partial class SpaceProgramState
    {
        public int DiscoveredBodies { get; private set; }
        public List<CelestialBody> StarMap { get; } = new List<CelestialBody>
        {
            new CelestialBody("Luna", CelestialBodyType.Moon),
            new CelestialBody("Asterion", CelestialBodyType.Planet),
            new CelestialBody("Vela Belt", CelestialBodyType.Asteroid),
            new CelestialBody("Aurora Nebula", CelestialBodyType.Nebula)
        };

        public bool TryLaunchCrewedExploration(WorldState world)
        {
            if (world == null || Mission < SpaceMission.SpaceStation) return false;
            if (!world.Resources.TrySpend(ResourceType.Fuel, 100)) return false;
            if (!world.Resources.TrySpend(ResourceType.Science, 150))
            {
                world.Resources.Add(ResourceType.Fuel, 100);
                return false;
            }

            Mission = SpaceMission.CrewedExploration;
            foreach (var body in StarMap)
            {
                if (body.Discovered) continue;
                body.Discovered = true;
                DiscoveredBodies++;
                break;
            }
            return true;
        }

        internal void RestoreProgress(SpaceMission mission, bool hasLaunched, bool hasDeepSpaceData, int discoveredBodies)
        {
            Mission = mission;
            HasLaunched = hasLaunched;
            HasDeepSpaceData = hasDeepSpaceData;
            DiscoveredBodies = Math.Max(0, Math.Min(StarMap.Count, discoveredBodies));
            for (var i = 0; i < DiscoveredBodies; i++) StarMap[i].Discovered = true;
        }
    }
}
