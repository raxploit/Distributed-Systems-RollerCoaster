# CHANGELOG – Logik, Metriken, Architektur

Stand: 2026-11-20

Zweck dieser Datei: alles festhalten, was **nicht** Optik ist (also nicht Dashboard-Layout,
Farben, Labels, Rahmen). Damit kann ich auf Anfrage schnell und korrekt zusammenfassen,
was fachlich am Projekt geändert wurde.

**Kurzfassung:** Die Achterbahn ist ein zentraler Java-Monitor (`StationHandler`) mit
Thrift-RPCs. Ein Wagen fährt erst voll ab, Passagiere werden FIFO bedient, die Strecke
erzwingt Abfahrtsreihenfolge. Metriken laufen ohne IDL-Änderung über Zähler im Handler
und eine 10-s-Periode in `logs/station.log`. Betrieb: Station nativ auf dem Laptop,
 Clients als einzelne Container in der VM (Tailnet). Generiert werden 14 Container
(4 Wagen + 10 Passagiere). Docker-Image enthält bewusst Java **und** Python, weil
Wagen Python und Passagiere Java sind.

---

## 1. Architektur – Ist-Zustand

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
Ohne TTY (Redirect, `docker logs`) werden automatisch die Log-Zeilen ausgegeben, damit
keine ANSI-Codes in Dateien/Redirects landen.

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
  `StartWagons.sh` bleiben liegen, werden aber vom Docker-Betrieb ebenfalls nicht genutzt.

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

## 9. Offene Punkte / Entscheidungen (noch nicht umgesetzt)

1. **`TASK.md` verletzt aktuell:** „passengers and wagons should not be on the same
   machines“. Bei uns laufen beide Gruppen als Container in **einer** VM. Container sind
   getrennte Knoten, aber keine getrennten physischen Maschinen. Möglich: Passagiere VM /
   Wagen Laptop, oder umgekehrt, oder zwei VMs.
2. **Neuer Wunsch des Users (letzte Nachricht):** Start, Parameter für *einen Wagen-Container*
   und *einen Passagier-Container* sowie Ende vom Laptop aus steuern; VM soll auf dem Port
   lauschen; Parameter wie Boarding-Dauer / Fahrtdauer „dynamisch“ anpassen; Frage, ob die
   Station im selben Container wie die Wagen liegen soll. **Noch nichts davon implementiert.**
   Ich hatte empfohlen: Station + Wagen in einen Container (Wagen via `127.0.0.1`),
   Passagiere separat, Port einmal exponiert. „Dynamisch“ zunächst als Startzeit-Env
   (`BOARDING_MS`, `RIDE_MS`, `CAPACITY`, `WAGONS`), nicht als Live-Änderung zur Laufzeit
   (das bräuchte einen zusätzlichen Control-RPC).
3. `--capacity=4` und `--port=9090` als echte Flags fehlen; Port ist hartcodiert, Kapazität
   nur Positionsargument.
4. Keine Validierung von `c < m`.
5. `minWorkerThreads(16)` könnte bei deutlich mehr als ~16–30 gleichzeitig blockierten
   Clients eng werden; für die Demo (4+10) reicht es.

## 10. Prüfstand / Einschränkung

- In dieser Umgebung ist **kein `javac`/Java installiert** → kein Kompilieren, kein Start,
  keine Laufzeitprüfung. Alle Checks waren statisch: Klammern ausgeglichen, keine
  `System.out`-Reste im Handler, eine `new StationHandler(...)`-Stelle, Python-Mock des
  Layouts.
- Prüfkommandos für den User:
  ```shell
  javac -cp "lib/*" -d build src/rollerCoaster/*.java gen-java/rollerCoaster/*.java
  java -cp "build:lib/*" rollerCoaster.StationServer 4
  tail -f logs/station.log
  ```
- Alle Änderungen liegen nach aktuellem Stand uncommitted auf Platte; kein Commit/Push.
