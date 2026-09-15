using System.Text;
using CivilizationSandbox.Runtime;
using UnityEngine;
using UnityEngine.UI;

namespace CivilizationSandbox.UI
{
    public sealed class WorldEventLogPanel : MonoBehaviour
    {
        [SerializeField] private CivilizationGameController game;
        [SerializeField] private Text eventText;
        [SerializeField] private int visibleEntries = 8;
        [SerializeField] private bool newestFirst = true;

        private void OnEnable()
        {
            if (game != null) game.EraAdvanced += OnEraAdvanced;
            Refresh();
        }

        private void OnDisable()
        {
            if (game != null) game.EraAdvanced -= OnEraAdvanced;
        }

        private void Update()
        {
            if (game != null && game.World != null) Refresh();
        }

        public void Refresh()
        {
            if (game == null || game.World == null || eventText == null) return;
            var entries = game.World.EventLog.Entries;
            var count = Mathf.Min(Mathf.Max(1, visibleEntries), entries.Count);
            var builder = new StringBuilder();
            for (var i = 0; i < count; i++)
            {
                var index = newestFirst ? entries.Count - 1 - i : entries.Count - count + i;
                if (i > 0) builder.Append('\n');
                builder.Append('•').Append(' ').Append(entries[index]);
            }
            eventText.text = builder.ToString();
        }

        private void OnEraAdvanced(CivilizationSandbox.Simulation.Era era)
        {
            Refresh();
        }
    }
}
