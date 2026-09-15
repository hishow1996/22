using CivilizationSandbox.Simulation;
using NUnit.Framework;

namespace CivilizationSandbox.Tests
{
    public sealed class WorldEventLogTests
    {
        [Test]
        public void LogKeepsLatestEntriesWithinCapacity()
        {
            var log = new WorldEventLog(2);
            log.Add("第一件事");
            log.Add("第二件事");
            log.Add("第三件事");

            Assert.That(log.Entries.Count, Is.EqualTo(2));
            Assert.That(log.Latest, Is.EqualTo("第三件事"));
            Assert.That(log.Entries[0], Is.EqualTo("第二件事"));
        }

        [Test]
        public void LogRestoresSavedEntries()
        {
            var log = new WorldEventLog();
            log.Restore(new[] { "时代跃迁：农业时代", "科技突破：farming" });

            Assert.That(log.Latest, Is.EqualTo("科技突破：farming"));
            Assert.That(log.Entries.Count, Is.EqualTo(2));
        }
    }
}
