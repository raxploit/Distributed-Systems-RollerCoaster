import sys
import time

from rollerCoaster import Station  # Modulname = Name der .thrift-Datei (bzw. "namespace py")
from thrift.protocol import TBinaryProtocol
from thrift.transport import TSocket, TTransport


def main():
    host = sys.argv[1]
    port = int(sys.argv[2])
    wagon_id = int(sys.argv[3])
    base_ride_time = int(sys.argv[4]) if len(sys.argv) > 4 else 3000
    boarding_ms = int(sys.argv[5]) if len(sys.argv) > 5 else 0

    socket = TSocket.TSocket(host, port)
    socket.setTimeout(None)  # Calls duerfen lange blockieren (arrive, ride_finished)
    transport = TTransport.TBufferedTransport(socket)
    protocol = TBinaryProtocol.TBinaryProtocol(transport)  # muss zum Server passen
    station = Station.Client(protocol)
    transport.open()

    rides = 0
    try:
        while True:
            print(f"[W{wagon_id}] fahre in den Bahnsteig ein")
            station.arrive(wagon_id)  # blockiert, bis ich dran UND voll bin

            if boarding_ms > 0:
                time.sleep(boarding_ms / 1000)  # simulierte Boardingzeit nach dem vollen Wagen

            station.departure(wagon_id)
            ride_time = base_ride_time  # feste Dauer -> die Station kann echten Fortschritt zeigen
            print(f"[W{wagon_id}] Abfahrt, Fahrt dauert {ride_time} ms")
            time.sleep(ride_time / 1000)  # die eigentliche Fahrt

            station.ride_finished(wagon_id)  # blockiert, bis alle ausgestiegen sind
            rides += 1
            print(f"[W{wagon_id}] leer, zurueck zum Bahnsteig | Fahrten: {rides}")
    finally:
        transport.close()


if __name__ == "__main__":
    main()
