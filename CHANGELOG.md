# CHANGELOG – Logik, Metriken, Architektur

Stand: 2026-11-22

Zweck dieser Datei: alles festhalten, was **nicht** Optik ist (also nicht Dashboard-Layout,
Farben, Labels, Rahmen). Damit kann ich auf Anfrage schnell und korrekt zusammenfassen,
was fachlich am Projekt geändert wurde.

**Kurzfassung:** Die Achterbahn ist ein zentraler Java-Monitor (`StationHandler`) mit
Thrift-RPCs. Ein Wagen fährt erst voll ab, Passagiere werden FIFO bedient, die Strecke
erzwingt Abfahrtsreihenfolge. Metriken laufen ohne IDL-Änderung über Zähler im Handler
und eine 10-s-Periode in `logs/station.log`. **Aktueller Betrieb (seit 2026-11-22):
alles nativ** – `StationServer` + `wagon_runner.py` auf dem Laptop, `PassengerRunner`
auf der VM. Damit sind Wagen und Passagiere auf verschiedenen physischen Maschinen,
und das Dashboard läuft wieder im echten Terminal. Die Sprachmischung Java/Python
bleibt bewusst erhalten (Thrift-Demo).

---

## 1. Architektur – historischer Zustand (vor dem 2026-11-20)

```
Laptop (nativ)                 VM (Docker, Tailnet)
──────────────────             ──────────────────────────────
StationServer :9090  <────────  wagon-1..4   (Python)
  StationHandler                 passenger-1..10 (Java)
  StationLog → logs/station.log
```

- Ein **einziger** `StationHandler` pro Server (`StationServer.java:51`) ist der gemeinsame
  Monitor für alle Clients. Nicht ein Handler pro Client.
- Transport: Apache Thrift, `TBinaryProtocol`, `TServerSocket` auf Port **9090** (im Server
  hartcodiert), `TThreadPoolServer` mit `minWorkerThreads(16)` – ein Thread pro blockierendem
  Client-Call (`m + n` Verbindungen).
- Kein Thrift-Compiler nötig: `gen-java/`, `gen-py/` und `lib/*.jar` liegen im Repo.
- IDs sind stabil: `wagon-i` / `passenger-i` mit `ID_OFFSET + i`. Kein `--scale`, weil alle
  Replikate sonst dieselbe Kommandozeile und damit dieselbe ID hätten.

## 2. Stationslogik (`src/rollerCoaster/StationHandler.java`)

Alle Methoden sind `synchronized` auf derselben Instanz; blockierende Clients warten in
`wait()` und werden nach Zustandsänderungen per `notifyAll()` geweckt und prüfen ihre
Bedingung erneut. `waitUninterruptibly()` fängt `InterruptedException` und setzt das
Interrupt-Flag wieder.

Zustandsstrukturen:

| Feld | Bedeutung |
|---|---|
| `Deque<Integer> waitingPassengers` | FIFO-Warteschlange → keine Starvation |
| `Deque<Integer> platformQueue` | Wagen am Bahnsteig in Ankunftsreihenfolge; nur vorne darf geladen werden |
| `Deque<Integer> track` | Wagen auf der Strecke in Abfahrtsreihenfolge → kein Überholen |
| `Map<Integer, List<Integer>> seats` | Passagiere pro Wagen |
| `Map<Integer, Integer> passengerWagon` | Zuordnung Passagier → Wagen (damit dieselben c Leute aussteigen) |
| `Set<Integer> rideOver` | Wagen, dessen Fahrt vorbei ist; Passagiere dürfen aussteigen |
| `Map<Integer, Integer> rideCount` | Fahrten pro Passagier (`TreeMap`, nur für Fairness/Demo) |

Ablauf der RPCs (Reihenfolge aus `rollerCoaster.thrift`, IDL unverändert):

1. `arrive(w)`: `seats.put(w, [])`, `platformQueue.addLast(w)`, `notifyAll()`. Warten,
   bis `w == platformQueue.peekFirst()` **und** `seats[w].size() == capacity`.
2. `i_am_boarding(p)`: `waitingPassengers.addLast(p)`. Warten, bis ein Wagen am Bahnsteig
   noch Platz hat **und** `p == waitingPassengers.peekFirst()` (FIFO). Dann `pollFirst()`,
   in `seats[w]` eintragen, `passengerWagon[p] = w`, `boardings++`, `notifyAll()`, `w` zurückgeben.
3. `departure(w)`: `platformQueue.pollFirst()` (Bahnsteig frei), `track.addLast(w)`,
   `departures++`, `notifyAll()`.
4. `wait_for_ride_end(p)`: warten, bis `rideOver.contains(passengerWagon[p])`.
5. `ride_finished(w)`: warten, bis `w == track.peekFirst()` → erzwingt Reihenfolge trotz
   unterschiedlicher Fahrtdauer. Dann `rideOver.add(w)`, `notifyAll()` (weckt Passagiere),
   warten bis `seats[w].isEmpty()`, dann `rideOver.remove(w)`, `track.pollFirst()`,
   `completedRides++`, `notifyAll()` (nächster Wagen darf entladen).
6. `i_am_disembarking(p)`: `passengerWagon.remove(p)`, aus `seats[w]` entfernen,
   `rideCount.merge(p, 1, Integer::sum)`, `notifyAll()`.

Invarianten: ein Wagen verlässt den Bahnsteig nur mit genau `c` Passagieren; nie mehr als
`c`; jeder wartende Passagier kommt irgendwann dran (FIFO); Wagen überholen sich nie;
nach `ride_finished` steigen genau die zuvor eingestiegenen Passagiere aus.

Zusätzlich: `recentEvents` speichert die letzten 5 Ereignisse mit laufender Nummer
(`#0001 …`, `String.format(Locale.ROOT, "#%04d %s", …)`). Das ist die Datenquelle für den
Ticker – das Rendering selbst ist Optik und hier nicht dokumentiert.

## 3. Metriken und Log-Datei

- Neue Zähler im Handler: `boardings`, `departures`, `completedRides` (vorher nicht vorhanden).
- `stats()` gibt exakt diese Zeile zurück (Format **unverändert**, maschinenlesbar):

  ```
  [METRIC] c=%d waiting=%d platform=%d track=%d boardings=%d departures=%d rides=%d
  ```

- `StationServer` loggt beim Start einmal `stats()` und danach alle **10 s** per
  `ScheduledExecutorService` (Daemon-Thread).
- `StationLog.java` (neu) schreibt UTF-8, `CREATE + APPEND`, `flush()` nach jeder Zeile
  (damit `tail -f` sofort etwas zeigt) und `close()` via Shutdown-Hook.
- `[Station] …`-Zeilen behalten ihr altes deutsches Format inkl. der Fahrten-Map
  (`| Fahrten: {1=11, …}`) – bewusst nicht angefasst.
- `echoToStdout` ist **nur** aktiv, wenn kein TTY vorhanden ist oder `--no-dashboard`
  gesetzt wurde. Dann stehen die Maschinenzeilen auf stdout; im Dashboard-Modus gehört
  stdout ausschließlich der View.
- `snapshot()` liefert einen unveränderlichen, tief kopierten `Status`-Record
  (Kapazität, Warteschlange, Bahnsteig, Strecke, Sitzplätze, Fahrtenzahlen, Ereignisse,
  Zähler). Die View greift nie auf Live-Collections zu.

## 4. Server-Betrieb (`src/rollerCoaster/StationServer.java`)

Defaults: `port = 9090`, `capacity = 4`, `width = 96`, `logPath = logs/station.log`,
`dashboard = true`, `colorFlag = true`.

| Argument | Wirkung (logisch) |
|---|---|
| nacktes `123` | Kapazität `c` (positionsabhängig, derzeit ohne `--capacity=`) |
| `--log=pfad` | Ziel der Log-Datei; Elternverzeichnisse werden angelegt |
| `--no-dashboard` / `--dashboard` | Log-Zeilen auf stdout statt Dashboard |
| `--width=N` | Dashboard-Breite (Optik, aber Flag-Parsing hier) |
| `--no-color` / `--color`, `NO_COLOR` | Farb-Flag; `NO_COLOR` gewinnt |
| unbekannt | Fehlermeldung auf stderr + Return |

TTY-Erkennung: `System.console() != null && TERM != "dumb"` → `interactive`.
Ohne TTY (z. B. Redirect in eine Datei) werden automatisch die Log-Zeilen
ausgegeben, damit keine ANSI-Codes in Dateien/Redirects landen.

## 5. Clients

- **`src/python/wagon_client.py`** (Python, in der VM): `socket.setTimeout(None)`, weil
  `arrive()`/`ride_finished()` lange blockieren. Schleife:
  `arrive → departure → sleep(RIDE_MS + random(0,2000)) → ride_finished`.
  Fahrtdauer kommt aus `sys.argv[4]` (`RIDE_MS`, Default 3000). `rides`-Zähler im
  stdout-Log ergänzt. `transport.close()` in `finally`.
- **`src/rollerCoaster/PassengerClient.java`** (Java): `i_am_boarding →
  wait_for_ride_end → i_am_disembarking → sleep(500)`. Ein Prozess pro Passagier-ID.
- **`docker/wait-for-station.sh`** (neu): wartet per Python-Socket auf
  `STATION_HOST:STATION_PORT`, bevor der Client startet. Grund: die Clients haben kein
  Retry, `transport.open()` schlägt sonst sofort fehl. Zeitfenster konfigurierbar über
  `STATION_WAIT_TIMEOUT` (300 s) und `STATION_WAIT_INTERVAL` (2 s).
- `restart: on-failure`, `init: true`, `stop_grace_period: 5s` in jedem generierten Service.

## 6. Docker / Compose / Make

- **`Dockerfile`** (neu, Multi-Stage):
  - Build: `eclipse-temurin:21-jdk`, kompiliert `src/rollerCoaster/*.java` +
    `gen-java/rollerCoaster/*.java` mit `lib/*` nach `build/`.
  - Runtime: `eclipse-temurin:21-jre` + `python3-venv`, `pip install -r requirements.txt`
    (Thrift), `PYTHONPATH=/app/gen-py`, kopiert `build`, `lib`, `gen-py`, `src/python`,
    `docker`.
  - **Ein** Image für beide Rollen; aktuell enthält es beide Runtimes (JRE + Python).
    Bei 14 Containern ist das insgesamt schwer – ein bekannter Optimierungspunkt.
- **`docker/generate-compose.sh`** (neu): erzeugt `compose.clients.yaml` (nicht von Hand
  bearbeiten!). Pflicht: `STATION_HOST`; numerische Validierung von `STATION_PORT`,
  `WAGONS`, `PASSENGERS`, `RIDE_MS`, `ID_OFFSET`. Emittiert `wagon-1..n` und
  `passenger-1..m` mit festen IDs. Wagen-Command nutzt `python3 -u src/python/wagon_client.py`,
  Passagier-Command `java … rollerCoaster.PassengerClient`.
- **`compose.yaml`**: `station`-Service nur mit `profiles: [local]`; Port nur auf
  `127.0.0.1` gebunden. Im verteilten Betrieb läuft die Station nativ, nicht hier.
- **`Makefile`**: `COMPOSE = docker compose -f compose.yaml -f compose.clients.yaml`;
  Ziele `gen`, `up` (`--build`), `down`, `logs`, `ps`, `config`. `.env` wird inkludiert
  und exportiert; CLI-Variablen überschreiben `.env` (z. B. `make up WAGONS=6`).
- **`.dockerignore` / `.gitignore`** ergänzt: `build/`, `logs/`, `.env`,
  `compose.clients.yaml`, `.venv/`, `__pycache__`, Start-Skripte, README/Dockerfile.
- **Windows-Only-Startskripte entfernt:** `StartPassengers.cmd`, `StartPassengers.ps1`,
  `StartWagons.cmd`, `StartWagons.ps1`. Sie waren veraltete Lokalstart-Helfer für
  `localhost` ohne `wait-for-station.sh` und wurden nirgends referenziert (weder README,
  Makefile, Compose noch Dockerfile). Die POSIX-Pendants `StartPassengers.sh` und
  `StartWagons.sh` wurden später ebenfalls entfernt (siehe Abschnitt 11).

## 7. Konfiguration

`.env` und `.env.example` enthalten jetzt **nur Client-Seite**:

```
STATION_HOST=100.x.y.z   # Tailnet-IP des Laptops
STATION_PORT=9090
WAGONS=4
PASSENGERS=10
RIDE_MS=3000
ID_OFFSET=0
```

- `CAPACITY` wurde aus beiden Dateien entfernt: der Generator nutzt es nicht, die echte
  Kapazität kommt aus dem Startargument der Station. Ein Rest `${CAPACITY:-4}` steht noch
  im `local`-Profil in `compose.yaml` und fällt auf 4 zurück (funktioniert, ist aber
  irreführend).
- README: Setup/config/server/container auf Arch Linux reduziert; Parameter-Tabelle ergänzt.

## 8. Bewusst NICHT geändert

- `rollerCoaster.thrift`, `gen-java/`, `gen-py/`, `lib/` – IDL und generierter Code.
- Format der `[Station]`- und `[METRIC]`-Zeilen.
- Die Synchronisationsinvarianten selbst (nur Zähler/Snapshot/Events kamen hinzu).

## 9. Offene Punkte / Entscheidungen

Die alten Punkte 1–3 (TASK.md-Maschinentrennung, 3-Container-Plan,
`--capacity=`/`--port=`) sind mit dem Update vom 2026-11-20 erledigt – siehe
Abschnitt 11. Es bleibt offen:

1. Keine Validierung von `c < m`.
2. `minWorkerThreads(16)` könnte bei deutlich mehr als ~16–30 gleichzeitig blockierten
   Clients eng werden; für die Demo (4+10) reicht es.
3. Echtes Live-Ändern von Parametern während des Laufs bräuchte einen zusätzlichen
   Control-RPC; aktuell gibt es nur Startzeit-Env.

## 10. Prüfstand / Einschränkung

- `javac`/`java` 21 sind inzwischen verfügbar: Java kompiliert sauber
  (`javac -cp "lib/*" -d build src/rollerCoaster/*.java gen-java/rollerCoaster/*.java`).
- Smoke-Test durchgeführt: 2 Python-Wagen + 3 Java-Passagiere gegen einen Server mit
  `c=2`; Boarding, Abfahrten, Aussteigen und Zähler liefen korrekt. Zweiter Test mit
  `wagon_runner.py` und `PassengerRunner.java`: beide starten ihre Kinder, und nach
  `SIGTERM` bleiben keine Client-Prozesse übrig.
- `python3 -m py_compile` für alle Python-Dateien OK; `make -n laptop/vm` zeigt die
  erwarteten Kommandos.
- Strg+C-Tests: `timeout -s INT` auf `make laptop` (2 Wagen) und `make vm` (2 Passagiere)
  beendet in beiden Fällen Runner und alle Kinder ohne Restprozesse.
- Docker-Artefakte sind inzwischen entfernt; Docker wird nicht mehr benötigt.
- Alle Änderungen liegen nach aktuellem Stand uncommitted auf Platte; kein Commit/Push.

## 11. Update 2026-11-20: 3-Container-Topologie (historisch, ersetzt durch 12)

Dieser Abschnitt ersetzt die Abschnitte 1, 6 und 7 für den aktuellen Betrieb.

### Neue Aufteilung

```
VM (Tailnet-IP)                      Laptop
┌──────────────────────────┐         ┌──────────────────────────┐
│ station  (Java, :9090)   │<────────│ passengers (Java)        │
│ wagons   (Python)        │ Tailnet│  m Passagier-Prozesse    │
│   n Wagen-Prozesse       │         └──────────────────────────┘
└──────────────────────────┘
```

- `station` und `wagons` sind **zwei** Container auf der VM, `passengers` ist ein
  Container auf dem Laptop. Wagen und Passagiere sind damit auf verschiedenen
  physischen Maschinen → `TASK.md` jetzt erfüllt.
- **Sprachen bewusst gemischt:** Station + Passagiere Java, Wagen Python. Der
  Thrift-namespace `py`/`java` und die bestehenden Clients bleiben erhalten.

### Neue/geänderte Dateien

| Datei | Änderung |
|---|---|
| `Dockerfile` | zwei Targets: `java` (JRE + `build/` + `lib/`) und `python` (Python-Slim + `thrift` + `gen-py/`). Kein JRE in Wagen, kein Python in Station/Passagieren. |
| `StationServer.java` | echte Flags `--capacity=N` und `--port=N`; nacktes Zahlenargument bleibt Alias für `capacity`. |
| `PassengerClient.java` | optionales 4. Argument `pauseMs` (Default 500) statt hartcodierter Pause. |
| `src/python/wagon_client.py` | optionales 5. Argument `boarding_ms`: Pause nach `arrive()` (voller Wagen) vor `departure()`. |
| `src/rollerCoaster/PassengerRunner.java` | neu: wartet auf `STATION_HOST:PORT`, startet `PASSENGERS` Java-Subprocesse mit fester ID, `inheritIO()`, Restart bei Crash, Shutdown-Hook beendet alle Kinder. |
| `src/python/wagon_runner.py` | neu: gleiche Aufgabe für `WAGONS` Python-Wagen (Socket-Retry, Subprocesse, SIGTERM-Handling). |
| `compose.yaml` | jetzt `station` + `wagons` auf der VM; Port `0.0.0.0:9090`, Log-Volume `./logs:/app/logs`, `depends_on: [station]`. |
| `compose.laptop.yaml` | neu: nur `passengers`, verbindet zu `${STATION_HOST}` (Tailnet-IP der VM). |
| `Makefile` | Steuerung vom Laptop: `vm-up`/`vm-down` per SSH, `pax-up`/`pax-down` lokal, `up`/`down` für beides; `VM_HOST` defaultet auf `STATION_HOST`. |
| `.env`, `.env.example` | `STATION_HOST` = VM-IP (nicht mehr Laptop-IP); `CAPACITY` zurück als VM-Parameter; neu `BOARDING_MS`, `PAUSE_MS`, `VM_USER`, `VM_REPO_DIR`. |
| `README.md` | komplett auf die 3-Container-Topologie umgestellt. |

### Entfernt

`docker/generate-compose.sh`, `compose.clients.yaml`, `docker/wait-for-station.sh`,
`StartPassengers.sh`, `StartWagons.sh` (und zuvor schon die vier Windows-Dateien).
Die Retry-Logik steckt jetzt in `PassengerRunner.java` bzw. `wagon_runner.py`.

### Parameter (Startzeit-Env, keine Live-Änderung)

- VM: `CAPACITY`, `WAGONS`, `RIDE_MS`, `BOARDING_MS`, `STATION_PORT`, `ID_OFFSET`.
- Laptop: `PASSENGERS`, `PAUSE_MS`, `ID_OFFSET`, `STATION_HOST`, `STATION_PORT`.

Die Synchronisationslogik in `StationHandler` und das `[METRIC]`-Format sind
**unverändert**. Jeder Client bleibt ein eigener Prozess mit eigener Thrift-Verbindung;
die Container fassen nur mehrere Knoten pro Maschine zusammen.

## 12. Update 2026-11-22: zurück zu nativen Prozessen (Topologie aktuell)

Dieser Abschnitt ersetzt Abschnitt 11. Alle Docker-Artefakte wurden entfernt.

### Neue Topologie

```
Laptop (echtes Terminal)             VM (Tailnet)
┌────────────────────────┐           ┌────────────────────────┐
│ StationServer (Java)   │<──────────│ PassengerRunner (Java) │
│ wagon_runner (Python)  │  :9090    │ m Passagier-Prozesse   │
│ n Wagen-Prozesse       │           └────────────────────────┘
└────────────────────────┘
```

- `STATION_HOST` ist wieder die **Tailnet-IP des Laptops** (`100.67.237.25`).
- Laptop: Station im Vordergrund → Dashboard sichtbar; Wagen laufen nativ, ihre
  Ausgabe geht nach `logs/wagons.log`, damit das Dashboard nicht überschrieben wird.
- VM: Passagiere laufen nativ via `PassengerRunner.java`, Ausgabe im Terminal.
- Wagen (Laptop) und Passagiere (VM) bleiben auf verschiedenen physischen Maschinen →
  `TASK.md` weiterhin erfüllt.

### Makefile

| Ziel | Verhalten |
|---|---|
| `make build` | kompiliert alle Java-Klassen |
| `make laptop` | kompiliert, startet Station im Vordergrund und Wagen im Hintergrund; `trap` auf `INT`/`TERM`/`EXIT` beendet beide bei Strg+C; kurze Verzögerung verhindert Retry-Lärm beim Wagen-Start |
| `make vm` | kompiliert, startet `PassengerRunner` im Vordergrund; Strg+C beendet Runner und alle Kinder |
| `make wagons-log` | `tail -f logs/wagons.log` |
| `make station-log` | `tail -f logs/station.log` |

### Entfernt

`Dockerfile`, `compose.yaml`, `compose.laptop.yaml`, `.dockerignore` und alle
vorherigen Docker-Makefile-Ziele. `.env` enthält keine `VM_*`-Variablen mehr.

### Unverändert

`StationHandler`-Monitor, `[METRIC]`-Format, `StationLog`, IDL, generierter Code,
`PassengerClient.java`, `wagon_client.py`, Runner-Logik. `ConsoleView` wurde in
Abschnitt 13 weiter angepasst.

## 13. Update 2026-11-22: Dashboard-Politur (aktuell)

### Header

- Titel nur noch `ROLLER COASTER` (kein Port, kein `.`/`:`). Port bleibt in `CONFIG`.
- `CONFIG` mit Klammern pro Wert: `[PORT 9090] [CAPACITY 4] [WAGONS 4] [REFRESH 4 Hz]`.
  `COLORS` und `WIDTH` werden nicht mehr angezeigt.
- `LOG` ist eine eigene Kategorie und zeigt nur die letzten zwei Pfadsegmente
  (`logs/station.log`).
- `UPTIME` bleibt; neu ist `LINK` mit `SERVER ● online`, `WAGON ● online (n)` und
  `PASSENGERS ● online (n)`. Die Zahlen kommen ausschließlich aus dem bestehenden
  `Status`: Wagen aus `seats.keySet()`, Passagiere aus `waiting`, Sitzplatz-IDs
  und `rideCount`. Kein Heartbeat, kein IDL-/Handler-Change.
- `PASSENGERS` korrekt geschrieben.

### Breite und Terminalverhalten

- Breite zunächst automatisch aus `COLUMNS`/`stty`; in Abschnitt 17 auf feste
  100 Spalten umgestellt (siehe dort). `--width=N` bleibt als Override.
- Alternativer Screen (`\033[?1049h` / `\033[?1049l`): nach Strg+C ist der alte
  Terminalinhalt erhalten. `reset()` stellt außerdem Farben und Cursor wieder her.
- TTY-Erkennung nicht mehr über `System.console()`, sondern über `test -t 1`
  (stdout). Ursache: `make laptop` startet die Station mit `&`; dadurch ist stdin
  `/dev/null` und `System.console()` null, obwohl stdout weiterhin das Terminal
  ist. Das war die Ursache für „kein Dashboard“ trotz laufender Wagen.
- Ohne TTY unverändert: keine ANSI-Codes, `[Station]`/`[METRIC]` auf stdout.

### Makefile

`make laptop` prüft jetzt vor dem Start `.venv/bin/python` und `import thrift`
und bricht mit klarer Meldung ab, statt einen toten Hintergrundprozess zu
starten (Ursache des Problems vom 2026-11-22). Zusätzlich erscheint
`[make] Wagen-Log -> logs/wagons.log`.

### Unverändert

Synchronisationslogik, Zähler, `[METRIC]`-Format und 10-s-Periode,
`recentEvents`-Nummerierung (`#0042`), `StationLog`, IDL, generierter Code.
`StationHandler` und Clients wurden in Abschnitt 14 nur für die Anzeige ergänzt.

## 14. Update 2026-11-22: echter Fahrtfortschritt (aktuell)

### Fahrtdauer und Boarding

- `RIDE_MS` ist jetzt eine **feste** Dauer (Default `20000` ms). Der zufällige
  Zuschlag im Python-Wagen (`random.randint(0, 2000)`) wurde entfernt. Damit ist
  die vom Dashboard berechnete Progressionsanzeige exakt.
- Die Station bekommt denselben Wert über das neue Flag `--ride-ms=$(RIDE_MS)`.
- Passagiere warten vor dem Einsteigen eine zufällige Zeit im Bereich
  `BOARD_MIN_MS`..`BOARD_MAX_MS` (Default 1000..5000 ms). Das geschieht im
  `PassengerClient` **vor** `i_am_boarding()`, damit der Monitor nicht blockiert.
  `PAUSE_MS` steht auf `0`.
- Neue Runner-Flags: `--board-min-ms`, `--board-max-ms`.

### Dashboard

- `TRACK` zeigt statt des immer vollen Sitzplatzbalkens den echten Fortschritt:
  `[W3 ▶ 4/4 ━━━━━━╸─── 62%]` (10 Zellen, auf 99 % geclemmt, vor Abfahrt `--`).
- `▶` markiert weiter den Wagen, der als Nächstes entladen wird.
- Ohne Farben wird `=`/`>`/`-` statt `━`/`╸`/`─` verwendet.

### Anzeige-Daten im Handler (keine Logikänderung)

- `rideStartedNanos`: Zeitstempel pro Wagen, gesetzt in `departure()`, gelöscht in
  `ride_finished()` und safetyhalber in `arrive()`.
- `Status` wurde um `Map<Integer, Long> rideStartedNanos` erweitert; `snapshot()`
  liefert eine Kopie. `stats()`/`[METRIC]` sind unverändert.

### Unverändert

Thrift-IDL, generierter Code, Wait/Notify, FIFO-Reihenfolge, Zähler, 10-s-Metrik,
`StationLog`, `ConsoleView`-Header, Makefile-Strg+C-Verhalten.

## 15. Update 2026-11-22: deterministische Wagen-Startreihenfolge (aktuell)

### Problem

`wagon_runner.py` startet alle Wagen in einer dichten Schleife. Welcher Prozess
zuerst `arrive()` erreicht, landete vorne in der `platformQueue` – die Reihenfolge
war bei jedem Start anders.

### Lösung

- Neues Server-Flag `--wagons=$(WAGONS)` (Default `0` = altes Verhalten).
- `StationHandler` kennt `expectedWagons` und ein `wagonsSeen`-Set.
- In `arrive()` wird während der Startphase sortiert eingefügt, bis alle
  erwarteten Wagen gesehen wurden. Danach wieder normales `addLast()`.
- Ergebnis: immer `W1, W2, W3, W4` im Bahnsteig, unabhängig vom Start-Timing.

### Sonderfälle

- Weniger Wagen als `WAGONS` gestartet: sortierte Einfügung bleibt aktiv, die
  vorhandenen Wagen sind trotzdem korrekt geordnet.
- Wagen-Neustart mit gleicher ID: already seen → normales `addLast()` am Ende.
- `--wagons=0` (manueller Start ohne Flag): unverändertes altes Verhalten.

### Unverändert

Wait/Notify, Füllregel, Strecken-Reihenfolge, Zähler, `[METRIC]`-Format, IDL,
generierter Code, Clients, `ConsoleView`.

## 16. Update 2026-11-22: RIDES/PAX als Kacheln (aktuell)

### Vorher

`P1 29  P2 28  …` in Blöcken zu 8, danach `min/max/avg` – eine unstrukturierte
Zahlenliste ohne direkt sichtbare Fairness-Information.

### Jetzt

```text
RIDES/PAX  min 24 (P9) · max 29 (P1) · avg 26.7 · spread 5
           [P1  29 ↑2] [P2  28 ↑1] [P3  26 ↓1] [P4  27 → ] [P5  26 ↓1]
           [P6  26 ↓1] [P7  26 ↓1] [P8  25 ↓2] [P9  24 ↓3] [P10 28 ↑1]
```

- Summary mit `min`/`max` inkl. Passagier-ID, `avg` und **`spread = max - min`**.
- Pro Passagier eine fest breite Kachel `[P1  29 ↑2]`: ID cyan, Fahrten magenta,
  Pfeil = gerundete Abweichung vom Average (`↑`/`↓`/`→`).
- `P%-2d` hält die Kacheln auch bei zweistelligen IDs gleich lang; 5 pro Zeile.
- Farben: `↑` grün, `↓` blau, `→` grau; ohne Farben bleiben die Zeichen lesbar.

### Unverändert

`rideCount` aus `Status`, Sortierung nach ID, `StationHandler`, IDL, Log-Format,
alle anderen Dashboard-Bereiche.

## 17. Update 2026-11-22: feste Dashboard-Breite (aktuell)

- Die automatische Breitenerkennung (`COLUMNS`, `stty size < /dev/tty`) wurde
  entfernt. Die `detectWidth()`-Methode und die dafür nötigen Imports sind weg.
- Default ist jetzt eine **feste Breite von 120 Spalten**. Bei 100 Spalten wurde
  der vierte Wagen auf `TRACK` abgeschnitten (`[W4 …`); die Zeile benötigt 118
  Zeichen (114 Inhalt + 4 Rahmen). 120 lässt 2 Zeichen Puffer.
  Ist das Terminal schmaler, werden Zeilen abgeschnitten – das ist gewollt.
- `--width=N` bleibt als manueller Override erhalten.
- Unverändert: TTY-Erkennung über `test -t 1`, Farben, alle Inhalte.

