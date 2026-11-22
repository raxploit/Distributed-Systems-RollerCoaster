-include .env
export

.PHONY: build laptop vm wagons-log station-log

build:
	javac -cp "lib/*" -d build src/rollerCoaster/*.java gen-java/rollerCoaster/*.java

# Laptop: Station im Vordergrund (Dashboard), Wagen-Log in Datei.
# Strg+C beendet beide Prozesse.
laptop: build
	@mkdir -p logs
	@set -e; \
	java -cp "build:lib/*" rollerCoaster.StationServer \
		--capacity=$(CAPACITY) --port=$(STATION_PORT) --log=logs/station.log & STATION_PID=$$!; \
	sleep 0.5; \
	PYTHONPATH=gen-py .venv/bin/python -u src/python/wagon_runner.py \
		--host=127.0.0.1 --port=$(STATION_PORT) --count=$(WAGONS) \
		--ride-ms=$(RIDE_MS) --boarding-ms=$(BOARDING_MS) \
		--id-offset=$(ID_OFFSET) >> logs/wagons.log 2>&1 & WAGON_PID=$$!; \
	trap 'kill $$STATION_PID $$WAGON_PID 2>/dev/null || true; wait 2>/dev/null || true' INT TERM EXIT; \
	wait $$STATION_PID $$WAGON_PID

# VM: Passagiere im Vordergrund; Strg+C beendet Runner und alle Kinder.
vm: build
	@java -cp "build:lib/*" rollerCoaster.PassengerRunner \
		--host=$(STATION_HOST) --port=$(STATION_PORT) \
		--count=$(PASSENGERS) --id-offset=$(ID_OFFSET) --pause-ms=$(PAUSE_MS)

wagons-log:
	@tail -f logs/wagons.log

station-log:
	@tail -f logs/station.log
