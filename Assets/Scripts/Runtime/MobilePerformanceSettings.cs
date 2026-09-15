using UnityEngine;

namespace CivilizationSandbox.Runtime
{
    public sealed class MobilePerformanceSettings : MonoBehaviour
    {
        private const string Prefix = "civilization.graphics.";
        [SerializeField] private bool lowEndPreset = true;
        [SerializeField] private bool antiAliasing;
        [SerializeField] private bool shadows;
        [SerializeField] private bool particles = true;
        [SerializeField] private bool anisotropicFiltering;
        [SerializeField] private bool vSync;
        [SerializeField] private int textureLimit = 1;
        [SerializeField] private int targetFrameRate = 30;
        [SerializeField] private float lodBias = 0.7f;

        public bool AntiAliasingEnabled => antiAliasing;
        public bool ShadowsEnabled => shadows;
        public bool ParticlesEnabled => particles;
        public bool AnisotropicFilteringEnabled => anisotropicFiltering;
        public bool VSyncEnabled => vSync;
        public int TextureLimit => textureLimit;
        public int TargetFrameRate => targetFrameRate;

        private void Awake()
        {
            Load();
            ApplyCurrent();
        }

        public void ApplyLowEndPreset()
        {
            lowEndPreset = true;
            antiAliasing = false;
            shadows = false;
            particles = true;
            anisotropicFiltering = false;
            vSync = false;
            textureLimit = 1;
            targetFrameRate = 30;
            lodBias = 0.7f;
            ApplyCurrent();
            Save();
        }

        public void ApplyBalancedPreset()
        {
            lowEndPreset = false;
            antiAliasing = true;
            shadows = true;
            particles = true;
            anisotropicFiltering = true;
            vSync = true;
            textureLimit = 0;
            targetFrameRate = 60;
            lodBias = 1f;
            ApplyCurrent();
            Save();
        }

        public void SetAntiAliasing(bool enabled) { antiAliasing = enabled; ApplyCurrent(); Save(); }
        public void SetShadows(bool enabled) { shadows = enabled; ApplyCurrent(); Save(); }
        public void SetParticles(bool enabled) { particles = enabled; ApplyCurrent(); Save(); }
        public void SetAnisotropicFiltering(bool enabled) { anisotropicFiltering = enabled; ApplyCurrent(); Save(); }
        public void SetVSync(bool enabled) { vSync = enabled; ApplyCurrent(); Save(); }
        public void SetTextureLimit(int limit) { textureLimit = Mathf.Clamp(limit, 0, 3); ApplyCurrent(); Save(); }
        public void SetTargetFrameRate(int frameRate) { targetFrameRate = Mathf.Clamp(frameRate, 24, 60); ApplyCurrent(); Save(); }

        private void ApplyCurrent()
        {
            QualitySettings.vSyncCount = vSync ? 1 : 0;
            Application.targetFrameRate = targetFrameRate;
            QualitySettings.antiAliasing = antiAliasing ? 2 : 0;
            QualitySettings.anisotropicFiltering = anisotropicFiltering
                ? AnisotropicFiltering.Enable : AnisotropicFiltering.Disable;
            QualitySettings.shadows = shadows ? ShadowQuality.HardOnly : ShadowQuality.Disable;
            QualitySettings.shadowDistance = shadows ? 18f : 0f;
            QualitySettings.lodBias = Mathf.Clamp(lodBias, 0.3f, 1f);
            QualitySettings.maximumLODLevel = lowEndPreset ? 1 : 0;
            QualitySettings.masterTextureLimit = textureLimit;
            QualitySettings.particleRaycastBudget = particles ? 64 : 0;
            QualitySettings.asyncUploadTimeSlice = lowEndPreset ? 2 : 4;
        }

        private void Load()
        {
            antiAliasing = PlayerPrefs.GetInt(Prefix + "aa", antiAliasing ? 1 : 0) == 1;
            shadows = PlayerPrefs.GetInt(Prefix + "shadows", shadows ? 1 : 0) == 1;
            particles = PlayerPrefs.GetInt(Prefix + "particles", particles ? 1 : 0) == 1;
            anisotropicFiltering = PlayerPrefs.GetInt(Prefix + "aniso", anisotropicFiltering ? 1 : 0) == 1;
            vSync = PlayerPrefs.GetInt(Prefix + "vsync", vSync ? 1 : 0) == 1;
            textureLimit = PlayerPrefs.GetInt(Prefix + "texture", textureLimit);
            targetFrameRate = PlayerPrefs.GetInt(Prefix + "fps", targetFrameRate);
            textureLimit = Mathf.Clamp(textureLimit, 0, 3);
            targetFrameRate = Mathf.Clamp(targetFrameRate, 24, 60);
        }

        private void Save()
        {
            PlayerPrefs.SetInt(Prefix + "aa", antiAliasing ? 1 : 0);
            PlayerPrefs.SetInt(Prefix + "shadows", shadows ? 1 : 0);
            PlayerPrefs.SetInt(Prefix + "particles", particles ? 1 : 0);
            PlayerPrefs.SetInt(Prefix + "aniso", anisotropicFiltering ? 1 : 0);
            PlayerPrefs.SetInt(Prefix + "vsync", vSync ? 1 : 0);
            PlayerPrefs.SetInt(Prefix + "texture", textureLimit);
            PlayerPrefs.SetInt(Prefix + "fps", targetFrameRate);
            PlayerPrefs.Save();
        }
    }
}
