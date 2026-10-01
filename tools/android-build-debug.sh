#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/tools/android-env.sh"

DIST_DIR="$ROOT_DIR/dist"
APK_SOURCE="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
APK_DEST="$DIST_DIR/balancing-robot-debug.apk"

mkdir -p "$DIST_DIR"
cd "$ROOT_DIR"

./gradlew --no-daemon testDebugUnitTest assembleDebug
cp "$APK_SOURCE" "$APK_DEST"

GIT_REV="$(git rev-parse --short HEAD 2>/dev/null || printf '%s' 'uncommitted')"
SHA256="$(sha256sum "$APK_DEST" | awk '{print $1}')"
cat > "$DIST_DIR/balancing-robot-debug.build-info" <<EOF
artifact=balancing-robot-debug.apk
git_revision=$GIT_REV
sha256=$SHA256
built_at=$(date --iso-8601=seconds)
EOF

printf 'APK: %s\nSHA256: %s\n' "$APK_DEST" "$SHA256"
