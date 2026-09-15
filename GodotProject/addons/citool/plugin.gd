@tool
extends EditorPlugin

## CI 专用：存在工具链时把 Android SDK / Java SDK 路径写入编辑器设置。
## 路径不存在时静默跳过，不影响其他机器。

const JAVA_SDK := "/home/z/unity/Editor/Data/PlaybackEngines/AndroidPlayer/OpenJDK"
const ANDROID_SDK := "/home/z/unity/Editor/Data/PlaybackEngines/AndroidPlayer/SDK"

func _enter_tree() -> void:
	if not DirAccess.dir_exists_absolute(JAVA_SDK + "/bin"):
		return
	var settings := get_editor_interface().get_editor_settings()
	settings.set_setting("export/android/java_sdk_path", JAVA_SDK)
	settings.set_setting("export/android/android_sdk_path", ANDROID_SDK)
	if settings.has_method("save"):
		settings.call("save")
	print("[citool] android toolchain paths configured")
