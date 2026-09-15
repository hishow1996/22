using CivilizationSandbox.Population;
using CivilizationSandbox.Simulation;
using NUnit.Framework;

namespace CivilizationSandbox.Tests
{
    public sealed class PopulationJobManagerTests
    {
        [Test]
        public void AgrarianEraCreatesFarmersAndBuilders()
        {
            var agents = CreateAgents(6);
            new PopulationJobManager().AssignForEra(agents, Era.Agrarian);

            Assert.That(agents[0].Job, Is.EqualTo(Job.Builder));
            Assert.That(agents[1].Job, Is.EqualTo(Job.Farmer));
        }

        [Test]
        public void SpaceEraCreatesAstronautsAndScientists()
        {
            var agents = CreateAgents(8);
            new PopulationJobManager().AssignForEra(agents, Era.Space);

            Assert.That(agents[0].Job, Is.EqualTo(Job.Astronaut));
            Assert.That(agents[2].Job, Is.EqualTo(Job.Scientist));
        }

        private static PopulationAgent[] CreateAgents(int count)
        {
            var agents = new PopulationAgent[count];
            for (var i = 0; i < count; i++) agents[i] = new PopulationAgent(i + 1, "Agent " + i);
            return agents;
        }
    }
}
