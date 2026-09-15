using System;
using System.Collections.Generic;

namespace CivilizationSandbox.World
{
    public enum TerrainType { Ocean, Grass, Dirt, Forest, Mountain, River }

    public readonly struct WorldCell
    {
        public TerrainType Terrain { get; }
        public int ResourceAmount { get; }

        public WorldCell(TerrainType terrain, int resourceAmount)
        {
            Terrain = terrain;
            ResourceAmount = resourceAmount;
        }
    }

    public sealed class GeneratedWorld
    {
        private readonly WorldCell[,] cells;
        public int Width { get; }
        public int Height { get; }

        public GeneratedWorld(int width, int height)
        {
            if (width < 4 || height < 4) throw new ArgumentOutOfRangeException();
            Width = width;
            Height = height;
            cells = new WorldCell[width, height];
        }

        public void Set(int x, int y, WorldCell cell) => cells[x, y] = cell;
        public WorldCell Get(int x, int y) => cells[x, y];
    }

    public sealed class WorldGenerator
    {
        public GeneratedWorld Generate(int width, int height, int seed)
        {
            var world = new GeneratedWorld(width, height);
            var random = new Random(seed);
            for (var y = 0; y < height; y++)
            {
                for (var x = 0; x < width; x++)
                {
                    var edge = x == 0 || y == 0 || x == width - 1 || y == height - 1;
                    var roll = random.Next(100);
                    var terrain = edge ? TerrainType.Ocean : roll < 8 ? TerrainType.Mountain
                        : roll < 22 ? TerrainType.Forest : roll < 28 ? TerrainType.Dirt : TerrainType.Grass;
                    var resource = terrain == TerrainType.Forest ? random.Next(2, 8)
                        : terrain == TerrainType.Mountain ? random.Next(3, 12) : 0;
                    world.Set(x, y, new WorldCell(terrain, resource));
                }
            }
            return world;
        }
    }
}
