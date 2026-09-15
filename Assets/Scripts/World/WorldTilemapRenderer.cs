using System.Collections.Generic;
using UnityEngine;
using UnityEngine.Tilemaps;

namespace CivilizationSandbox.World
{
    public sealed class WorldTilemapRenderer : MonoBehaviour
    {
        [SerializeField] private Tilemap targetTilemap;
        [SerializeField] private TileBase oceanTile;
        [SerializeField] private TileBase grassTile;
        [SerializeField] private TileBase dirtTile;
        [SerializeField] private TileBase forestTile;
        [SerializeField] private TileBase mountainTile;
        [SerializeField] private TileBase riverTile;

        public void Render(GeneratedWorld world)
        {
            if (targetTilemap == null || world == null) return;
            targetTilemap.ClearAllTiles();
            for (var y = 0; y < world.Height; y++)
            {
                for (var x = 0; x < world.Width; x++)
                {
                    var position = new Vector3Int(x - world.Width / 2, y - world.Height / 2, 0);
                    targetTilemap.SetTile(position, ResolveTile(world.Get(x, y).Terrain));
                }
            }
        }

        private TileBase ResolveTile(TerrainType terrain)
        {
            return terrain switch
            {
                TerrainType.Ocean => oceanTile,
                TerrainType.Grass => grassTile,
                TerrainType.Dirt => dirtTile,
                TerrainType.Forest => forestTile,
                TerrainType.Mountain => mountainTile,
                TerrainType.River => riverTile,
                _ => grassTile
            };
        }
    }
}
