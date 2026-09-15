using CivilizationSandbox.GodControls;
using CivilizationSandbox.Nations;
using CivilizationSandbox.Environment;
using CivilizationSandbox.Population;
using CivilizationSandbox.Persistence;
using CivilizationSandbox.Settlement;
using CivilizationSandbox.Simulation;
using CivilizationSandbox.Technology;
using System;
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
        [SerializeField] private PopulationUnitLayer populationUnitLayer;
        [SerializeField] private float autoSaveIntervalSeconds = 30f;

        public WorldState World { get; private set; }
        public GeneratedWorld Map { get; private set; }
        public GodControlState GodControls { get; } = new GodControlState();
        public EnvironmentSimulator Environment { get; } = new EnvironmentSimulator();
        public VfxEventBridge VfxEvents { get; } = new VfxEventBridge();
        public PopulationAgent[] Agents { get; private set; }
        public PopulationMovementSystem Movement { get; } = new PopulationMovementSystem();
        public event Action<Era> EraAdvanced;
        public event Action<string> TechnologyResearched;

        private readonly WorldGenerator worldGenerator = new WorldGenerator();
        private readonly WorldOverlayPlanner overlayPlanner = new WorldOverlayPlanner();
        private readonly SettlementLayoutPlanner settlementPlanner = new SettlementLayoutPlanner();
        private readonly EconomySimulator economySimulator = new EconomySimulator();
        private readonly DiplomacySystem diplomacySystem = new DiplomacySystem();
        private readonly AutonomousDiplomacySystem autonomousDiplomacy = new AutonomousDiplomacySystem();
        private float dayAccumulator;
        private float autoSaveTimer;

        public void StartRain()
        {
            GodControls.SetWeather(WeatherType.Rain);
            VfxEvents.Raise(VfxEventType.RainStarted);
        }

        public void TriggerMeteor()
        {
            GodControls.TriggerDisaster();
            if (World != null) Environment.ApplyDisaster(World, DisasterType.Meteor, 1);
            VfxEvents.Raise(VfxEventType.MeteorWarning);
            VfxEvents.Raise(VfxEventType.MeteorImpact);
        }

        public bool TryLaunchRocket()
        {
            if (World == null || !World.SpaceProgram.TryLaunch(World)) return false;
            VfxEvents.Raise(VfxEventType.RocketLaunch);
            return true;
        }

        public bool TryResearchTechnology(string id)
        {
            if (World == null || !World.Technologies.TryResearch(id, World)) return false;
            VfxEvents.Raise(VfxEventType.TechnologyResearched);
            TechnologyResearched?.Invoke(id);
            SaveGame();
            return true;
        }

        public bool TryFormAlliance(int firstIndex, int secondIndex)
        {
            if (!TryGetNations(firstIndex, secondIndex, out var first, out var second)) return false;
            if (!diplomacySystem.FormAlliance(first, second)) return false;
            World.Diplomacy.RecordAlliance();
            VfxEvents.Raise(VfxEventType.DiplomacyAction);
            SaveGame();
            return true;
        }

        public bool TryTrade(int buyerIndex, int sellerIndex, int amount)
        {
            if (!TryGetNations(buyerIndex, sellerIndex, out var buyer, out var seller)) return false;
            if (!diplomacySystem.ExecuteTrade(buyer, seller, amount)) return false;
            World.Diplomacy.RecordTrade();
            VfxEvents.Raise(VfxEventType.DiplomacyAction);
            SaveGame();
            return true;
        }

        public bool TryResolveWar(int attackerIndex, int defenderIndex)
        {
            if (!TryGetNations(attackerIndex, defenderIndex, out var attacker, out var defender)) return false;
            var result = diplomacySystem.ResolveWar(attacker, defender);
            World.Diplomacy.RecordWar(result.Winner.Name);
            VfxEvents.Raise(VfxEventType.DiplomacyAction, result.Damage);
            SaveGame();
            return true;
        }

        public bool TryBuildSpaceStation()
        {
            if (World == null || !World.SpaceProgram.TryBuildSpaceStation(World)) return false;
            VfxEvents.Raise(VfxEventType.SpaceStationBuilt);
            SaveGame();
            return true;
        }

        public bool TryLaunchDeepSpaceProbe()
        {
            if (World == null || !World.SpaceProgram.TryLaunchDeepSpaceProbe(World)) return false;
            VfxEvents.Raise(VfxEventType.DeepSpaceProbeLaunched);
            SaveGame();
            return true;
        }

        public bool TryLaunchCrewedExploration()
        {
            if (World == null) return false;
            var previousDiscoveries = World.SpaceProgram.DiscoveredBodies;
            if (!World.SpaceProgram.TryLaunchCrewedExploration(World)) return false;
            VfxEvents.Raise(VfxEventType.CrewedExplorationLaunched);
            if (World.SpaceProgram.DiscoveredBodies > previousDiscoveries)
                VfxEvents.Raise(VfxEventType.CelestialBodyDiscovered,
                    World.SpaceProgram.DiscoveredBodies - previousDiscoveries);
            SaveGame();
            return true;
        }

        private void Awake()
        {
            World = new WorldState(seed);
            World.Nations.Add(new NationState("Aurora", Era.Primordial));
            World.Nations.Add(new NationState("Sol", Era.Primordial));
            Map = worldGenerator.Generate(mapWidth, mapHeight, seed);
            Agents = CreateStartingAgents(World.Population.Count);
            Movement.Seed(Agents, mapWidth, mapHeight);
            if (populationUnitLayer != null) populationUnitLayer.Initialize(this);
            RebuildPresentation();
        }

        private void Update()
        {
            if (World == null || GodControls.IsPaused) return;
            autoSaveTimer += Time.deltaTime;
            if (autoSaveIntervalSeconds > 0f && autoSaveTimer >= autoSaveIntervalSeconds)
            {
                SaveGame();
                autoSaveTimer = 0f;
            }
            dayAccumulator += Time.deltaTime * GodControls.TimeScale * daysPerSecond;
            var elapsedDays = Mathf.FloorToInt(dayAccumulator);
            if (elapsedDays <= 0) return;
            dayAccumulator -= elapsedDays;
            World.PopulationSimulator.Tick(World, elapsedDays);
            Movement.Tick(Map, Agents, elapsedDays);
            Environment.SetWeather(GodControls.Weather);
            economySimulator.Tick(World, Agents, elapsedDays, Environment.FoodProductionMultiplier,
                (resource, amount) => VfxEvents.Raise(VfxEventType.ResourceGathered, amount));
            var autonomousResult = autonomousDiplomacy.Tick(World, elapsedDays);
            if (autonomousResult.Action != AutonomousDiplomacyAction.None)
            {
                VfxEvents.Raise(VfxEventType.DiplomacyAction);
                SaveGame();
            }
            if (World.Progression.TryAdvance(World))
            {
                World.Technologies.UnlockEra(World.Progression.CurrentEra);
                RebuildPresentation();
                VfxEvents.Raise(VfxEventType.EraAdvanced, (int)World.Progression.CurrentEra + 1);
                EraAdvanced?.Invoke(World.Progression.CurrentEra);
            }
        }

        public void SaveGame()
        {
            if (World == null) return;
            SaveFileService.Save(World);
        }

        public bool LoadGame()
        {
            if (!SaveFileService.TryLoad(out var data)) return false;
            World = SaveRestore.Restore(data);
            EnsureDefaultNations();
            Map = worldGenerator.Generate(mapWidth, mapHeight, World.Seed);
            Agents = CreateStartingAgents(World.Population.Count);
            Movement.Seed(Agents, mapWidth, mapHeight);
            if (populationUnitLayer != null)
            {
                populationUnitLayer.ResetUnits();
                populationUnitLayer.Initialize(this);
            }
            RebuildPresentation();
            autoSaveTimer = 0f;
            return true;
        }

        private void RebuildPresentation()
        {
            if (mapRenderer == null || Map == null) return;
            mapRenderer.Render(Map);
            mapRenderer.RenderOverlays(overlayPlanner.Plan(Map, World.Progression.CurrentEra));
            mapRenderer.RenderBuildings(settlementPlanner.Plan(Map, World.Progression.CurrentEra));
        }

        private bool TryGetNations(int firstIndex, int secondIndex, out NationState first, out NationState second)
        {
            first = null;
            second = null;
            if (World == null || firstIndex < 0 || secondIndex < 0 || firstIndex >= World.Nations.Count || secondIndex >= World.Nations.Count)
                return false;
            first = World.Nations[firstIndex];
            second = World.Nations[secondIndex];
            return true;
        }

        private void EnsureDefaultNations()
        {
            if (World.Nations.Count > 0) return;
            World.Nations.Add(new NationState("Aurora", World.Progression.CurrentEra));
            World.Nations.Add(new NationState("Sol", World.Progression.CurrentEra));
        }

        private void OnApplicationPause(bool paused)
        {
            if (paused) SaveGame();
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
