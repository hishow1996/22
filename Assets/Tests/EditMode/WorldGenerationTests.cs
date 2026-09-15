using NUnit.Framework;
using CivilizationSandbox.World;

namespace CivilizationSandbox.Tests
{
    public sealed class WorldGenerationTests
    {
        [Test]
        public void SameSeedProducesSameTerrain()
        {
            var generator = new WorldGenerator();
            var first = generator.Generate(12, 12, 99);
            var second = generator.Generate(12, 12, 99);

            Assert.That(first.Get(5, 5).Terrain, Is.EqualTo(second.Get(5, 5).Terrain));
            Assert.That(first.Get(7, 3).ResourceAmount, Is.EqualTo(second.Get(7, 3).ResourceAmount));
        }

        [Test]
        public void GeneratedWorldHasOceanBoundary()
        {
            var world = new WorldGenerator().Generate(12, 12, 99);
            Assert.That(world.Get(0, 4).Terrain, Is.EqualTo(TerrainType.Ocean));
            Assert.That(world.Get(11, 4).Terrain, Is.EqualTo(TerrainType.Ocean));
        }
    }
}
