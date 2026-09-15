using NUnit.Framework;
using CivilizationSandbox.Environment;
using CivilizationSandbox.GodControls;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Tests
{
    public sealed class EnvironmentEventTests
    {
        [Test]
        public void RainImprovesFoodProductionModifier()
        {
            var environment = new EnvironmentSimulator();
            environment.SetWeather(WeatherType.Rain);
            Assert.That(environment.FoodProductionMultiplier, Is.GreaterThan(1f));
        }

        [Test]
        public void MeteorStrikeReducesPopulation()
        {
            var world = new WorldState(4);
            world.Population.Count = 100;
            var environment = new EnvironmentSimulator();

            environment.ApplyDisaster(world, DisasterType.Meteor, 1);

            Assert.That(world.Population.Count, Is.LessThan(100));
        }
    }
}
