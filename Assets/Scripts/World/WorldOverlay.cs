using UnityEngine;

namespace CivilizationSandbox.World
{
    public enum WorldOverlayType { Shoreline, Riverbank, GrassDirtEdge, CobblestoneRoad, StoneBridge, UrbanPlaza }

    public readonly struct WorldOverlayPlacement
    {
        public Vector3Int Position { get; }
        public WorldOverlayType Type { get; }

        public WorldOverlayPlacement(Vector3Int position, WorldOverlayType type)
        {
            Position = position;
            Type = type;
        }
    }
}
