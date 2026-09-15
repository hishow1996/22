using NUnit.Framework;
using CivilizationSandbox.Population;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Tests
{
    public sealed class EconomyVfxTests
    {
        [Test]
        public void GathererProductionRaisesResourceCallback()
        {
            var world = new WorldState(4);
            var agent = new PopulationAgent(1, "Gatherer");
            var agents = new[] { agent };
            ResourceType produced = ResourceType.Food;
            var amount = 0;

            new EconomySimulator().Tick(world, agents, 2, 1f, (resource, value) =>
            {
                produced = resource;
                amount = value;
            });

            Assert.That(produced, Is.EqualTo(ResourceType.Food));
            Assert.That(amount, Is.EqualTo(4));
        }
    }
}
