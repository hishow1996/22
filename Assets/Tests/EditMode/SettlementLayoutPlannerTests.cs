using System.Linq;
using CivilizationSandbox.Settlement;
using CivilizationSandbox.Simulation;
using CivilizationSandbox.World;
using NUnit.Framework;

namespace CivilizationSandbox.Tests
{
    public sealed class SettlementLayoutPlannerTests
    {
        [Test]
        public void SpaceEraPlansLaunchSiteAndResearchLab()
        {
            var world = new GeneratedWorld(20, 20);
            Fill(world, TerrainType.Grass);

            var placements = new SettlementLayoutPlanner().Plan(world, Era.Space);

            Assert.That(placements.Any(x => x.Type == BuildingType.LaunchSite), Is.True);
            Assert.That(placements.Any(x => x.Type == BuildingType.ResearchLab), Is.True);
        }

        [Test]
        public void BuildingsNeverAppearOnOceanOrRiver()
        {
            var world = new GeneratedWorld(20, 20);
            Fill(world, TerrainType.Ocean);

            var placements = new SettlementLayoutPlanner().Plan(world, Era.Modern);

            Assert.That(placements, Is.Empty);
        }

        private static void Fill(GeneratedWorld world, TerrainType terrain)
        {
            for (var y = 0; y < world.Height; y++)
                for (var x = 0; x < world.Width; x++)
                    world.Set(x, y, new WorldCell(terrain, 0));
        }
    }
}
