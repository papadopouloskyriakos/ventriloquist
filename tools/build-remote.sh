#!/usr/bin/env bash
# Build on a remote machine that has the Android SDK: rsync the tree there, run Gradle,
# copy the APKs back. Nothing about a specific host lives in this script.
#
#   BUILD_HOST=buildbox BUILD_USER=me [BUILD_KEY=~/.ssh/id] [ANDROID_SDK=/opt/android-sdk] \
#   [BUILD_DIR=ventriloquist-build] tools/build-remote.sh [gradle tasks...]
#
# Default tasks: assembleDebug testDebugUnitTest
set -euo pipefail

: "${BUILD_HOST:?set BUILD_HOST to the build machine address}"
BUILD_USER="${BUILD_USER:-$USER}"
BUILD_DIR="${BUILD_DIR:-ventriloquist-build}"
ANDROID_SDK="${ANDROID_SDK:-/opt/android-sdk}"
TASKS=("$@")
if [ ${#TASKS[@]} -eq 0 ]; then
    TASKS=(assembleDebug testDebugUnitTest)
fi

SSH=(ssh -o BatchMode=yes)
if [ -n "${BUILD_KEY:-}" ]; then
    SSH+=(-i "$BUILD_KEY")
fi
REMOTE="$BUILD_USER@$BUILD_HOST"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo ">> sync $ROOT -> $REMOTE:$BUILD_DIR"
rsync -az --delete -e "${SSH[*]}" \
    --exclude '.git/' --filter=':- .gitignore' \
    "$ROOT/" "$REMOTE:$BUILD_DIR/"

echo ">> gradle ${TASKS[*]}"
"${SSH[@]}" "$REMOTE" "cd '$BUILD_DIR' && chmod +x gradlew && ANDROID_HOME='$ANDROID_SDK' ./gradlew --console=plain ${TASKS[*]}"

echo ">> fetch APKs"
mkdir -p "$ROOT/app/build/outputs/apk"
rsync -az -e "${SSH[*]}" "$REMOTE:$BUILD_DIR/app/build/outputs/apk/" "$ROOT/app/build/outputs/apk/" || true
find "$ROOT/app/build/outputs/apk" -name '*.apk' -print
