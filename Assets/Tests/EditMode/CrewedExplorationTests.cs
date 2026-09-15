using NUnit.Framework;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Tests
{
    public sealed class CrewedExplorationTests
    {
        [Test]
        public void CrewedMissionDiscoversAPlanet()
        {
            var world = CreateStationWorld();
            world.Resources.Add(ResourceType.Fuel, 150);
            world.Resources.Add(ResourceType.Science, 200);

            Assert.That(world.SpaceProgram.TryLaunchCrewedExploration(world), Is.True);
            Assert.That(world.SpaceProgram.Mission, Is.EqualTo(SpaceMission.CrewedExploration));
            Assert.That(world.SpaceProgram.DiscoveredBodies, Is.GreaterThan(0));
        }

        [Test]
        public void SpaceProgressSurvivesSaveRestore()
        {
            var world = CreateStationWorld();
            world.SpaceProgram.TryLaunchDeepSpaceProbe(world);
            var restored = Persistence.SaveRestore.Restore(Persistence.SaveSystem.Capture(world));

            Assert.That(restored.SpaceProgram.Mission, Is.EqualTo(SpaceMission.DeepSpaceProbe));
            Assert.That(restored.SpaceProgram.HasDeepSpaceData, Is.True);
        }

        private static WorldState CreateStationWorld()
        {
            var world = new WorldState(12);
            world.Progression.SetEraForTests(Era.Space);
            world.SpaceProgram.HasLaunchSite = true;
            world.Resources.Add(ResourceType.Metal, 100);
            world.Resources.Add(ResourceType.Fuel, 200);
            world.Resources.Add(ResourceType.Science, 100);
            world.SpaceProgram.TryLaunch(world);
            world.Resources.Add(ResourceType.Metal, 100);
            world.Resources.Add(ResourceType.Fuel, 100);
            world.SpaceProgram.TryBuildSpaceStation(world);
            return world;
        }
    }
}
