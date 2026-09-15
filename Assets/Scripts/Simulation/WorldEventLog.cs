using System;
using System.Collections.Generic;

namespace CivilizationSandbox.Simulation
{
    public sealed class WorldEventLog
    {
        private readonly List<string> entries = new List<string>();
        private readonly int capacity;

        public WorldEventLog(int capacity = 40)
        {
            this.capacity = Math.Max(1, capacity);
        }

        public IReadOnlyList<string> Entries => entries;
        public string Latest => entries.Count == 0 ? "暂无文明事件" : entries[entries.Count - 1];
        public event Action<string> Changed;

        public void Add(string message)
        {
            if (string.IsNullOrEmpty(message)) return;
            entries.Add(message);
            while (entries.Count > capacity) entries.RemoveAt(0);
            Changed?.Invoke(message);
        }

        public void Restore(IEnumerable<string> restoredEntries)
        {
            entries.Clear();
            if (restoredEntries == null) return;
            foreach (var entry in restoredEntries) Add(entry);
        }
    }
}
