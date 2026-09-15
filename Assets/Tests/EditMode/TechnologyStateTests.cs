using CivilizationSandbox.Simulation;
using CivilizationSandbox.Technology;
using NUnit.Framework;

namespace CivilizationSandbox.Tests
{
    public sealed class TechnologyStateTests
    {
        [Test]
        public void EraUnlockMakesFireAvailableByDefault()
        {
            var world = new WorldState(1);

            Assert.That(world.Technologies.IsUnlocked("fire"), Is.True);
            Assert.That(world.Technologies.CountAvailable(Era.Primordial), Is.EqualTo(1));
        }

        [Test]
        public void ResearchRequiresPrerequisiteAndConsumesScience()
        {
            var world = new WorldState(1);
            world.Resources.Set(ResourceType.Science, 10);

            Assert.That(world.Technologies.TryResearch("toolmaking", world), Is.True);
            Assert.That(world.Technologies.IsUnlocked("toolmaking"), Is.True);
            Assert.That(world.Resources.Get(ResourceType.Science), Is.EqualTo(0));
        }

        [Test]
        public void FutureEraTechnologyCannotBeResearched()
        {
            var world = new WorldState(1);
            world.Resources.Set(ResourceType.Science, 999);

            Assert.That(world.Technologies.TryResearch("spaceflight", world), Is.False);
        }
    }
}
