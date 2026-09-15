using CivilizationSandbox.Runtime;
using UnityEngine;
using UnityEngine.UI;

namespace CivilizationSandbox.UI
{
    public sealed class GraphicsSettingsPanel : MonoBehaviour
    {
        [SerializeField] private MobilePerformanceSettings settings;
        [SerializeField] private Toggle antiAliasingToggle;
        [SerializeField] private Toggle shadowsToggle;
        [SerializeField] private Toggle particlesToggle;
        [SerializeField] private Toggle anisotropicToggle;
        [SerializeField] private Toggle vSyncToggle;
        [SerializeField] private Dropdown textureQualityDropdown;
        [SerializeField] private Dropdown frameRateDropdown;
        [SerializeField] private Text presetSummary;

        private void OnEnable()
        {
            if (settings == null) settings = FindObjectOfType<MobilePerformanceSettings>();
            BindListeners(true);
            RefreshControls();
        }

        private void OnDisable()
        {
            BindListeners(false);
        }

        public void UseLowEndPreset() { settings.ApplyLowEndPreset(); RefreshControls(); }
        public void UseBalancedPreset() { settings.ApplyBalancedPreset(); RefreshControls(); }
        public void ResetToRecommended() { settings.ApplyLowEndPreset(); RefreshControls(); }

        private void BindListeners(bool bind)
        {
            if (antiAliasingToggle != null) SetToggleListener(antiAliasingToggle, bind, settings != null ? settings.SetAntiAliasing : null);
            if (shadowsToggle != null) SetToggleListener(shadowsToggle, bind, settings != null ? settings.SetShadows : null);
            if (particlesToggle != null) SetToggleListener(particlesToggle, bind, settings != null ? settings.SetParticles : null);
            if (anisotropicToggle != null) SetToggleListener(anisotropicToggle, bind, settings != null ? settings.SetAnisotropicFiltering : null);
            if (vSyncToggle != null) SetToggleListener(vSyncToggle, bind, settings != null ? settings.SetVSync : null);
            if (textureQualityDropdown != null)
            {
                if (bind) textureQualityDropdown.onValueChanged.AddListener(OnTextureQualityChanged);
                else textureQualityDropdown.onValueChanged.RemoveListener(OnTextureQualityChanged);
            }
            if (frameRateDropdown != null)
            {
                if (bind) frameRateDropdown.onValueChanged.AddListener(OnFrameRateChanged);
                else frameRateDropdown.onValueChanged.RemoveListener(OnFrameRateChanged);
            }
        }

        private static void SetToggleListener(Toggle toggle, bool bind, UnityEngine.Events.UnityAction<bool> action)
        {
            if (action == null) return;
            if (bind) toggle.onValueChanged.AddListener(action);
            else toggle.onValueChanged.RemoveListener(action);
        }

        private void RefreshControls()
        {
            if (settings == null) return;
            EnsureDropdownOptions();
            SetToggleValue(antiAliasingToggle, settings.AntiAliasingEnabled);
            SetToggleValue(shadowsToggle, settings.ShadowsEnabled);
            SetToggleValue(particlesToggle, settings.ParticlesEnabled);
            SetToggleValue(anisotropicToggle, settings.AnisotropicFilteringEnabled);
            SetToggleValue(vSyncToggle, settings.VSyncEnabled);
            if (textureQualityDropdown != null) textureQualityDropdown.SetValueWithoutNotify(settings.TextureLimit);
            if (frameRateDropdown != null) frameRateDropdown.SetValueWithoutNotify(FrameRateToIndex(settings.TargetFrameRate));
            if (presetSummary != null) presetSummary.text = "帧率 " + settings.TargetFrameRate + " FPS｜纹理等级 " + settings.TextureLimit;
        }

        private void EnsureDropdownOptions()
        {
            if (textureQualityDropdown != null && textureQualityDropdown.options.Count == 0)
            {
                textureQualityDropdown.AddOptions(new System.Collections.Generic.List<string>
                { "高", "中", "低", "极低" });
            }
            if (frameRateDropdown != null && frameRateDropdown.options.Count == 0)
            {
                frameRateDropdown.AddOptions(new System.Collections.Generic.List<string>
                { "30 FPS", "45 FPS", "60 FPS" });
            }
        }

        private static void SetToggleValue(Toggle toggle, bool value)
        {
            if (toggle != null) toggle.SetIsOnWithoutNotify(value);
        }

        private void OnTextureQualityChanged(int value) => settings.SetTextureLimit(value);
        private void OnFrameRateChanged(int value) => settings.SetTargetFrameRate(IndexToFrameRate(value));
        private static int IndexToFrameRate(int index) => index == 0 ? 30 : index == 1 ? 45 : 60;
        private static int FrameRateToIndex(int value) => value <= 30 ? 0 : value <= 45 ? 1 : 2;
    }
}
