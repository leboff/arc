#!/usr/bin/env bash
set -eo pipefail

export IS_SANDBOX=1
export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
export ANDROID_HOME="/root/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

REPO_DIR="/root/daydream-vr-player"
cd "$REPO_DIR"

echo "=== Arc VR Player: Playback Fixes & PLAY'A UI Finish ==="
echo "Started at: $(date)"

# 1. Run Claude Sonnet
echo "Launching Claude Sonnet..."
claude -p --model sonnet --dangerously-skip-permissions --max-turns 125 < docs/SONNET_FINISH_UI_AND_PLAYBACK_PROMPT.md > docs/sonnet_finish.log 2>&1 || {
    echo "Warning: Sonnet run encountered non-zero exit. Tail of log:"
    tail -n 25 docs/sonnet_finish.log || true
}

echo "Sonnet pass finished. Git status:"
git status --short

# 2. Independent Test & Build Verification
echo "Running test suite..."
./gradlew testDebugUnitTest --no-daemon

echo "Building release APK..."
./gradlew assembleRelease --no-daemon

RELEASE_UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
RELEASE_SIGNED="/root/Arc-v0.3.1-release.apk"

if [ -f "$RELEASE_UNSIGNED" ]; then
    echo "Signing release APK..."
    /root/android-sdk/build-tools/35.0.0/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --out "$RELEASE_SIGNED" "$RELEASE_UNSIGNED"
    /root/android-sdk/build-tools/35.0.0/apksigner verify -v "$RELEASE_SIGNED"
    echo "Release APK signed and verified at $RELEASE_SIGNED"
elif [ -f "app/build/outputs/apk/release/app-release.apk" ]; then
    cp -v "app/build/outputs/apk/release/app-release.apk" "$RELEASE_SIGNED"
    echo "Copied release APK to $RELEASE_SIGNED"
fi

echo "All steps completed at: $(date)"
