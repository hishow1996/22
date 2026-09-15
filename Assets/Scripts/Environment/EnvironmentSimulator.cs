using System;
using CivilizationSandbox.GodControls;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Environment
{
    public enum DisasterType { Drought, Flood, Fire, Meteor }

    public sealed class EnvironmentSimulator
    {
        public WeatherType Weather { get; private set; } = WeatherType.Clear;
        public float FoodProductionMultiplier => Weather == WeatherType.Rain ? 1.25f : Weather == WeatherType.Drought ? 0.6f : 1f;
        public int DisasterCount { get; private set; }

        public void SetWeather(WeatherType weather) => Weather = weather;

        public void ApplyDisaster(WorldState world, DisasterType disaster, int severity)
        {
            if (world == null) throw new ArgumentNullException(nameof(world));
            var safeSeverity = Math.Max(1, severity);
            DisasterCount++;
            switch (disaster)
            {
                case DisasterType.Drought:
                    world.Resources.Set(ResourceType.Food, Math.Max(0, world.Resources.Get(ResourceType.Food) - 10 * safeSeverity));
                    break;
                case DisasterType.Flood:
                    world.Resources.Set(ResourceType.Wood, Math.Max(0, world.Resources.Get(ResourceType.Wood) - 8 * safeSeverity));
                    break;
                case DisasterType.Fire:
                    world.Resources.Set(ResourceType.Wood, Math.Max(0, world.Resources.Get(ResourceType.Wood) - 15 * safeSeverity));
                    world.Population.Count = Math.Max(0, world.Population.Count - safeSeverity);
                    break;
                case DisasterType.Meteor:
                    world.Population.Count = Math.Max(0, world.Population.Count - 5 * safeSeverity);
                    world.Resources.Set(ResourceType.Food, Math.Max(0, world.Resources.Get(ResourceType.Food) - 20 * safeSeverity));
                    break;
            }
        }
    }
}
