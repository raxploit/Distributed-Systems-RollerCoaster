package rollerCoaster;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class StationServer {
    public static void main(String[] args) throws Exception {
        int port = 9090;
        int capacity = args.length > 0 ? Integer.parseInt(args[0]) : 4; // c

        StationHandler handler = new StationHandler(capacity);     // EINE Instanz fuer alle Clients
        Station.Processor<StationHandler> processor = new Station.Processor<>(handler);

        TServerSocket socket = new TServerSocket(port);
        TServer server = new TThreadPoolServer(
                new TThreadPoolServer.Args(socket)
                        .processor(processor)
                        .minWorkerThreads(16)); // ein Thread pro Client-Verbindung (m + n)

        System.out.println("[Station] laeuft auf Port " + port + ", c = " + capacity);
        System.out.println(handler.stats());

        // Alle 10 s einen kompakten Zustandssnapshot ausgeben (Daemon -> blockiert kein Ende)
        ScheduledExecutorService metrics = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "station-metrics");
            t.setDaemon(true);
            return t;
        });
        metrics.scheduleAtFixedRate(() -> System.out.println(handler.stats()), 10, 10, TimeUnit.SECONDS);

        server.serve();
    }
}