using System.Collections.Generic;
using CivilizationSandbox.Settlement;
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
        [SerializeField] private Tilemap overlayTilemap;
        [SerializeField] private TileBase shorelineTile;
        [SerializeField] private TileBase riverbankTile;
        [SerializeField] private TileBase grassDirtEdgeTile;
        [SerializeField] private TileBase cobblestoneRoadTile;
        [SerializeField] private TileBase stoneBridgeTile;
        [SerializeField] private TileBase urbanPlazaTile;
        [SerializeField] private Tilemap buildingTilemap;
        [SerializeField] private TileBase campfireTile;
        [SerializeField] private TileBase hutTile;
        [SerializeField] private TileBase farmTile;
        [SerializeField] private TileBase workshopTile;
        [SerializeField] private TileBase factoryTile;
        [SerializeField] private TileBase researchLabTile;
        [SerializeField] private TileBase launchSiteTile;

        public void Configure(Tilemap terrain, Tilemap overlays, Tilemap buildings)
        {
            targetTilemap = terrain;
            overlayTilemap = overlays;
            buildingTilemap = buildings;
        }

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

        public void RenderOverlays(IEnumerable<WorldOverlayPlacement> placements)
        {
            if (overlayTilemap == null || placements == null) return;
            overlayTilemap.ClearAllTiles();
            foreach (var placement in placements)
                overlayTilemap.SetTile(placement.Position, ResolveOverlayTile(placement.Type));
        }

        private TileBase ResolveOverlayTile(WorldOverlayType type)
        {
            return type switch
            {
                WorldOverlayType.Shoreline => shorelineTile,
                WorldOverlayType.Riverbank => riverbankTile,
                WorldOverlayType.GrassDirtEdge => grassDirtEdgeTile,
                WorldOverlayType.CobblestoneRoad => cobblestoneRoadTile,
                WorldOverlayType.StoneBridge => stoneBridgeTile,
                WorldOverlayType.UrbanPlaza => urbanPlazaTile,
                _ => null
            };
        }

        public void RenderBuildings(IEnumerable<BuildingPlacement> placements)
        {
            if (buildingTilemap == null || placements == null) return;
            buildingTilemap.ClearAllTiles();
            foreach (var placement in placements)
                buildingTilemap.SetTile(placement.Position, ResolveBuildingTile(placement.Type));
        }

        private TileBase ResolveBuildingTile(BuildingType type)
        {
            return type switch
            {
                BuildingType.Campfire => campfireTile,
                BuildingType.Hut => hutTile,
                BuildingType.Farm => farmTile,
                BuildingType.Workshop => workshopTile,
                BuildingType.Factory => factoryTile,
                BuildingType.ResearchLab => researchLabTile,
                BuildingType.LaunchSite => launchSiteTile,
                _ => null
            };
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
