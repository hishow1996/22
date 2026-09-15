using CivilizationSandbox.Population;
using CivilizationSandbox.World;
using NUnit.Framework;

namespace CivilizationSandbox.Tests
{
    public sealed class PopulationMovementSystemTests
    {
        [Test]
        public void SeedCreatesDeterministicPositions()
        {
            var world = CreateWorld(TerrainType.Grass);
            var agents = new[] { new PopulationAgent(1, "A"), new PopulationAgent(2, "B") };
            var movement = new PopulationMovementSystem();

            movement.Seed(agents, world.Width, world.Height);

            Assert.That(movement.Positions[1].X, Is.EqualTo(1));
            Assert.That(movement.Positions[1].Y, Is.EqualTo(1));
            Assert.That(movement.Positions[2].X, Is.EqualTo(4));
        }

        [Test]
        public void MovementDoesNotEnterMountain()
        {
            var world = CreateWorld(TerrainType.Grass);
            world.Set(2, 1, new WorldCell(TerrainType.Mountain, 0));
            var agents = new[] { new PopulationAgent(1, "A") };
            var movement = new PopulationMovementSystem();
            movement.Seed(agents, world.Width, world.Height);

            movement.Tick(world, agents, 1);

            Assert.That(movement.Positions[1].X, Is.EqualTo(1));
        }

        private static GeneratedWorld CreateWorld(TerrainType terrain)
        {
            var world = new GeneratedWorld(8, 8);
            for (var y = 0; y < world.Height; y++)
                for (var x = 0; x < world.Width; x++)
                    world.Set(x, y, new WorldCell(terrain, 0));
            return world;
        }
    }
}
