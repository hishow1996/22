using System.IO;
using CivilizationSandbox.Persistence;
using CivilizationSandbox.Simulation;
using NUnit.Framework;

namespace CivilizationSandbox.Tests
{
    public sealed class SaveFileServiceTests
    {
        [Test]
        public void SaveAndLoadRoundTripPreservesWorld()
        {
            var path = Path.Combine(Path.GetTempPath(), "civilization-sandbox-test-save.json");
            try
            {
                var world = new WorldState(99);
                world.Resources.Set(ResourceType.Food, 123);
                world.Progression.SetEraForTests(Era.Modern);
                SaveFileService.Save(world, path);

                Assert.That(SaveFileService.TryLoad(out var data, path), Is.True);
                Assert.That(data.seed, Is.EqualTo(99));
                Assert.That(data.food, Is.EqualTo(123));
                Assert.That(data.era, Is.EqualTo((int)Era.Modern));
            }
            finally
            {
                if (File.Exists(path)) File.Delete(path);
            }
        }

        [Test]
        public void MissingFileReturnsFalse()
        {
            var path = Path.Combine(Path.GetTempPath(), "civilization-sandbox-missing-save.json");
            if (File.Exists(path)) File.Delete(path);

            Assert.That(SaveFileService.TryLoad(out _, path), Is.False);
        }
    }
}
