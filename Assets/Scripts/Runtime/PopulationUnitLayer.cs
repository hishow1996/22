using System.Collections.Generic;
using CivilizationSandbox.Population;
using UnityEngine;

namespace CivilizationSandbox.Runtime
{
    public sealed class PopulationUnitLayer : MonoBehaviour
    {
        [SerializeField] private CivilizationGameController game;
        [SerializeField] private PopulationUnitPresenter unitPrefab;
        [SerializeField] private Transform unitRoot;
        private readonly Dictionary<int, PopulationUnitPresenter> presenters = new Dictionary<int, PopulationUnitPresenter>();
        private int lastMovementRevision = -1;

        public void Initialize(CivilizationGameController controller)
        {
            game = controller;
            SyncUnits();
        }

        public void ResetUnits()
        {
            foreach (var presenter in presenters.Values)
                if (presenter != null) Destroy(presenter.gameObject);
            presenters.Clear();
            lastMovementRevision = -1;
        }

        private void Update()
        {
            if (game != null && game.World != null && game.Movement.Revision != lastMovementRevision) SyncUnits();
        }

        private void SyncUnits()
        {
            if (game == null || game.Agents == null || game.Map == null) return;
            lastMovementRevision = game.Movement.Revision;
            foreach (var agent in game.Agents)
            {
                if (agent == null || !agent.IsAlive) continue;
                if (!presenters.TryGetValue(agent.Id, out var presenter))
                {
                    presenter = CreatePresenter(agent);
                    presenters.Add(agent.Id, presenter);
                }
                if (game.Movement.Positions.TryGetValue(agent.Id, out var position))
                    presenter.Sync(position, game.Map.Width, game.Map.Height);
            }
        }

        private PopulationUnitPresenter CreatePresenter(PopulationAgent agent)
        {
            PopulationUnitPresenter presenter;
            if (unitPrefab != null)
            {
                presenter = Instantiate(unitPrefab, unitRoot != null ? unitRoot : transform);
            }
            else
            {
                var objectRoot = new GameObject($"Unit_{agent.Id}");
                objectRoot.transform.SetParent(unitRoot != null ? unitRoot : transform);
                presenter = objectRoot.AddComponent<PopulationUnitPresenter>();
            }
            presenter.Bind(agent.Id);
            return presenter;
        }
    }
}
