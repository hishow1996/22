using System.Collections.Generic;
using CivilizationSandbox.Settlement;
using CivilizationSandbox.Simulation;

namespace CivilizationSandbox.Technology
{
    public sealed class TechnologyNode
    {
        public string Id { get; }
        public string DisplayName { get; }
        public Era Era { get; }
        public ResourceType ResearchCost { get; }
        public int Cost { get; }
        public IReadOnlyList<string> Prerequisites { get; }

        public TechnologyNode(string id, string displayName, Era era, ResourceType researchCost, int cost, params string[] prerequisites)
        {
            Id = id; DisplayName = displayName; Era = era; ResearchCost = researchCost; Cost = cost;
            Prerequisites = prerequisites;
        }
    }

    public static class EraTechnologyCatalog
    {
        public static IReadOnlyList<TechnologyNode> All { get; } = new List<TechnologyNode>
        {
            new TechnologyNode("fire", "掌握火种", Era.Primordial, ResourceType.Science, 0),
            new TechnologyNode("toolmaking", "石器工具", Era.Primordial, ResourceType.Science, 10, "fire"),
            new TechnologyNode("farming", "定居农业", Era.Agrarian, ResourceType.Science, 30, "toolmaking"),
            new TechnologyNode("masonry", "砖石建筑", Era.Agrarian, ResourceType.Science, 45, "farming"),
            new TechnologyNode("steam", "蒸汽动力", Era.Industrial, ResourceType.Science, 80, "masonry"),
            new TechnologyNode("electricity", "电力网络", Era.Industrial, ResourceType.Science, 120, "steam"),
            new TechnologyNode("computing", "计算机", Era.Modern, ResourceType.Science, 180, "electricity"),
            new TechnologyNode("rocketry", "轨道火箭", Era.Modern, ResourceType.Science, 260, "computing"),
            new TechnologyNode("spaceflight", "深空飞行", Era.Space, ResourceType.Science, 500, "rocketry")
        };

        public static IReadOnlyList<BuildingDefinition> Buildings { get; } = new List<BuildingDefinition>
        {
            new BuildingDefinition(BuildingType.Campfire, Era.Primordial, ResourceType.Wood, 5),
            new BuildingDefinition(BuildingType.Hut, Era.Primordial, ResourceType.Wood, 15),
            new BuildingDefinition(BuildingType.Farm, Era.Agrarian, ResourceType.Wood, 25),
            new BuildingDefinition(BuildingType.Workshop, Era.Industrial, ResourceType.Metal, 30),
            new BuildingDefinition(BuildingType.Factory, Era.Industrial, ResourceType.Metal, 80),
            new BuildingDefinition(BuildingType.ResearchLab, Era.Modern, ResourceType.Metal, 100),
            new BuildingDefinition(BuildingType.LaunchSite, Era.Space, ResourceType.Metal, 50)
        };
    }
}
