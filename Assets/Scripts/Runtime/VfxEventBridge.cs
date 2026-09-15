using System;

namespace CivilizationSandbox.Runtime
{
    public enum VfxEventType
    {
        ResourceGathered,
        RainStarted,
        MeteorWarning,
        MeteorImpact,
        RocketLaunch,
        EraAdvanced,
        TechnologyResearched,
        SpaceStationBuilt,
        DeepSpaceProbeLaunched,
        CrewedExplorationLaunched,
        CelestialBodyDiscovered
    }

    public readonly struct VfxEvent
    {
        public VfxEventType Type { get; }
        public int Intensity { get; }

        public VfxEvent(VfxEventType type, int intensity = 1)
        {
            Type = type;
            Intensity = Math.Max(1, intensity);
        }
    }

    public sealed class VfxEventBridge
    {
        public event Action<VfxEvent> Raised;

        public void Raise(VfxEventType type, int intensity = 1)
        {
            Raised?.Invoke(new VfxEvent(type, intensity));
        }
    }
}
