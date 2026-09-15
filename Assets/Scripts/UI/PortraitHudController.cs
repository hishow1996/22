using CivilizationSandbox.GodControls;
using CivilizationSandbox.Runtime;
using CivilizationSandbox.Simulation;
using UnityEngine;

namespace CivilizationSandbox.UI
{
    public sealed class PortraitHudController : MonoBehaviour
    {
        [SerializeField] private CivilizationGameController game;
        [SerializeField] private Canvas portraitCanvas;

        public void TogglePause() => game.GodControls.TogglePause();
        public void SetSpeed(float speed) => game.GodControls.SetTimeScale(speed);
        public void GrantFood() => game.GodControls.GrantResource(game.World, ResourceType.Food, 25);
        public void GrantScience() => game.GodControls.GrantResource(game.World, ResourceType.Science, 25);
        public void SetRain() => game.GodControls.SetWeather(WeatherType.Rain);
        public void TriggerMeteor() => game.GodControls.TriggerDisaster();
        public bool TryLaunchRocket() => game.World.SpaceProgram.TryLaunch(game.World);
        public bool TryBuildSpaceStation() => game.World.SpaceProgram.TryBuildSpaceStation(game.World);
        public bool TryLaunchDeepSpaceProbe() => game.World.SpaceProgram.TryLaunchDeepSpaceProbe(game.World);
        public bool TryLaunchCrewedExploration() => game.World.SpaceProgram.TryLaunchCrewedExploration(game.World);

        private void Awake()
        {
            if (portraitCanvas != null)
                portraitCanvas.renderMode = RenderMode.ScreenSpaceOverlay;
            Screen.orientation = ScreenOrientation.Portrait;
            Screen.autorotateToLandscapeLeft = false;
            Screen.autorotateToLandscapeRight = false;
        }
    }
}
