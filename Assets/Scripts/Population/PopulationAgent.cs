using System;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Population
{
    public enum Job { Gatherer, Farmer, Builder, Soldier, Engineer, Scientist, Astronaut }

    public sealed class PopulationAgent
    {
        public int Id { get; }
        public string DisplayName { get; }
        public Job Job { get; private set; } = Job.Gatherer;
        public bool IsAlive { get; private set; } = true;
        public int Age { get; private set; } = 18;

        public PopulationAgent(int id, string displayName)
        {
            if (id < 0) throw new ArgumentOutOfRangeException(nameof(id));
            Id = id;
            DisplayName = displayName ?? throw new ArgumentNullException(nameof(displayName));
        }

        public bool TrySetJob(Job job, Era currentEra)
        {
            var requiredEra = job switch
            {
                Job.Engineer => Era.Industrial,
                Job.Scientist => Era.Modern,
                Job.Astronaut => Era.Space,
                Job.Soldier => Era.Agrarian,
                _ => Era.Primordial
            };
            if (currentEra < requiredEra) return false;
            Job = job;
            return true;
        }

        public void TickYear()
        {
            if (!IsAlive) return;
            Age++;
            if (Age > 90) IsAlive = false;
        }
    }
}
