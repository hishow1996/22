using System.Collections.Generic;
using CivilizationSandbox.Simulation;
using UnityEngine;

namespace CivilizationSandbox.World
{
    public sealed class WorldOverlayPlanner
    {
        public List<WorldOverlayPlacement> Plan(GeneratedWorld world, Era era)
        {
            var result = new List<WorldOverlayPlacement>();
            if (world == null) return result;

            for (var y = 1; y < world.Height - 1; y++)
            {
                for (var x = 1; x < world.Width - 1; x++)
                {
                    var terrain = world.Get(x, y).Terrain;
                    var north = world.Get(x, y + 1).Terrain;
                    var south = world.Get(x, y - 1).Terrain;
                    var east = world.Get(x + 1, y).Terrain;
                    var west = world.Get(x - 1, y).Terrain;
                    var position = new Vector3Int(x - world.Width / 2, y - world.Height / 2, 0);

                    if (terrain != TerrainType.Ocean && terrain != TerrainType.River && HasTerrainNeighbor(TerrainType.Ocean, north, south, east, west))
                        result.Add(new WorldOverlayPlacement(position, WorldOverlayType.Shoreline));
                    else if (terrain != TerrainType.Ocean && terrain != TerrainType.River && HasTerrainNeighbor(TerrainType.River, north, south, east, west))
                        result.Add(new WorldOverlayPlacement(position, WorldOverlayType.Riverbank));
                    else if (terrain == TerrainType.Grass && HasTerrainNeighbor(TerrainType.Dirt, north, south, east, west))
                        result.Add(new WorldOverlayPlacement(position, WorldOverlayType.GrassDirtEdge));
                }
            }

            var roadX = world.Width / 2;
            for (var y = 1; y < world.Height - 1; y++)
            {
                var terrain = world.Get(roadX, y).Terrain;
                var position = new Vector3Int(roadX - world.Width / 2, y - world.Height / 2, 0);
                if (terrain == TerrainType.River)
                    result.Add(new WorldOverlayPlacement(position, WorldOverlayType.StoneBridge));
                else if (terrain != TerrainType.Ocean && terrain != TerrainType.Mountain)
                    result.Add(new WorldOverlayPlacement(position, WorldOverlayType.CobblestoneRoad));
            }

            if (era >= Era.Modern)
            {
                var center = new Vector3Int(0, 0, 0);
                result.Add(new WorldOverlayPlacement(center, WorldOverlayType.UrbanPlaza));
            }
            return result;
        }

        private static bool HasTerrainNeighbor(TerrainType target, params TerrainType[] neighbors)
        {
            foreach (var neighbor in neighbors)
                if (neighbor == target) return true;
            return false;
        }
    }
}
