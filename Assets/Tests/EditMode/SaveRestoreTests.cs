using NUnit.Framework;
using CivilizationSandbox.Persistence;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Tests
{
    public sealed class SaveRestoreTests
    {
        [Test]
        public void RestoreRecreatesCoreWorldState()
        {
            var world = new WorldState(9);
            world.Population.Count = 42;
            world.Progression.SetEraForTests(Era.Modern);
            world.Resources.Add(ResourceType.Science, 220);
            world.SpaceProgram.HasLaunchSite = true;

            var restored = SaveRestore.Restore(SaveSystem.Capture(world));

            Assert.That(restored.Seed, Is.EqualTo(9));
            Assert.That(restored.Population.Count, Is.EqualTo(42));
            Assert.That(restored.Progression.CurrentEra, Is.EqualTo(Era.Modern));
            Assert.That(restored.Resources.Get(ResourceType.Science), Is.EqualTo(220));
            Assert.That(restored.SpaceProgram.HasLaunchSite, Is.True);
        }
    }
}
