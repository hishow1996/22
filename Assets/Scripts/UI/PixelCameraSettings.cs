using UnityEngine;
using UnityEngine.U2D;

namespace CivilizationSandbox.UI
{
    [RequireComponent(typeof(Camera))]
    public sealed class PixelCameraSettings : MonoBehaviour
    {
        [SerializeField] private int assetsPixelsPerUnit = 32;
        [SerializeField] private int referenceResolutionX = 360;
        [SerializeField] private int referenceResolutionY = 640;

        private void Awake()
        {
            var pixelPerfect = GetComponent<PixelPerfectCamera>();
            if (pixelPerfect == null) pixelPerfect = gameObject.AddComponent<PixelPerfectCamera>();
            pixelPerfect.assetsPPU = assetsPixelsPerUnit;
            pixelPerfect.refResolutionX = referenceResolutionX;
            pixelPerfect.refResolutionY = referenceResolutionY;
            pixelPerfect.upscaleRT = true;
            pixelPerfect.pixelSnapping = true;
        }
    }
}
