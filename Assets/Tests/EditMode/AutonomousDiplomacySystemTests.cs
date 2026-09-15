using CivilizationSandbox.Nations;
using CivilizationSandbox.Simulation;
using NUnit.Framework;

namespace CivilizationSandbox.Tests
{
    public sealed class AutonomousDiplomacySystemTests
    {
        [Test]
        public void AiWaitsBeforeMakingDecision()
        {
            var world = CreateWorld();
            var ai = new AutonomousDiplomacySystem();

            var result = ai.Tick(world, 29);

            Assert.That(result.Action, Is.EqualTo(AutonomousDiplomacyAction.None));
        }

        [Test]
        public void AiMakesDeterministicDiplomacyDecisionAfterThirtyDays()
        {
            var world = CreateWorld();
            var ai = new AutonomousDiplomacySystem();

            var result = ai.Tick(world, 30);

            Assert.That(result.Action, Is.Not.EqualTo(AutonomousDiplomacyAction.None));
            Assert.That(world.Diplomacy.TradeCount + world.Diplomacy.AllianceCount + world.Diplomacy.WarCount, Is.EqualTo(1));
        }

        private static WorldState CreateWorld()
        {
            var world = new WorldState(7);
            world.Nations.Add(new NationState("Aurora", Era.Primordial));
            world.Nations.Add(new NationState("Sol", Era.Primordial));
            return world;
        }
    }
}
