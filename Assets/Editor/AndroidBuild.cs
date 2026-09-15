using System.IO;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;

namespace CivilizationSandbox.EditorTools
{
    public static class AndroidBuild
    {
        private const string OutputPath = "Builds/CivilizationSandbox.apk";

        [MenuItem("Civilization Sandbox/Build Android APK")]
        public static void BuildMenu()
        {
            BuildAndroid(OutputPath);
        }

        public static void BuildFromCommandLine()
        {
            BuildAndroid(OutputPath);
        }

        private static void BuildAndroid(string outputPath)
        {
            var scenePath = "Assets/Scenes/Main.unity";
            if (!File.Exists(scenePath))
            {
                Debug.LogError($"Missing scene: {scenePath}");
                EditorApplication.Exit(2);
                return;
            }

            Directory.CreateDirectory(Path.GetDirectoryName(outputPath));
            EditorUserBuildSettings.buildAppBundle = false;
            EditorUserBuildSettings.androidBuildSystem = AndroidBuildSystem.Gradle;
            PlayerSettings.defaultInterfaceOrientation = UIOrientation.Portrait;
            PlayerSettings.allowedAutorotateToPortrait = true;
            PlayerSettings.allowedAutorotateToPortraitUpsideDown = false;
            PlayerSettings.allowedAutorotateToLandscapeLeft = false;
            PlayerSettings.allowedAutorotateToLandscapeRight = false;
            PlayerSettings.applicationIdentifier = "com.civilizationsandbox.stellarages";
            PlayerSettings.productName = "文明沙盒：星际纪元";

            var options = new BuildPlayerOptions
            {
                scenes = new[] { scenePath },
                locationPathName = outputPath,
                target = BuildTarget.Android,
                options = BuildOptions.None
            };
            var report = BuildPipeline.BuildPlayer(options);
            if (report.summary.result != BuildResult.Succeeded)
            {
                Debug.LogError($"Android build failed: {report.summary.result}");
                EditorApplication.Exit(1);
                return;
            }

            Debug.Log($"Android APK created: {Path.GetFullPath(outputPath)}");
            EditorApplication.Exit(0);
        }
    }
}
