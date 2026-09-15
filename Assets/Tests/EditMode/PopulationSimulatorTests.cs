using NUnit.Framework;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Tests
{
    public sealed class PopulationSimulatorTests
    {
        [Test]
        public void PopulationGrowsAfterEnoughFoodOverOneSeason()
        {
            var world = new WorldState(seed: 11);
            world.Population.Count = 10;
            world.Resources.Add(ResourceType.Food, 100);

            world.PopulationSimulator.Tick(world, 30);

            Assert.That(world.Population.Count, Is.GreaterThan(10));
            Assert.That(world.Resources.Get(ResourceType.Food), Is.LessThan(100));
        }
    }
}
