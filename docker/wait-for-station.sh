#!/usr/bin/env bash
# Wartet, bis die Station unter STATION_HOST:STATION_PORT erreichbar ist,
# und startet dann den übergebenen Client. Die Clients selbst haben keinen
# Retry (transport.open() schlägt sonst sofort fehl).
set -euo pipefail

HOST=${STATION_HOST:-localhost}
PORT=${STATION_PORT:-9090}
TIMEOUT=${STATION_WAIT_TIMEOUT:-300}
INTERVAL=${STATION_WAIT_INTERVAL:-2}

is_open() {
  python3 - "$HOST" "$PORT" <<'PY'
import socket
import sys

host, port = sys.argv[1], int(sys.argv[2])
try:
    with socket.create_connection((host, port), timeout=1):
        pass
except OSError:
    sys.exit(1)
PY
}

echo "[wait] Warte auf Station ${HOST}:${PORT} ..."
deadline=$(( $(date +%s) + TIMEOUT ))

until is_open; do
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "[wait] Station nach ${TIMEOUT}s nicht erreichbar – breche ab." >&2
    exit 1
  fi
  sleep "$INTERVAL"
done

echo "[wait] Station ist erreichbar – starte Client."
exec "$@"
