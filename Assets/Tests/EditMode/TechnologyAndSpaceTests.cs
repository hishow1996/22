using NUnit.Framework;
using CivilizationSandbox.Settlement;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Tests
{
    public sealed class TechnologyAndSpaceTests
    {
        [Test]
        public void LaunchSiteCanBeBuiltOnlyInSpaceEra()
        {
            var world = new WorldState(seed: 3);
            world.Resources.Add(ResourceType.Metal, 100);
            var settlement = new SettlementState();
            var launchSite = new BuildingDefinition(BuildingType.LaunchSite, Era.Space, ResourceType.Metal, 50);

            Assert.That(settlement.TryBuild(launchSite, world), Is.False);
            world.Progression.SetEraForTests(Era.Space);
            Assert.That(settlement.TryBuild(launchSite, world), Is.True);
            Assert.That(world.SpaceProgram.HasLaunchSite, Is.True);
        }

        [Test]
        public void LaunchConsumesFuelAndMetalAndMarksMissionComplete()
        {
            var world = new WorldState(seed: 3);
            world.Progression.SetEraForTests(Era.Space);
            world.SpaceProgram.HasLaunchSite = true;
            world.Resources.Add(ResourceType.Fuel, 50);
            world.Resources.Add(ResourceType.Metal, 50);

            Assert.That(world.SpaceProgram.TryLaunch(world), Is.True);
            Assert.That(world.SpaceProgram.HasLaunched, Is.True);
            Assert.That(world.Resources.Get(ResourceType.Fuel), Is.Zero);
            Assert.That(world.Resources.Get(ResourceType.Metal), Is.Zero);
        }
    }
}
