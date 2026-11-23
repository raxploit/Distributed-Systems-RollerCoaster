"""Startet n Wagen-Prozesse in einem Container.

Der Runner wartet auf die Station, startet einen Python-Prozess pro Wagen mit
fester ID und beendet alle Kinder bei SIGTERM sauber. Jeder Wagen bleibt damit
ein eigener Knoten mit eigener Thrift-Verbindung.
"""

import argparse
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path

CLIENT = Path(__file__).with_name("wagon_client.py")

stopping = False


def log(msg):
    print(f"[wagon-runner] {msg}", flush=True)


def wait_for_port(host, port, timeout=300, interval=1):
    """Warten, bis die Station erreichbar ist (die Clients haben kein Retry)."""
    deadline = time.monotonic() + timeout
    while True:
        try:
            with socket.create_connection((host, port), timeout=1):
                log(f"Station {host}:{port} ist erreichbar")
                return
        except OSError:
            if time.monotonic() >= deadline:
                raise SystemExit(f"Station {host}:{port} nach {timeout}s nicht erreichbar")
            time.sleep(interval)


def on_signal(signum, frame):
    global stopping
    stopping = True
    log(f"Signal {signum} erhalten – beende Wagen")


def spawn(cmd):
    log("starte " + " ".join(cmd))
    return subprocess.Popen(cmd)


def terminate(children):
    for child in children:
        if child["proc"].poll() is None:
            child["proc"].terminate()
    for child in children:
        try:
            child["proc"].wait(timeout=5)
        except subprocess.TimeoutExpired:
            child["proc"].kill()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="station")
    parser.add_argument("--port", type=int, default=9090)
    parser.add_argument("--count", type=int, default=4)
    parser.add_argument("--ride-ms", type=int, default=3000)
    parser.add_argument("--boarding-ms", type=int, default=0)
    parser.add_argument("--id-offset", type=int, default=0)
    parser.add_argument("--start-stagger-ms", type=int, default=400)
    args = parser.parse_args()

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)

    wait_for_port(args.host, args.port)

    children = []
    for i in range(1, args.count + 1):
        wagon_id = args.id_offset + i
        cmd = [
            sys.executable, "-u", str(CLIENT),
            args.host, str(args.port), str(wagon_id),
            str(args.ride_ms), str(args.boarding_ms),
        ]
        children.append({"id": wagon_id, "cmd": cmd, "proc": spawn(cmd)})
        if i < args.count:
            # Die Prozesse sonst gleichzeitig starten zu lassen, ergaebe zufaellige
            # arrive()-RPCs; der Versatz macht die ersten Aufrufe W1, W2, W3, ...
            time.sleep(max(0, args.start_stagger_ms) / 1000)

    try:
        while not stopping:
            for child in children:
                rc = child["proc"].poll()
                if rc is not None and not stopping:
                    log(f"W{child['id']} beendet ({rc}) – starte neu")
                    child["proc"] = spawn(child["cmd"])
            time.sleep(1)
    finally:
        terminate(children)
        log("alle Wagen beendet")


if __name__ == "__main__":
    main()
