using CivilizationSandbox.GodControls;
using CivilizationSandbox.Runtime;
using CivilizationSandbox.Simulation;
using UnityEngine;
using System;

namespace CivilizationSandbox.UI
{
    public sealed class PortraitHudController : MonoBehaviour
    {
        [SerializeField] private CivilizationGameController game;
        [SerializeField] private Canvas portraitCanvas;
        public PortraitHudState CurrentState { get; private set; }
        public event Action<PortraitHudState> StateChanged;

        public void TogglePause() => game.GodControls.TogglePause();
        public void SetSpeed(float speed) => game.GodControls.SetTimeScale(speed);
        public void GrantFood() => game.GodControls.GrantResource(game.World, ResourceType.Food, 25);
        public void GrantScience() => game.GodControls.GrantResource(game.World, ResourceType.Science, 25);
        public void SetRain() => game.StartRain();
        public void TriggerMeteor() => game.TriggerMeteor();
        public bool TryLaunchRocket() => game.TryLaunchRocket();
        public bool TryBuildSpaceStation() => game.World.SpaceProgram.TryBuildSpaceStation(game.World);
        public bool TryLaunchDeepSpaceProbe() => game.World.SpaceProgram.TryLaunchDeepSpaceProbe(game.World);
        public bool TryLaunchCrewedExploration() => game.World.SpaceProgram.TryLaunchCrewedExploration(game.World);
        public void SaveGame() => game.SaveGame();
        public bool LoadGame() => game.LoadGame();

        private void Awake()
        {
            if (portraitCanvas != null)
                portraitCanvas.renderMode = RenderMode.ScreenSpaceOverlay;
            Screen.orientation = ScreenOrientation.Portrait;
            Screen.autorotateToLandscapeLeft = false;
            Screen.autorotateToLandscapeRight = false;
            RefreshState();
        }

        private void Update()
        {
            if (game != null && game.World != null) RefreshState();
        }

        public void RefreshState()
        {
            if (game == null || game.World == null) return;
            CurrentState = PortraitHudState.FromWorld(game.World);
            StateChanged?.Invoke(CurrentState);
        }
    }
}
