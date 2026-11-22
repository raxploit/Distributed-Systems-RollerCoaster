# Roller Coaster

Verteilte Achterbahn-Simulation mit Apache Thrift. **Station und Wagen laufen
nativ auf dem Präsentationslaptop, die Passagiere nativ auf der VM.** Damit
liegen Wagen und Passagiere auf verschiedenen physischen Maschinen, und die
Demo zeigt, dass Thrift Java- und Python-Knoten verbindet.

```
Laptop (echtes Terminal)             VM (Tailnet)
┌────────────────────────┐           ┌────────────────────────┐
│ StationServer (Java)   │<──────────│ PassengerRunner (Java) │
│   Dashboard auf stdout │  Tailnet  │ m Passagier-Prozesse   │
│ wagon_runner (Python)  │           └────────────────────────┘
│   n Wagen-Prozesse     │
│   → 127.0.0.1:9090     │
└────────────────────────┘
```

## Setup

### Laptop (Station + Wagen)

```shell
sudo pacman -S jdk21-openjdk python python-venv
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

### VM (Passagiere)

```shell
sudo pacman -S jdk21-openjdk
```

Auf beiden Systemen Tailscale installieren und im selben Tailnet anmelden. Das
Repo liegt auf beiden Maschinen. Kein Thrift-Compiler nötig: `gen-java/`,
`gen-py/` und `lib/*.jar` liegen im Repo.

## Konfiguration

```shell
cp .env.example .env
```

| Variable | Bedeutung | Default |
|---|---|---|
| `STATION_HOST` | Tailnet-IP des **Laptops** (Ziel der Passagiere) | – |
| `STATION_PORT` | Port der Station | `9090` |
| `CAPACITY` | Sitze pro Wagen `c` | `4` |
| `WAGONS` | Anzahl Wagen auf dem Laptop; bestimmt auch die sortierte Startreihenfolge `1..n` | `4` |
| `RIDE_MS` | **feste** Fahrtdauer in ms (Grundlage des Fortschrittsbalkens) | `20000` |
| `BOARDING_MS` | Türen-zu-Pause nach vollem Wagen in ms | `0` |
| `PASSENGERS` | Anzahl Passagiere auf der VM | `10` |
| `BOARD_MIN_MS` | minimale zufällige Ankunftszeit vor dem Einsteigen | `1000` |
| `BOARD_MAX_MS` | maximale zufällige Ankunftszeit vor dem Einsteigen | `5000` |
| `PAUSE_MS` | Pause nach dem Aussteigen in ms | `0` |
| `ID_OFFSET` | Offset für Client-IDs | `0` |

## Start

### Laptop

```shell
make laptop
```

- kompiliert die Java-Klassen,
- prüft, dass `.venv/bin/python` und das Python-Paket `thrift` vorhanden sind
  (sonst bricht `make` mit einer klaren Meldung ab),
- startet den `StationServer` im Vordergrund → **farbiges Live-Dashboard (4 Hz)**,
- startet `wagon_runner.py` im Hintergrund; dessen Ausgabe geht nach
  `logs/wagons.log`, damit das Dashboard nicht gestört wird,
- **Strg+C beendet Station und Wagen gemeinsam.**

Wagen-Aktivität in einem zweiten Terminal:

```shell
make wagons-log
```

### VM

```shell
make vm
```

- kompiliert die Java-Klassen,
- startet `PassengerRunner` im Vordergrund; die `[P1]`-Ausgaben erscheinen direkt,
- **Strg+C beendet den Runner und alle Passagier-Prozesse.**

Die VM erreicht die Station über `STATION_HOST:STATION_PORT`. Port 9090 muss
auf dem Laptop aus dem Tailnet erreichbar sein (z. B. `sudo ufw allow from
100.64.0.0/10 to any port 9090`).

## Logs und Dashboard

Die maschinenlesbaren `[Station]`- und `[METRIC]`-Zeilen stehen unverändert in
`logs/station.log`:

```shell
make station-log
```

Das Dashboard erscheint nur auf dem Laptop, weil dort ein echtes TTY vorhanden
ist. Der Titel ist einfach `ROLLER COASTER`. Es zeigt `CONFIG` (mit `[PORT …]`,
`[CAPACITY …]`, `[WAGONS …]`, `[REFRESH …]`), `LOG`, `UPTIME`, `LINK`
(`SERVER`/`WAGON`/`PASSENGERS` mit Anzahl der gesehenen Knoten), `WAITING`,
`PLATFORM`, `TRACK`, `COUNTERS`, `RIDES/PAX`, `LAST 5` und `HINT`.
Auf `TRACK` ersetzt ein echter Fahrtfortschritt (`━━━━╸── 62 %`) den sonst immer
vollen Sitzplatzbalken: Die Station merkt sich die Abfahrtszeit und rechnet sie
gegen `RIDE_MS`. Die Passagiere kommen zufällig alle 1–5 s an, sodass sich
`WAITING` und der Bahnsteig sichtbar füllen. Die Startreihenfolge der Wagen ist
deterministisch `1, 2, 3, 4` – die Station sortiert die `platformQueue` in der
Startphase, bis alle `WAGONS` gesehen wurden. Danach gilt wieder normales FIFO.
`RIDES/PAX` zeigt oben `min`, `max` (jeweils mit Passagier-ID), `avg` und
`spread = max - min`; darunter 5 fest ausgerichtete Kacheln pro Zeile im Format
`[P1  29 ↑2]` – die Zahl ist die Fahrtenzahl, der Pfeil die gerundete Abweichung
vom Durchschnitt.

Die TTY-Erkennung prüft stdout (`test -t 1`), daher erscheint das Dashboard auch
dann, wenn die Station vom Makefile im Hintergrund gestartet wird. Die Breite
wird automatisch aus `COLUMNS` bzw. `stty size` übernommen (Fallback 96 Spalten);
`--width=N` überschreibt sie. Farben gibt es automatisch; aus mit
`NO_COLOR=1` oder `--no-color`. Das Dashboard nutzt den alternativen Screen:
Nach Strg+C steht der alte Terminalinhalt wieder da.

Für einen nativen Serverstart ohne Makefile:

```shell
javac -cp "lib/*" -d build src/rollerCoaster/*.java gen-java/rollerCoaster/*.java
java -Dstdout.encoding=UTF-8 -cp "build:lib/*" rollerCoaster.StationServer --capacity=4 --port=9090 --ride-ms=20000 --wagons=4
```

Weitere Server-Flags: `--log=pfad`, `--ride-ms=N`, `--wagons=N`, `--width=N`, `--no-dashboard`, `--color` /
`--no-color`. Ohne TTY oder mit `--no-dashboard` gehen die Log-Zeilen direkt
auf stdout.
