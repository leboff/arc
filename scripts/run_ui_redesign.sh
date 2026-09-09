#!/usr/bin/env bash
set -eo pipefail

export IS_SANDBOX=1
export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
export ANDROID_HOME="/root/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

REPO_DIR="/root/daydream-vr-player"
cd "$REPO_DIR"

echo "=== Arc VR Player UI Redesign Pipeline ==="
echo "Started at: $(date)"

# 1. Quota Check Loop
echo "Checking Claude CLI quota..."
for i in {1..10}; do
    if claude -p "echo ready" > /dev/null 2>&1; then
        echo "Claude CLI is alive and authenticated!"
        break
    else
        echo "Quota still locking or network glitch (attempt $i/10). Waiting 60s..."
        sleep 60
    fi
done

# 2. Stage 1: Claude Opus Review Pass
echo "Starting Stage 1: Claude Opus Architectural Review..."
claude -p --model opus --dangerously-skip-permissions --max-turns 30 < docs/OPUS_REVIEW_PROMPT.md > docs/opus_review.log 2>&1 || {
    echo "Warning: Opus review pass encountered non-zero exit. Checking log:"
    tail -n 20 docs/opus_review.log || true
}

echo "Opus review finished. Git status:"
git status --short

# 3. Stage 2: Claude Sonnet Implementation Pass
echo "Starting Stage 2: Claude Sonnet Build & Implementation..."
claude -p --model sonnet --dangerously-skip-permissions --max-turns 125 < docs/SONNET_BUILD_PROMPT.md > docs/sonnet_build.log 2>&1 || {
    echo "Warning: Sonnet build pass encountered non-zero exit. Checking log:"
    tail -n 20 docs/sonnet_build.log || true
}

# 4. Independent Verification Pass
echo "Running independent test and build verification..."
./gradlew testDebugUnitTest --no-daemon
./gradlew assembleRelease --no-daemon

RELEASE_UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
RELEASE_SIGNED="/root/Arc-v0.3.0-release.apk"

if [ -f "$RELEASE_UNSIGNED" ]; then
    echo "Signing release APK..."
    /root/android-sdk/build-tools/35.0.0/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --out "$RELEASE_SIGNED" "$RELEASE_UNSIGNED"
    /root/android-sdk/build-tools/35.0.0/apksigner verify -v "$RELEASE_SIGNED"
    echo "Release APK signed and verified at $RELEASE_SIGNED"
fi

echo "Pipeline completed at: $(date)"
