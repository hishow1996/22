using CivilizationSandbox.GodControls;
using CivilizationSandbox.Population;
using CivilizationSandbox.Simulation;
using CivilizationSandbox.World;
using UnityEngine;

namespace CivilizationSandbox.Runtime
{
    public sealed class CivilizationGameController : MonoBehaviour
    {
        [SerializeField] private int seed = 20260915;
        [SerializeField] private int mapWidth = 64;
        [SerializeField] private int mapHeight = 96;
        [SerializeField] private int daysPerSecond = 1;
        [SerializeField] private WorldTilemapRenderer mapRenderer;

        public WorldState World { get; private set; }
        public GeneratedWorld Map { get; private set; }
        public GodControlState GodControls { get; } = new GodControlState();
        public PopulationAgent[] Agents { get; private set; }

        private readonly WorldGenerator worldGenerator = new WorldGenerator();
        private readonly EconomySimulator economySimulator = new EconomySimulator();
        private float dayAccumulator;

        private void Awake()
        {
            World = new WorldState(seed);
            Map = worldGenerator.Generate(mapWidth, mapHeight, seed);
            Agents = CreateStartingAgents(World.Population.Count);
            if (mapRenderer != null) mapRenderer.Render(Map);
        }

        private void Update()
        {
            if (World == null || GodControls.IsPaused) return;
            dayAccumulator += Time.deltaTime * GodControls.TimeScale * daysPerSecond;
            var elapsedDays = Mathf.FloorToInt(dayAccumulator);
            if (elapsedDays <= 0) return;
            dayAccumulator -= elapsedDays;
            World.PopulationSimulator.Tick(World, elapsedDays);
            economySimulator.Tick(World, Agents, elapsedDays);
            World.Progression.TryAdvance(World);
        }

        private static PopulationAgent[] CreateStartingAgents(int count)
        {
            var agents = new PopulationAgent[Mathf.Max(1, count)];
            for (var i = 0; i < agents.Length; i++)
                agents[i] = new PopulationAgent(i + 1, $"Settler {i + 1}");
            return agents;
        }
    }
}
