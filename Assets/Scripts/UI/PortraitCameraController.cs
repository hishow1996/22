using UnityEngine;

namespace CivilizationSandbox.UI
{
    public sealed class PortraitCameraController : MonoBehaviour
    {
        [SerializeField] private Camera targetCamera;
        [SerializeField] private float dragSpeed = 0.012f;
        [SerializeField] private float zoomSpeed = 0.08f;
        [SerializeField] private float minZoom = 3f;
        [SerializeField] private float maxZoom = 16f;

        private Vector2 previousPointer;
        private bool dragging;

        private void Awake()
        {
            if (targetCamera == null) targetCamera = Camera.main;
            if (targetCamera != null) targetCamera.orthographic = true;
        }

        private void Update()
        {
            if (targetCamera == null) return;
            HandlePointer();
            HandleZoom();
        }

        private void HandlePointer()
        {
            if (Input.touchCount > 0)
            {
                var touch = Input.GetTouch(0);
                if (touch.phase == TouchPhase.Began) { previousPointer = touch.position; dragging = true; }
                if (dragging && touch.phase == TouchPhase.Moved) Pan(touch.position - previousPointer);
                if (touch.phase == TouchPhase.Ended || touch.phase == TouchPhase.Canceled) dragging = false;
                previousPointer = touch.position;
                return;
            }

            if (Input.GetMouseButtonDown(0)) { previousPointer = Input.mousePosition; dragging = true; }
            if (dragging && Input.GetMouseButton(0)) Pan((Vector2)Input.mousePosition - previousPointer);
            if (Input.GetMouseButtonUp(0)) dragging = false;
            previousPointer = Input.mousePosition;
        }

        private void Pan(Vector2 delta)
        {
            targetCamera.transform.position -= (Vector3)(delta * dragSpeed * targetCamera.orthographicSize / 8f);
        }

        private void HandleZoom()
        {
            var pinch = 0f;
            if (Input.touchCount >= 2)
            {
                var first = Input.GetTouch(0);
                var second = Input.GetTouch(1);
                var previousFirst = first.position - first.deltaPosition;
                var previousSecond = second.position - second.deltaPosition;
                pinch = (Vector2.Distance(previousFirst, previousSecond) - Vector2.Distance(first.position, second.position)) * zoomSpeed;
            }
            targetCamera.orthographicSize = Mathf.Clamp(targetCamera.orthographicSize + pinch - Input.mouseScrollDelta.y * zoomSpeed, minZoom, maxZoom);
        }
    }
}
