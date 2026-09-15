using CivilizationSandbox.Nations;
using CivilizationSandbox.Simulation;
using NUnit.Framework;

namespace CivilizationSandbox.Tests
{
    public sealed class DiplomacyStateTests
    {
        [Test]
        public void TradeMovesTreasuryAndRecordsAction()
        {
            var buyer = new NationState("Buyer", Era.Agrarian) { Treasury = 100 };
            var seller = new NationState("Seller", Era.Agrarian) { Treasury = 10 };
            var diplomacy = new DiplomacySystem();

            Assert.That(diplomacy.ExecuteTrade(buyer, seller, 25), Is.True);
            Assert.That(buyer.Treasury, Is.EqualTo(75));
            Assert.That(seller.Treasury, Is.EqualTo(35));
        }

        [Test]
        public void WorldDiplomacyRestoresCounters()
        {
            var state = new DiplomacyState();
            state.Restore(2, 3, 4, "战争结束：Aurora");

            Assert.That(state.TradeCount, Is.EqualTo(2));
            Assert.That(state.AllianceCount, Is.EqualTo(3));
            Assert.That(state.WarCount, Is.EqualTo(4));
            Assert.That(state.LastAction, Is.EqualTo("战争结束：Aurora"));
        }
    }
}
