using System;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.GodControls
{
    public enum WeatherType { Clear, Rain, Drought, Storm }

    public sealed class GodControlState
    {
        public bool IsPaused { get; private set; }
        public float TimeScale { get; private set; } = 1f;
        public WeatherType Weather { get; private set; } = WeatherType.Clear;
        public int DisasterCount { get; private set; }

        public void TogglePause() => IsPaused = !IsPaused;
        public void SetTimeScale(float scale) => TimeScale = Math.Max(0.25f, Math.Min(8f, scale));
        public void SetWeather(WeatherType weather) => Weather = weather;
        public void GrantResource(WorldState world, ResourceType type, int amount) => world.Resources.Add(type, amount);
        public void TriggerDisaster() => DisasterCount++;
    }
}
