using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Population
{
    public sealed class PopulationJobManager
    {
        public void AssignForEra(PopulationAgent[] agents, Era era)
        {
            if (agents == null) return;
            for (var i = 0; i < agents.Length; i++)
            {
                if (agents[i] == null || !agents[i].IsAlive) continue;
                var job = SelectJob(i, era);
                agents[i].TrySetJob(job, era);
            }
        }

        private static Job SelectJob(int index, Era era)
        {
            switch (era)
            {
                case Era.Agrarian:
                    return index % 3 == 0 ? Job.Builder : Job.Farmer;
                case Era.Industrial:
                    return index % 3 == 0 ? Job.Builder : index % 3 == 1 ? Job.Engineer : Job.Farmer;
                case Era.Modern:
                    return index % 3 == 0 ? Job.Scientist : index % 3 == 1 ? Job.Engineer : Job.Builder;
                case Era.Space:
                    return index % 4 == 0 ? Job.Astronaut : index % 2 == 0 ? Job.Scientist : Job.Engineer;
                default:
                    return Job.Gatherer;
            }
        }
    }
}
