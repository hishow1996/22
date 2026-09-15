using System;
using CivilizationSandbox.Population;

namespace CivilizationSandbox.Simulation
{
    public sealed class EconomySimulator
    {
        public void Tick(WorldState world, PopulationAgent[] agents, int elapsedDays)
        {
            Tick(world, agents, elapsedDays, 1f);
        }

        public void Tick(WorldState world, PopulationAgent[] agents, int elapsedDays, float foodProductionMultiplier)
        {
            Tick(world, agents, elapsedDays, foodProductionMultiplier, null);
        }

        public void Tick(WorldState world, PopulationAgent[] agents, int elapsedDays, float foodProductionMultiplier, Action<ResourceType, int> onProduced)
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
                        var food = (int)(elapsedDays * foodProductionMultiplier);
                        world.Resources.Add(ResourceType.Food, food);
                        world.Resources.Add(ResourceType.Wood, elapsedDays);
                        onProduced?.Invoke(ResourceType.Food, food + elapsedDays);
                        break;
                    case Job.Farmer:
                        var farmFood = (int)(elapsedDays * 2 * foodProductionMultiplier);
                        world.Resources.Add(ResourceType.Food, farmFood);
                        onProduced?.Invoke(ResourceType.Food, farmFood);
                        break;
                    case Job.Builder:
                        world.Resources.Add(ResourceType.Stone, elapsedDays);
                        onProduced?.Invoke(ResourceType.Stone, elapsedDays);
                        break;
                    case Job.Engineer:
                        world.Resources.Add(ResourceType.Metal, elapsedDays);
                        world.Resources.Add(ResourceType.Electricity, elapsedDays);
                        onProduced?.Invoke(ResourceType.Metal, elapsedDays);
                        break;
                    case Job.Scientist:
                        if (world.Progression.CurrentEra >= Era.Modern)
                            world.Resources.Add(ResourceType.Science, elapsedDays);
                            onProduced?.Invoke(ResourceType.Science, elapsedDays);
                        break;
                    case Job.Astronaut:
                        if (world.Progression.CurrentEra == Era.Space)
                            world.Resources.Add(ResourceType.Science, elapsedDays * 2);
                            onProduced?.Invoke(ResourceType.Science, elapsedDays * 2);
                        break;
                }
            }
        }
    }
}
