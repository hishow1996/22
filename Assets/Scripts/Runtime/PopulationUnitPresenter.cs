using CivilizationSandbox.Population;
using UnityEngine;

namespace CivilizationSandbox.Runtime
{
    public sealed class PopulationUnitPresenter : MonoBehaviour
    {
        [SerializeField] private Animator animator;
        [SerializeField] private float pixelsPerUnit = 32f;
        private int agentId = -1;
        private Vector3 lastPosition;

        public void Bind(int id)
        {
            agentId = id;
            lastPosition = transform.position;
        }

        public void Sync(AgentGridPosition position, int mapWidth, int mapHeight)
        {
            var target = new Vector3(
                position.X - mapWidth / 2f,
                position.Y - mapHeight / 2f,
                0f);
            var moving = Vector3.Distance(lastPosition, target) > 0.001f;
            transform.position = target;
            lastPosition = target;
            if (animator != null) animator.SetBool("IsMoving", moving);
        }

        public int AgentId => agentId;
        public float PixelsPerUnit => pixelsPerUnit;
    }
}
