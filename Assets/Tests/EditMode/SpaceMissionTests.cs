using NUnit.Framework;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Tests
{
    public sealed class SpaceMissionTests
    {
        [Test]
        public void SuccessfulLaunchCreatesSatellite()
        {
            var world = CreateSpaceWorld();
            Assert.That(world.SpaceProgram.TryLaunch(world), Is.True);
            Assert.That(world.SpaceProgram.Mission, Is.EqualTo(SpaceMission.Satellite));
        }

        [Test]
        public void SpaceStationRequiresSatelliteAndAdditionalResources()
        {
            var world = CreateSpaceWorld();
            world.SpaceProgram.TryLaunch(world);
            world.Resources.Add(ResourceType.Metal, 100);
            world.Resources.Add(ResourceType.Fuel, 100);

            Assert.That(world.SpaceProgram.TryBuildSpaceStation(world), Is.True);
            Assert.That(world.SpaceProgram.Mission, Is.EqualTo(SpaceMission.SpaceStation));
        }

        private static WorldState CreateSpaceWorld()
        {
            var world = new WorldState(2);
            world.Progression.SetEraForTests(Era.Space);
            world.SpaceProgram.HasLaunchSite = true;
            world.Resources.Add(ResourceType.Metal, 100);
            world.Resources.Add(ResourceType.Fuel, 100);
            return world;
        }
    }
}
