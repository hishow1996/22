using CivilizationSandbox.GodControls;
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

        public WorldState World { get; private set; }
        public GeneratedWorld Map { get; private set; }
        public GodControlState GodControls { get; } = new GodControlState();

        private readonly WorldGenerator worldGenerator = new WorldGenerator();
        private float dayAccumulator;

        private void Awake()
        {
            World = new WorldState(seed);
            Map = worldGenerator.Generate(mapWidth, mapHeight, seed);
        }

        private void Update()
        {
            if (World == null || GodControls.IsPaused) return;
            dayAccumulator += Time.deltaTime * GodControls.TimeScale * daysPerSecond;
            var elapsedDays = Mathf.FloorToInt(dayAccumulator);
            if (elapsedDays <= 0) return;
            dayAccumulator -= elapsedDays;
            World.PopulationSimulator.Tick(World, elapsedDays);
        }
    }
}
