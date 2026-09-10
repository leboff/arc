#!/usr/bin/env bash
set -eo pipefail

export IS_SANDBOX=1
export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
export ANDROID_HOME="/root/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

REPO_DIR="/root/daydream-vr-player"
cd "$REPO_DIR"

echo "=== Arc VR Player: Milestone 8 Screen Integration Sprint ==="
echo "Started at: $(date)"

claude -p --model sonnet --dangerously-skip-permissions --max-turns 125 < docs/M8_INTEGRATION_PROMPT.md > docs/m8_build.log 2>&1 || {
    echo "Warning: Sonnet run finished with non-zero exit code. Log tail:"
    tail -n 25 docs/m8_build.log || true
}

echo "Sonnet pass finished. Git status:"
git status --short

echo "Running verification unit tests..."
./gradlew testDebugUnitTest --no-daemon

echo "Building release APK..."
./gradlew assembleRelease --no-daemon

RELEASE_SIGNED="/root/Arc-v0.4.0-release.apk"
if [ -f "app/build/outputs/apk/release/app-release.apk" ]; then
    cp -v "app/build/outputs/apk/release/app-release.apk" "$RELEASE_SIGNED"
    /root/android-sdk/build-tools/35.0.0/apksigner verify -v "$RELEASE_SIGNED"
    echo "Release APK copied and verified at $RELEASE_SIGNED"
fi

echo "M8 Sprint complete at: $(date)"
