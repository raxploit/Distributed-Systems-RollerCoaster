#!/usr/bin/env bash
# Erzeugt compose.clients.yaml mit genau einem Service pro Client.
# --scale würde allen Replikaten dieselbe Kommandozeile und damit dieselbe
# Client-ID geben; explizite Services halten die IDs eindeutig und lesbar.
set -euo pipefail
cd "$(dirname "$0")/.."

STATION_HOST=${STATION_HOST:-}
STATION_PORT=${STATION_PORT:-9090}
WAGONS=${WAGONS:-4}
PASSENGERS=${PASSENGERS:-10}
RIDE_MS=${RIDE_MS:-3000}
ID_OFFSET=${ID_OFFSET:-0}
OUT=compose.clients.yaml

if [ -z "$STATION_HOST" ]; then
  echo "STATION_HOST ist nicht gesetzt – siehe .env.example." >&2
  exit 1
fi

for var in STATION_PORT WAGONS PASSENGERS RIDE_MS ID_OFFSET; do
  value=${!var}
  if ! [[ "$value" =~ ^[0-9]+$ ]]; then
    echo "$var muss eine Zahl sein (ist: '$value')." >&2
    exit 1
  fi
done

emit_wagon() {
  local index=$1 id=$2
  cat <<EOF
  wagon-${index}:
    build: .
    image: rollercoaster:local
    environment:
      STATION_HOST: "${STATION_HOST}"
      STATION_PORT: "${STATION_PORT}"
    command: ["docker/wait-for-station.sh", "python3", "-u", "src/python/wagon_client.py", "${STATION_HOST}", "${STATION_PORT}", "${id}", "${RIDE_MS}"]
    init: true
    restart: on-failure
    stop_grace_period: 5s
EOF
}

emit_passenger() {
  local index=$1 id=$2
  cat <<EOF
  passenger-${index}:
    build: .
    image: rollercoaster:local
    environment:
      STATION_HOST: "${STATION_HOST}"
      STATION_PORT: "${STATION_PORT}"
    command: ["docker/wait-for-station.sh", "java", "-cp", "build:lib/*", "rollerCoaster.PassengerClient", "${STATION_HOST}", "${STATION_PORT}", "${id}"]
    init: true
    restart: on-failure
    stop_grace_period: 5s
EOF
}

{
  echo "# Generiert von docker/generate-compose.sh – nicht von Hand bearbeiten."
  echo "services:"
  for ((i = 1; i <= WAGONS; i++)); do
    emit_wagon "$i" "$((ID_OFFSET + i))"
  done
  for ((i = 1; i <= PASSENGERS; i++)); do
    emit_passenger "$i" "$((ID_OFFSET + i))"
  done
} > "$OUT"

echo "${OUT} geschrieben: ${WAGONS} Wagen, ${PASSENGERS} Passagiere (IDs ab $((ID_OFFSET + 1)))."
