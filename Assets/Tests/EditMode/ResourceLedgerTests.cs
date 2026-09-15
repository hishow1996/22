using NUnit.Framework;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Tests
{
    public sealed class ResourceLedgerTests
    {
        [Test]
        public void AddAndSpendKeepsExpectedBalance()
        {
            var ledger = new ResourceLedger();
            ledger.Add(ResourceType.Food, 20);

            Assert.That(ledger.TrySpend(ResourceType.Food, 7), Is.True);
            Assert.That(ledger.Get(ResourceType.Food), Is.EqualTo(13));
        }

        [Test]
        public void SpendFailsWithoutChangingBalanceWhenInsufficient()
        {
            var ledger = new ResourceLedger();
            ledger.Add(ResourceType.Metal, 2);

            Assert.That(ledger.TrySpend(ResourceType.Metal, 3), Is.False);
            Assert.That(ledger.Get(ResourceType.Metal), Is.EqualTo(2));
        }
    }
}
