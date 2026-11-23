-include .env
export

.PHONY: build laptop vm wagons-log station-log

build:
	javac -cp "lib/*" -d build src/rollerCoaster/*.java gen-java/rollerCoaster/*.java

# Laptop: Station im Vordergrund (Dashboard), Wagen-Log in Datei.
# Strg+C beendet beide Prozesse.
laptop: build
	@test -x .venv/bin/python || { echo "FEHLER: .venv fehlt – python3 -m venv .venv && .venv/bin/pip install -r requirements.txt"; exit 1; }
	@.venv/bin/python -c 'import thrift' 2>/dev/null || { echo "FEHLER: thrift fehlt in .venv – .venv/bin/pip install -r requirements.txt"; exit 1; }
	@mkdir -p logs
	@echo "[make] Wagen-Log -> logs/wagons.log"
	@set -e; \
	java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "build:lib/*" rollerCoaster.StationServer \
		--capacity=$(CAPACITY) --port=$(STATION_PORT) --ride-ms=$(RIDE_MS) --wagons=$(WAGONS) \
		--log=logs/station.log & STATION_PID=$$!; \
	sleep 0.5; \
	PYTHONPATH=gen-py .venv/bin/python -u src/python/wagon_runner.py \
		--host=127.0.0.1 --port=$(STATION_PORT) --count=$(WAGONS) \
		--ride-ms=$(RIDE_MS) --boarding-ms=$(BOARDING_MS) \
		--id-offset=$(ID_OFFSET) --start-stagger-ms=$(WAGON_STAGGER_MS) \
		>> logs/wagons.log 2>&1 & WAGON_PID=$$!; \
	trap 'kill $$STATION_PID $$WAGON_PID 2>/dev/null || true; wait 2>/dev/null || true' INT TERM EXIT; \
	wait $$STATION_PID $$WAGON_PID

# VM: Passagiere im Vordergrund; Strg+C beendet Runner und alle Kinder.
vm: build
	@java -cp "build:lib/*" rollerCoaster.PassengerRunner \
		--host=$(STATION_HOST) --port=$(STATION_PORT) \
		--count=$(PASSENGERS) --id-offset=$(ID_OFFSET) --pause-ms=$(PAUSE_MS) \
		--board-min-ms=$(BOARD_MIN_MS) --board-max-ms=$(BOARD_MAX_MS)

wagons-log:
	@tail -f logs/wagons.log

station-log:
	@tail -f logs/station.log
