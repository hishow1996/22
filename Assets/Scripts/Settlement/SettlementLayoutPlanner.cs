using System.Collections.Generic;
using CivilizationSandbox.Simulation;
using CivilizationSandbox.World;
using UnityEngine;

namespace CivilizationSandbox.Settlement
{
    public readonly struct BuildingPlacement
    {
        public Vector3Int Position { get; }
        public BuildingType Type { get; }

        public BuildingPlacement(Vector3Int position, BuildingType type)
        {
            Position = position;
            Type = type;
        }
    }

    public sealed class SettlementLayoutPlanner
    {
        public List<BuildingPlacement> Plan(GeneratedWorld world, Era era)
        {
            var result = new List<BuildingPlacement>();
            if (world == null) return result;

            var candidates = new[]
            {
                new Vector2Int(world.Width / 2 - 3, world.Height / 2 - 3),
                new Vector2Int(world.Width / 2 + 2, world.Height / 2 - 3),
                new Vector2Int(world.Width / 2 - 3, world.Height / 2 + 2),
                new Vector2Int(world.Width / 2 + 2, world.Height / 2 + 2),
                new Vector2Int(world.Width / 2, world.Height / 2 + 5)
            };
            var buildings = BuildingsForEra(era);
            for (var i = 0; i < buildings.Length && i < candidates.Length; i++)
            {
                var candidate = candidates[i];
                if (!IsBuildable(world, candidate.x, candidate.y)) continue;
                result.Add(new BuildingPlacement(
                    new Vector3Int(candidate.x - world.Width / 2, candidate.y - world.Height / 2, 0),
                    buildings[i]));
            }
            return result;
        }

        private static BuildingType[] BuildingsForEra(Era era)
        {
            if (era >= Era.Space) return new[] { BuildingType.Hut, BuildingType.Farm, BuildingType.Factory, BuildingType.ResearchLab, BuildingType.LaunchSite };
            if (era >= Era.Modern) return new[] { BuildingType.Hut, BuildingType.Farm, BuildingType.Factory, BuildingType.ResearchLab };
            if (era >= Era.Industrial) return new[] { BuildingType.Hut, BuildingType.Farm, BuildingType.Workshop, BuildingType.Factory };
            if (era >= Era.Agrarian) return new[] { BuildingType.Hut, BuildingType.Farm, BuildingType.Campfire };
            return new[] { BuildingType.Campfire, BuildingType.Hut };
        }

        private static bool IsBuildable(GeneratedWorld world, int x, int y)
        {
            if (x < 0 || y < 0 || x >= world.Width || y >= world.Height) return false;
            var terrain = world.Get(x, y).Terrain;
            return terrain == TerrainType.Grass || terrain == TerrainType.Dirt || terrain == TerrainType.Forest;
        }
    }
}
