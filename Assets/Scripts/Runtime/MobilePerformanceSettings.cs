using UnityEngine;

namespace CivilizationSandbox.Runtime
{
    public sealed class MobilePerformanceSettings : MonoBehaviour
    {
        [SerializeField] private bool lowEndPreset = true;
        [SerializeField] private int targetFrameRate = 30;
        [SerializeField] private int lowEndTextureLimit = 1;
        [SerializeField] private float lowEndLodBias = 0.7f;

        private void Awake()
        {
            Apply(lowEndPreset);
        }

        public void ApplyLowEndPreset() => Apply(true);
        public void ApplyBalancedPreset() => Apply(false);

        private void Apply(bool lowEnd)
        {
            QualitySettings.vSyncCount = 0;
            Application.targetFrameRate = lowEnd ? Mathf.Clamp(targetFrameRate, 24, 30) : 60;
            QualitySettings.antiAliasing = 0;
            QualitySettings.anisotropicFiltering = AnisotropicFiltering.Disable;
            QualitySettings.shadows = lowEnd ? ShadowQuality.Disable : ShadowQuality.HardOnly;
            QualitySettings.shadowDistance = lowEnd ? 0f : 18f;
            QualitySettings.lodBias = lowEnd ? Mathf.Clamp(lowEndLodBias, 0.3f, 1f) : 1f;
            QualitySettings.maximumLODLevel = lowEnd ? 1 : 0;
            QualitySettings.masterTextureLimit = lowEnd ? Mathf.Clamp(lowEndTextureLimit, 0, 3) : 0;
            QualitySettings.particleRaycastBudget = lowEnd ? 16 : 64;
            QualitySettings.asyncUploadTimeSlice = lowEnd ? 2 : 4;
        }
    }
}
