using System.Collections.Generic;
using CivilizationSandbox.World;

namespace CivilizationSandbox.Population
{
    public readonly struct AgentGridPosition
    {
        public int X { get; }
        public int Y { get; }

        public AgentGridPosition(int x, int y)
        {
            X = x;
            Y = y;
        }
    }

    public sealed class PopulationMovementSystem
    {
        private readonly Dictionary<int, AgentGridPosition> positions = new Dictionary<int, AgentGridPosition>();
        public IReadOnlyDictionary<int, AgentGridPosition> Positions => positions;

        public void Seed(PopulationAgent[] agents, int width, int height)
        {
            positions.Clear();
            if (agents == null) return;
            for (var i = 0; i < agents.Length; i++)
            {
                var x = 1 + (i * 3) % (width - 2);
                var y = 1 + (i * 5) % (height - 2);
                positions[agents[i].Id] = new AgentGridPosition(x, y);
            }
        }

        public void Tick(GeneratedWorld world, PopulationAgent[] agents, int elapsedDays)
        {
            if (world == null || agents == null || elapsedDays <= 0) return;
            foreach (var agent in agents)
            {
                if (agent == null || !agent.IsAlive || !positions.TryGetValue(agent.Id, out var current)) continue;
                var direction = (agent.Id + elapsedDays) % 4;
                var next = direction switch
                {
                    0 => new AgentGridPosition(current.X + 1, current.Y),
                    1 => new AgentGridPosition(current.X, current.Y + 1),
                    2 => new AgentGridPosition(current.X - 1, current.Y),
                    _ => new AgentGridPosition(current.X, current.Y - 1)
                };
                if (IsWalkable(world, next)) positions[agent.Id] = next;
            }
        }

        private static bool IsWalkable(GeneratedWorld world, AgentGridPosition position)
        {
            if (position.X <= 0 || position.Y <= 0 || position.X >= world.Width - 1 || position.Y >= world.Height - 1) return false;
            var terrain = world.Get(position.X, position.Y).Terrain;
            return terrain != TerrainType.Ocean && terrain != TerrainType.Mountain;
        }
    }
}
