using NUnit.Framework;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Tests
{
    public sealed class EraProgressionTests
    {
        [Test]
        public void EraAdvancesOnlyWhenRequirementsAreMet()
        {
            var world = new WorldState(seed: 7);
            world.Resources.Add(ResourceType.Food, 100);
            world.Resources.Add(ResourceType.Wood, 100);

            Assert.That(world.Progression.CurrentEra, Is.EqualTo(Era.Primordial));
            Assert.That(world.Progression.TryAdvance(world), Is.True);
            Assert.That(world.Progression.CurrentEra, Is.EqualTo(Era.Agrarian));
        }

        [Test]
        public void SpaceLaunchRequiresSpaceEraAndLaunchSite()
        {
            var world = new WorldState(seed: 7);
            Assert.That(world.SpaceProgram.CanLaunch(world), Is.False);

            world.Progression.SetEraForTests(Era.Space);
            world.SpaceProgram.HasLaunchSite = true;
            world.Resources.Add(ResourceType.Fuel, 100);
            world.Resources.Add(ResourceType.Metal, 100);

            Assert.That(world.SpaceProgram.CanLaunch(world), Is.True);
        }
    }
}
