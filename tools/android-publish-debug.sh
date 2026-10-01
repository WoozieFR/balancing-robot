#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
PORT="${BALANCING_ROBOT_HTTP_PORT:-8765}"
HTTP_LOG="${TMPDIR:-/tmp}/balancing-robot-http.log"
TUNNEL_LOG="${TMPDIR:-/tmp}/balancing-robot-tunnel.log"
HTTP_SESSION="balancing-robot-http"
TUNNEL_SESSION="balancing-robot-tunnel"

if [[ ! -f "$DIST_DIR/balancing-robot-debug.apk" ]]; then
  echo "APK absent : lancer d'abord tools/android-build-debug.sh" >&2
  exit 2
fi

if ! screen -list | rg -q "[.]$HTTP_SESSION"; then
  : > "$HTTP_LOG"
  screen -dmS "$HTTP_SESSION" bash -lc "exec python3 -m http.server '$PORT' --bind 0.0.0.0 --directory '$DIST_DIR' >'$HTTP_LOG' 2>&1"
fi

if ! screen -list | rg -q "[.]$TUNNEL_SESSION"; then
  : > "$TUNNEL_LOG"
  screen -dmS "$TUNNEL_SESSION" bash -lc "exec cloudflared tunnel --url 'http://127.0.0.1:$PORT' >'$TUNNEL_LOG' 2>&1"
fi

URL=""
for _ in $(seq 1 20); do
  URL="$(rg -o 'https://[a-z0-9-]+\.trycloudflare\.com' "$TUNNEL_LOG" 2>/dev/null | tail -1 || true)"
  [[ -n "$URL" ]] && break
  sleep 1
done

if [[ -z "$URL" ]]; then
  echo "Tunnel non disponible ; consulter $TUNNEL_LOG" >&2
  exit 1
fi

printf '%s/balancing-robot-debug.apk\n' "$URL" | tee "$DIST_DIR/latest-debug-url.txt"
