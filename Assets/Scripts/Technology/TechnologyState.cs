using System.Collections.Generic;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Technology
{
    public sealed class TechnologyState
    {
        private readonly HashSet<string> unlocked = new HashSet<string>();
        public IReadOnlyCollection<string> Unlocked => unlocked;

        public bool IsUnlocked(string id) => !string.IsNullOrEmpty(id) && unlocked.Contains(id);

        public void UnlockEra(Era era)
        {
            foreach (var node in EraTechnologyCatalog.All)
                if (node.Era <= era && node.Cost == 0) unlocked.Add(node.Id);
        }

        public bool TryResearch(string id, WorldState world)
        {
            var node = Find(id);
            if (node == null || world == null || node.Era > world.Progression.CurrentEra || IsUnlocked(id)) return false;
            foreach (var prerequisite in node.Prerequisites)
                if (!IsUnlocked(prerequisite)) return false;
            if (!world.Resources.TrySpend(node.ResearchCost, node.Cost)) return false;
            unlocked.Add(node.Id);
            return true;
        }

        public int CountAvailable(Era era)
        {
            var count = 0;
            foreach (var node in EraTechnologyCatalog.All)
                if (node.Era <= era && !IsUnlocked(node.Id) && HasPrerequisites(node)) count++;
            return count;
        }

        public void Restore(IEnumerable<string> ids)
        {
            unlocked.Clear();
            if (ids == null) return;
            foreach (var id in ids)
                if (Find(id) != null) unlocked.Add(id);
        }

        private bool HasPrerequisites(TechnologyNode node)
        {
            foreach (var prerequisite in node.Prerequisites)
                if (!IsUnlocked(prerequisite)) return false;
            return true;
        }

        private static TechnologyNode Find(string id)
        {
            foreach (var node in EraTechnologyCatalog.All)
                if (node.Id == id) return node;
            return null;
        }
    }
}
