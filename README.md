# Roller Coaster

Verteilte Achterbahn-Simulation mit Apache Thrift. **Station und Wagen laufen
auf der VM, die Passagiere auf dem Präsentationslaptop.** Damit sind Wagen und
Passagiere auf verschiedenen physischen Maschinen, und die Demo zeigt, dass
Thrift Java- und Python-Knoten verbindet.

```
VM (Tailnet-IP)                    Laptop
┌────────────────────────┐         ┌────────────────────────┐
│ station  (Java, :9090) │<────────│ passengers (Java)      │
│ wagons   (Python)      │ Tailnet│ 10 Passagier-Prozesse  │
└────────────────────────┘         └────────────────────────┘
```

## Setup

Auf beiden Maschinen (Arch Linux):

```shell
sudo pacman -S docker docker-compose make openssh netcat
sudo systemctl enable --now docker.service
sudo usermod -aG docker "$USER"   # danach neu anmelden
```

Tailscale auf beiden Systemen installieren und im selben Tailnet anmelden. Das
Repo liegt auf beiden Maschinen im gleichen Pfad (default
`/projects/Distributed-Systems-RollerCoaster`). Vom Laptop muss SSH zur VM
möglich sein (`ssh arch@<vm-tailnet-ip>`).

Kein Thrift-Compiler nötig: `gen-java/`, `gen-py/` und `lib/*.jar` liegen im Repo.

## Konfiguration

```shell
cp .env.example .env
```

**Wichtig:** `STATION_HOST` ist jetzt die **Tailnet-IP der VM**, nicht mehr die
des Laptops. Alle Parameter:

| Variable | Bedeutung | Default |
|---|---|---|
| `STATION_HOST` | Tailnet-IP der VM (auch SSH-Ziel) | – |
| `STATION_PORT` | Port der Station | `9090` |
| `CAPACITY` | Sitze pro Wagen `c` | `4` |
| `WAGONS` | Anzahl Wagen | `4` |
| `RIDE_MS` | Basis-Fahrtdauer in ms | `3000` |
| `BOARDING_MS` | simulierte Boardingzeit in ms | `0` |
| `PASSENGERS` | Anzahl Passagiere | `10` |
| `PAUSE_MS` | Pause zwischen zwei Fahrten in ms | `500` |
| `ID_OFFSET` | Offset für Client-IDs | `0` |
| `VM_USER`, `VM_REPO_DIR` | SSH-Zugriff auf die VM | `arch`, Repo-Pfad |

`VM_HOST` kann optional gesetzt werden; sonst wird `STATION_HOST` verwendet.

## Start und Stop

Alles vom Laptop aus:

```shell
make up        # VM: station + wagons, Laptop: passengers
make down      # beides stoppen
make ps        # Status auf beiden Maschinen
make config    # aufgelöste Compose-Dateien prüfen
```

Einzelne Seiten:

```shell
make vm-up     # nur Station + Wagen auf der VM
make pax-up    # nur Passagiere auf dem Laptop
make vm-down
make pax-down
```

Die VM muss Port 9090 aus dem Tailnet erreichbar machen, z. B.
`sudo ufw allow from 100.64.0.0/10 to any port 9090`.

## Logs

```shell
make logs       # station + wagons auf der VM
make pax-logs   # Passagiere auf dem Laptop
```

Die maschinenlesbaren `[Station]`- und `[METRIC]`-Zeilen stehen unverändert in
`logs/station.log` auf der VM (per Volume auch im Repo sichtbar):

```shell
tail -f logs/station.log
```

Im Container gibt es kein TTY; das farbige Live-Dashboard ist daher nur beim
nativen Start sichtbar. Für native Tests:

```shell
javac -cp "lib/*" -d build src/rollerCoaster/*.java gen-java/rollerCoaster/*.java
java -cp "build:lib/*" rollerCoaster.StationServer --capacity=4 --port=9090
```

Weitere Server-Flags: `--log=pfad`, `--width=N`, `--no-dashboard`,
`--no-color` / `--color` (oder `NO_COLOR=1`). Ohne TTY oder mit
`--no-dashboard` gehen die Log-Zeilen direkt auf stdout.
