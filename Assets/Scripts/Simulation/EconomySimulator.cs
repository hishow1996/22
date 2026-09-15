using System;
using CivilizationSandbox.Population;

namespace CivilizationSandbox.Simulation
{
    public sealed class EconomySimulator
    {
        public void Tick(WorldState world, PopulationAgent[] agents, int elapsedDays)
        {
            if (world == null) throw new ArgumentNullException(nameof(world));
            if (agents == null) throw new ArgumentNullException(nameof(agents));
            if (elapsedDays <= 0) return;

            foreach (var agent in agents)
            {
                if (agent == null || !agent.IsAlive) continue;
                switch (agent.Job)
                {
                    case Job.Gatherer:
                        world.Resources.Add(ResourceType.Food, elapsedDays);
                        world.Resources.Add(ResourceType.Wood, elapsedDays);
                        break;
                    case Job.Farmer:
                        world.Resources.Add(ResourceType.Food, elapsedDays * 2);
                        break;
                    case Job.Builder:
                        world.Resources.Add(ResourceType.Stone, elapsedDays);
                        break;
                    case Job.Engineer:
                        world.Resources.Add(ResourceType.Metal, elapsedDays);
                        world.Resources.Add(ResourceType.Electricity, elapsedDays);
                        break;
                    case Job.Scientist:
                        if (world.Progression.CurrentEra >= Era.Modern)
                            world.Resources.Add(ResourceType.Science, elapsedDays);
                        break;
                    case Job.Astronaut:
                        if (world.Progression.CurrentEra == Era.Space)
                            world.Resources.Add(ResourceType.Science, elapsedDays * 2);
                        break;
                }
            }
        }
    }
}
