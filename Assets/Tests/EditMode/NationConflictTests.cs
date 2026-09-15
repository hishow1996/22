using NUnit.Framework;
using CivilizationSandbox.Nations;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Tests
{
    public sealed class NationConflictTests
    {
        [Test]
        public void NationsCanFormAlliance()
        {
            var first = new NationState("Aurora", Era.Agrarian);
            var second = new NationState("Sol", Era.Agrarian);
            var diplomacy = new DiplomacySystem();

            Assert.That(diplomacy.FormAlliance(first, second), Is.True);
            Assert.That(first.IsAlliedWith(second), Is.True);
        }

        [Test]
        public void WarProducesWinnerBasedOnMilitaryStrength()
        {
            var first = new NationState("Aurora", Era.Industrial) { MilitaryStrength = 80 };
            var second = new NationState("Sol", Era.Industrial) { MilitaryStrength = 20 };
            var diplomacy = new DiplomacySystem();

            var result = diplomacy.ResolveWar(first, second);

            Assert.That(result.Winner, Is.EqualTo(first));
            Assert.That(result.Loser, Is.EqualTo(second));
        }
    }
}
