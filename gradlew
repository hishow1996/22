#!/usr/bin/env sh
# Self-bootstrapping Gradle launcher.
# Works with or without gradle-wrapper.jar: prefers Android Studio's Gradle,
# a cached distribution, or downloads gradle-8.9 and delegates to it.
set -e
APP_HOME=$(cd "$(dirname "$0")" && pwd)
GV=8.9
DIST_URL="https://services.gradle.org/distributions/gradle-${GV}-bin.zip"
CACHE="$HOME/.gradle/wrapper/dists/gradle-${GV}-bin"

find_gradle() {
    if [ -n "$GRADLE_HOME" ] && [ -x "$GRADLE_HOME/bin/gradle" ]; then
        echo "$GRADLE_HOME/bin/gradle"; return 0
    fi
    if [ -d "$CACHE" ]; then
        g=$(ls -d "$CACHE"/*/gradle-"$GV"/bin/gradle 2>/dev/null | head -n 1 || true)
        if [ -n "$g" ] && [ -x "$g" ]; then echo "$g"; return 0; fi
    fi
    if command -v gradle >/dev/null 2>&1; then echo "gradle"; return 0; fi
    for c in \
        "/Applications/Android Studio.app/Contents/gradle/gradle-$GV/bin/gradle" \
        "/opt/android-studio/gradle/gradle-$GV/bin/gradle" \
        "$HOME/.gradle/caches/gradle-$GV/bin/gradle"; do
        if [ -x "$c" ]; then echo "$c"; return 0; fi
    done
    return 1
}

GRADLE_BIN=$(find_gradle || true)
if [ -z "$GRADLE_BIN" ]; then
    echo "Gradle $GV not found, downloading once..."
    mkdir -p "$CACHE/pkg"
    ZIP="$CACHE/pkg/gradle-$GV-bin.zip"
    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 2 -o "$ZIP" "$DIST_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$ZIP" "$DIST_URL"
    else
        echo "Need curl or wget, or install Gradle $GV manually." >&2; exit 1
    fi
    (cd "$CACHE/pkg" && unzip -q -o "gradle-$GV-bin.zip")
    GRADLE_BIN="$CACHE/pkg/gradle-$GV/bin/gradle"
fi

chmod +x "$GRADLE_BIN" 2>/dev/null || true
cd "$APP_HOME"
exec "$GRADLE_BIN" "$@"
