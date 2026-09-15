using NUnit.Framework;
using CivilizationSandbox.Simulation;
using CivilizationSandbox.UI;

namespace CivilizationSandbox.Tests
{
    public sealed class PortraitHudStateTests
    {
        [Test]
        public void SnapshotShowsCoreResourcesAndEra()
        {
            var world = new WorldState(21);
            world.Progression.SetEraForTests(Era.Industrial);
            world.Resources.Add(ResourceType.Metal, 37);

            var snapshot = PortraitHudState.FromWorld(world);

            Assert.That(snapshot.EraLabel, Is.EqualTo("工业时代"));
            Assert.That(snapshot.Metal, Is.EqualTo(37));
            Assert.That(snapshot.Population, Is.EqualTo(world.Population.Count));
        }

        [Test]
        public void SnapshotShowsSpaceMissionWhenAvailable()
        {
            var world = new WorldState(21);
            world.SpaceProgram.HasLaunchSite = true;
            world.Progression.SetEraForTests(Era.Space);

            var snapshot = PortraitHudState.FromWorld(world);

            Assert.That(snapshot.SpaceMissionLabel, Is.EqualTo("等待发射"));
            Assert.That(snapshot.CanLaunch, Is.False);
        }
    }
}
