using NUnit.Framework;
using CivilizationSandbox.Runtime;

namespace CivilizationSandbox.Tests
{
    public sealed class VfxEventBridgeTests
    {
        [Test]
        public void RaisePublishesEventAndClampsIntensity()
        {
            var bridge = new VfxEventBridge();
            VfxEvent received = default;
            var count = 0;
            bridge.Raised += value => { received = value; count++; };

            bridge.Raise(VfxEventType.MeteorImpact, 0);

            Assert.That(count, Is.EqualTo(1));
            Assert.That(received.Type, Is.EqualTo(VfxEventType.MeteorImpact));
            Assert.That(received.Intensity, Is.EqualTo(1));
        }

        [Test]
        public void MultipleEffectsRemainOrdered()
        {
            var bridge = new VfxEventBridge();
            var last = VfxEventType.ResourceGathered;
            bridge.Raised += value => last = value.Type;

            bridge.Raise(VfxEventType.MeteorWarning);
            bridge.Raise(VfxEventType.MeteorImpact);

            Assert.That(last, Is.EqualTo(VfxEventType.MeteorImpact));
        }
    }
}
