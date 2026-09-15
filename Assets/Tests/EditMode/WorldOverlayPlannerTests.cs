using NUnit.Framework;
using CivilizationSandbox.Simulation;
using CivilizationSandbox.World;
using System.Linq;
using UnityEngine;

namespace CivilizationSandbox.Tests
{
    public sealed class WorldOverlayPlannerTests
    {
        [Test]
        public void PlannerMarksOceanBoundaryAsShoreline()
        {
            var world = new GeneratedWorld(5, 5);
            Fill(world, TerrainType.Grass);
            for (var y = 0; y < 5; y++) world.Set(0, y, new WorldCell(TerrainType.Ocean, 0));

            var overlays = new WorldOverlayPlanner().Plan(world, Era.Primordial);

            Assert.That(overlays.Any(x => x.Type == WorldOverlayType.Shoreline), Is.True);
        }

        [Test]
        public void PlannerCreatesBridgeWhereRoadCrossesRiver()
        {
            var world = new GeneratedWorld(9, 9);
            Fill(world, TerrainType.Grass);
            for (var y = 0; y < 9; y++) world.Set(4, y, new WorldCell(TerrainType.River, 0));

            var overlays = new WorldOverlayPlanner().Plan(world, Era.Industrial);

            Assert.That(overlays.Any(x => x.Type == WorldOverlayType.StoneBridge), Is.True);
            Assert.That(overlays.Any(x => x.Type == WorldOverlayType.CobblestoneRoad), Is.True);
        }

        private static void Fill(GeneratedWorld world, TerrainType terrain)
        {
            for (var y = 0; y < world.Height; y++)
                for (var x = 0; x < world.Width; x++)
                    world.Set(x, y, new WorldCell(terrain, 0));
        }
    }
}
