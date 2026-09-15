using System;
using System.IO;
using CivilizationSandbox.Simulation;
using UnityEngine;

namespace CivilizationSandbox.Persistence
{
    public static class SaveFileService
    {
        public static string DefaultPath => Path.Combine(Application.persistentDataPath, "civilization-sandbox-save.json");

        public static void Save(WorldState world, string path = null)
        {
            if (world == null) throw new ArgumentNullException(nameof(world));
            var target = string.IsNullOrEmpty(path) ? DefaultPath : path;
            var directory = Path.GetDirectoryName(target);
            if (!string.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);
            File.WriteAllText(target, SaveSystem.ToJson(world));
        }

        public static bool TryLoad(out SaveData data, string path = null)
        {
            var target = string.IsNullOrEmpty(path) ? DefaultPath : path;
            data = null;
            if (!File.Exists(target)) return false;
            try
            {
                data = JsonUtility.FromJson<SaveData>(File.ReadAllText(target));
                return data != null && data.version >= 1;
            }
            catch (Exception)
            {
                data = null;
                return false;
            }
        }
    }
}
