using UnityEngine;
using UnityEngine.Events;

namespace CivilizationSandbox.Runtime
{
    public sealed class VfxEventPresenter : MonoBehaviour
    {
        [SerializeField] private CivilizationGameController game;
        [SerializeField] private UnityEvent<int> onResourceGathered;
        [SerializeField] private UnityEvent<int> onRainStarted;
        [SerializeField] private UnityEvent<int> onMeteorWarning;
        [SerializeField] private UnityEvent<int> onMeteorImpact;
        [SerializeField] private UnityEvent<int> onRocketLaunch;
        [SerializeField] private UnityEvent<int> onEraAdvanced;

        private void OnEnable()
        {
            if (game != null) game.VfxEvents.Raised += Handle;
        }

        private void OnDisable()
        {
            if (game != null) game.VfxEvents.Raised -= Handle;
        }

        private void Handle(VfxEvent value)
        {
            switch (value.Type)
            {
                case VfxEventType.ResourceGathered: onResourceGathered?.Invoke(value.Intensity); break;
                case VfxEventType.RainStarted: onRainStarted?.Invoke(value.Intensity); break;
                case VfxEventType.MeteorWarning: onMeteorWarning?.Invoke(value.Intensity); break;
                case VfxEventType.MeteorImpact: onMeteorImpact?.Invoke(value.Intensity); break;
                case VfxEventType.RocketLaunch: onRocketLaunch?.Invoke(value.Intensity); break;
                case VfxEventType.EraAdvanced: onEraAdvanced?.Invoke(value.Intensity); break;
            }
        }
    }
}
