using NUnit.Framework;
using CivilizationSandbox.GodControls;

namespace CivilizationSandbox.Tests
{
    public sealed class GodControlTests
    {
        [Test]
        public void TimeScaleIsClampedToSafeRange()
        {
            var controls = new GodControlState();
            controls.SetTimeScale(99f);
            Assert.That(controls.TimeScale, Is.EqualTo(8f));
            controls.SetTimeScale(0f);
            Assert.That(controls.TimeScale, Is.EqualTo(0.25f));
        }

        [Test]
        public void PauseAndDisasterStateAreObservable()
        {
            var controls = new GodControlState();
            controls.TogglePause();
            controls.TriggerDisaster();
            Assert.That(controls.IsPaused, Is.True);
            Assert.That(controls.DisasterCount, Is.EqualTo(1));
        }
    }
}
