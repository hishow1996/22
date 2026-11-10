gradle-wrapper.properties pins Gradle 8.9 for Android Studio.
gradlew / gradlew.bat are self-bootstrapping: if gradle-wrapper.jar is absent
they use Android Studio's Gradle, a cached distribution, or download Gradle 8.9.
Run `gradlew wrapper` once online to materialise the canonical wrapper jar.
