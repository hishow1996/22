using NUnit.Framework;
using CivilizationSandbox.Population;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Tests
{
    public sealed class PopulationAgentTests
    {
        [Test]
        public void NewAgentStartsAsGatherer()
        {
            var agent = new PopulationAgent(1, "Ayla");
            Assert.That(agent.Job, Is.EqualTo(Job.Gatherer));
            Assert.That(agent.IsAlive, Is.True);
        }

        [Test]
        public void AgentCanChangeJobWhenEraAllowsIt()
        {
            var agent = new PopulationAgent(1, "Ayla");
            Assert.That(agent.TrySetJob(Job.Engineer, Era.Primordial), Is.False);
            Assert.That(agent.TrySetJob(Job.Engineer, Era.Industrial), Is.True);
            Assert.That(agent.Job, Is.EqualTo(Job.Engineer));
        }
    }
}
