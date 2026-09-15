using NUnit.Framework;
using CivilizationSandbox.Population;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Tests
{
    public sealed class EconomySimulatorTests
    {
        [Test]
        public void GatherersAndFarmersProduceFood()
        {
            var world = new WorldState(5);
            var economy = new EconomySimulator();
            var agents = new[]
            {
                new PopulationAgent(1, "Ayla"),
                new PopulationAgent(2, "Borin")
            };
            agents[1].TrySetJob(Job.Farmer, Era.Agrarian);
            world.Progression.SetEraForTests(Era.Agrarian);
            var before = world.Resources.Get(ResourceType.Food);

            economy.Tick(world, agents, 1);

            Assert.That(world.Resources.Get(ResourceType.Food), Is.GreaterThan(before));
        }

        [Test]
        public void ScientistsProduceScienceOnlyFromModernEra()
        {
            var world = new WorldState(5);
            world.Progression.SetEraForTests(Era.Modern);
            var scientist = new PopulationAgent(1, "Cato");
            scientist.TrySetJob(Job.Scientist, Era.Modern);
            var economy = new EconomySimulator();

            economy.Tick(world, new[] { scientist }, 10);

            Assert.That(world.Resources.Get(ResourceType.Science), Is.EqualTo(10));
        }
    }
}
