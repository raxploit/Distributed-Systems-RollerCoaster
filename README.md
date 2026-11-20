# Roller Coaster

Verteilte Achterbahn-Simulation mit Apache Thrift. Die Station läuft auf dem
Laptop, Wagen und Passagiere als einzelne Container in der VM (Tailnet).

## Setup

Auf beiden Systemen Tailscale installieren und im selben Tailnet anmelden.
Das Repo liegt auf beiden Maschinen im Projektordner.

Laptop (Station):

```shell
sudo pacman -S jdk21-openjdk
javac -cp "lib/*" -d build src/rollerCoaster/*.java gen-java/rollerCoaster/*.java
```

VM (Clients):

```shell
sudo pacman -S docker docker-compose make netcat
sudo systemctl enable --now docker.service
sudo usermod -aG docker "$USER"   # danach neu anmelden
```

Kein Thrift-Compiler nötig (generierter Code und Jars liegen im Repo).

## Konfiguration

```shell
cp .env.example .env
```

In `.env`: `STATION_HOST` = Tailnet-IP des Laptops (Port 9090), `WAGONS`,
`PASSENGERS`, `RIDE_MS`, `ID_OFFSET`. Erreichbarkeit prüfen: `nc -vz <tailnet-ip> 9090`.

## Server starten (Laptop)

```shell
java -cp "build:lib/*" rollerCoaster.StationServer 4
```

Im Terminal läuft ein Live-Dashboard (4 Hz) mit englischen Beschriftungen:
`CONFIG`, `UPTIME`, `WAITING`, `PLATFORM`, `TRACK`, `COUNTERS`, `RIDES/PAX`
und `LAST 5`. Farben gibt es automatisch im echten Terminal. Die `[Station]`-
und `[METRIC]`-Zeilen bleiben im alten Format in `logs/station.log`:

```shell
tail -f logs/station.log
```

### Parameter

| Parameter | Bedeutung | Default |
|---|---|---|
| `4` (positionsabhängig) | Kapazität `c` | `4` |
| `--log=pfad` | Ziel der Log-Datei | `logs/station.log` |
| `--width=N` | Breite des Dashboards | `96` |
| `--no-dashboard` | Kein Dashboard, Log-Zeilen auf stdout | aus |
| `--no-color` / `NO_COLOR=1` | Farben aus | automatisch |
| `--color` | Farben an, sofern nicht `NO_COLOR` gesetzt | automatisch |

Ohne TTY oder mit `--no-dashboard` gehen die Log-Zeilen direkt auf stdout.

## Container starten (VM)

```shell
make up      # ein Container pro Wagen/Passagier
make logs    # oder: docker compose logs -f wagon-1
make ps
make down
```
