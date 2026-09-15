using CivilizationSandbox.UI;
using CivilizationSandbox.World;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.InputSystem.UI;
using UnityEngine.Tilemaps;
using UnityEngine.UI;

namespace CivilizationSandbox.Runtime
{
    /// <summary>
    /// Creates a safe baseline scene when Main.unity has not yet been hand-wired in the Unity Editor.
    /// Asset-specific TileBase and Sprite references remain intentionally editor-configurable.
    /// </summary>
    public sealed class RuntimeSceneBootstrap : MonoBehaviour
    {
        private const string RootName = "CivilizationRuntime";

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        private static void CreateRuntimeBaseline()
        {
            if (FindObjectOfType<CivilizationGameController>() != null) return;

            var root = new GameObject(RootName);
            var worldObject = new GameObject("World");
            worldObject.transform.SetParent(root.transform);
            var grid = worldObject.AddComponent<Grid>();

            var terrain = CreateTilemap("Terrain", grid.transform);
            var overlays = CreateTilemap("Overlays", grid.transform);
            var buildings = CreateTilemap("Buildings", grid.transform);

            var renderer = worldObject.AddComponent<WorldTilemapRenderer>();
            renderer.Configure(terrain, overlays, buildings);

            var units = new GameObject("Population Units");
            units.transform.SetParent(root.transform);
            var unitLayer = units.AddComponent<PopulationUnitLayer>();

            var controllerObject = new GameObject("Game Controller");
            controllerObject.transform.SetParent(root.transform);
            controllerObject.SetActive(false);
            var controller = controllerObject.AddComponent<CivilizationGameController>();
            controller.Configure(renderer, unitLayer);

            var performance = root.AddComponent<MobilePerformanceSettings>();

            var canvasObject = new GameObject("Canvas");
            canvasObject.transform.SetParent(root.transform);
            canvasObject.layer = LayerMask.NameToLayer("UI");
            var canvas = canvasObject.AddComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            canvasObject.AddComponent<CanvasScaler>();
            canvasObject.AddComponent<GraphicRaycaster>();
            var hud = canvasObject.AddComponent<PortraitHudController>();
            hud.Configure(controller, canvas);

            if (FindObjectOfType<EventSystem>() == null)
            {
                var eventSystem = new GameObject("EventSystem");
                eventSystem.transform.SetParent(root.transform);
                eventSystem.AddComponent<EventSystem>();
                eventSystem.AddComponent<InputSystemUIInputModule>();
            }

            root.SetActive(true);
            controllerObject.SetActive(true);
        }

        private static Tilemap CreateTilemap(string name, Transform parent)
        {
            var objectRoot = new GameObject(name);
            objectRoot.transform.SetParent(parent);
            objectRoot.AddComponent<TilemapRenderer>();
            return objectRoot.AddComponent<Tilemap>();
        }
    }
}
