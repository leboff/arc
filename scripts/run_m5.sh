#!/usr/bin/env bash
set -eo pipefail

export IS_SANDBOX=1
export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
export ANDROID_HOME="/root/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

REPO_DIR="/root/daydream-vr-player"
cd "$REPO_DIR"

echo "=== Arc VR Player: Milestone 5 Widgets Sprint ==="
echo "Started at: $(date)"

claude -p --model sonnet --dangerously-skip-permissions --max-turns 125 < docs/M5_WIDGETS_PROMPT.md > docs/m5_build.log 2>&1 || {
    echo "Warning: Sonnet run finished with non-zero exit code. Log tail:"
    tail -n 25 docs/m5_build.log || true
}

echo "Sonnet pass finished. Git status:"
git status --short

echo "Running verification unit tests..."
./gradlew testDebugUnitTest --no-daemon

echo "M5 Sprint complete at: $(date)"
