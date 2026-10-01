#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/tools/android-env.sh"
APK="${1:-$ROOT_DIR/dist/balancing-robot-debug.apk}"

if [[ ! -f "$APK" ]]; then
  echo "APK absent : $APK" >&2
  echo "Lancer d'abord tools/android-build-debug.sh" >&2
  exit 2
fi

if ! adb get-state >/dev/null 2>&1; then
  echo "Aucun appareil Android adb disponible." >&2
  echo "Activer le débogage USB (ou adb over Wi-Fi), puis vérifier avec : adb devices" >&2
  exit 3
fi

adb install -r "$APK"
