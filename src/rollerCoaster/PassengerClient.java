package rollerCoaster;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;

import java.util.Random;

/**
 * Passagier-Knoten. Start: java PassengerClient <stationHost> <port> <passengerId>
 * [pauseMs] [boardMinMs] [boardMaxMs]. Mehrere Passagiere = mehrere Prozesse mit
 * unterschiedlicher ID. Die zufällige Ankunftszeit liegt vor dem Betreten der
 * Warteschlange, damit der Monitor nicht blockiert wird.
 */
public class PassengerClient {
    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int id = Integer.parseInt(args[2]);
        int pauseMs = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int boardMinMs = args.length > 4 ? Integer.parseInt(args[4]) : 1000;
        int boardMaxMs = args.length > 5 ? Integer.parseInt(args[5]) : 5000;
        Random random = new Random();

        TTransport transport = new TSocket(host, port);
        transport.open();
        Station.Client station = new Station.Client(new TBinaryProtocol(transport));

        int rides = 0;
        while (true) {
            int span = Math.max(1, boardMaxMs - boardMinMs + 1);
            Thread.sleep(boardMinMs + random.nextInt(span)); // Passagier kommt zufällig an
            System.out.println("[P" + id + "] moechte fahren");
            int wagonId = station.i_am_boarding(id);        // blockiert, bis ich einen Platz habe
            System.out.println("[P" + id + "] sitze in W" + wagonId);

            station.wait_for_ride_end(id);                  // blockiert während der Fahrt
            station.i_am_disembarking(id);
            rides++;
            System.out.println("[P" + id + "] ausgestiegen, Fahrten: " + rides);

            Thread.sleep(pauseMs);                          // kurze Erholung, dann wieder anstellen
        }
    }
}
