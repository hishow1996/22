using System.Collections.Generic;
using CivilizationSandbox.Runtime;
using CivilizationSandbox.Technology;
using UnityEngine;
using UnityEngine.UI;

namespace CivilizationSandbox.UI
{
    public enum TechnologyVisualState { Locked, Available, Unlocked }

    public sealed class TechnologyTreePanel : MonoBehaviour
    {
        [SerializeField] private CivilizationGameController game;
        [SerializeField] private Transform nodeRoot;
        [SerializeField] private Button nodeTemplate;
        [SerializeField] private Text summaryText;
        private readonly List<Button> spawnedNodes = new List<Button>();

        private void OnEnable()
        {
            if (game != null)
            {
                game.EraAdvanced += OnEraAdvanced;
                game.TechnologyResearched += OnTechnologyResearched;
                game.WorldLoaded += OnWorldLoaded;
            }
            Refresh();
        }

        private void OnDisable()
        {
            if (game != null)
            {
                game.EraAdvanced -= OnEraAdvanced;
                game.TechnologyResearched -= OnTechnologyResearched;
                game.WorldLoaded -= OnWorldLoaded;
            }
        }

        public void Refresh()
        {
            if (game == null || game.World == null || nodeRoot == null || nodeTemplate == null) return;
            ClearNodes();
            foreach (var node in EraTechnologyCatalog.All)
            {
                var button = Instantiate(nodeTemplate, nodeRoot);
                button.gameObject.SetActive(true);
                button.name = "Technology_" + node.Id;
                var state = GetVisualState(node.Id);
                var label = button.GetComponentInChildren<Text>();
                if (label != null) label.text = FormatLabel(node, state);
                button.interactable = state == TechnologyVisualState.Available;
                var id = node.Id;
                button.onClick.AddListener(() => game.TryResearchTechnology(id));
                spawnedNodes.Add(button);
            }
            if (summaryText != null)
                summaryText.text = $"已解锁 {game.World.Technologies.Unlocked.Count} / {EraTechnologyCatalog.All.Count}，可研究 {game.World.Technologies.CountAvailable(game.World.Progression.CurrentEra)}";
        }

        public TechnologyVisualState GetVisualState(string id)
        {
            if (game == null || game.World == null) return TechnologyVisualState.Locked;
            if (game.World.Technologies.IsUnlocked(id)) return TechnologyVisualState.Unlocked;
            return game.World.Technologies.CountAvailable(game.World.Progression.CurrentEra) > 0 && IsAvailableNode(id)
                ? TechnologyVisualState.Available
                : TechnologyVisualState.Locked;
        }

        private bool IsAvailableNode(string id)
        {
            foreach (var node in EraTechnologyCatalog.All)
                if (node.Id == id && node.Era <= game.World.Progression.CurrentEra)
                    return HasUnlockedPrerequisites(node);
            return false;
        }

        private bool HasUnlockedPrerequisites(TechnologyNode node)
        {
            foreach (var prerequisite in node.Prerequisites)
                if (!game.World.Technologies.IsUnlocked(prerequisite)) return false;
            return true;
        }

        private static string FormatLabel(TechnologyNode node, TechnologyVisualState state)
        {
            var suffix = state == TechnologyVisualState.Unlocked ? " [已完成]" : state == TechnologyVisualState.Available ? " [可研究]" : " [锁定]";
            return node.DisplayName + suffix;
        }

        private void ClearNodes()
        {
            foreach (var node in spawnedNodes)
                if (node != null) Destroy(node.gameObject);
            spawnedNodes.Clear();
        }

        private void OnEraAdvanced(Era era)
        {
            Refresh();
        }

        private void OnTechnologyResearched(string id)
        {
            Refresh();
        }

        private void OnWorldLoaded()
        {
            Refresh();
        }
    }
}
