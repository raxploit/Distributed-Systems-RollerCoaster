package rollerCoaster;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class StationServer {
    public static void main(String[] args) throws Exception {
        int port = 9090;
        int capacity = 4;
        int width = 96;
        Path logPath = Path.of("logs", "station.log");
        boolean dashboard = true;

        for (String arg : args) {
            if (arg.startsWith("--log=")) {
                logPath = Path.of(arg.substring("--log=".length()));
            } else if (arg.equals("--no-dashboard")) {
                dashboard = false;
            } else if (arg.equals("--dashboard")) {
                dashboard = true;
            } else if (arg.startsWith("--width=")) {
                width = Integer.parseInt(arg.substring("--width=".length()));
            } else if (arg.equals("--no-color") || arg.equals("--color")) {
                // Farben kommen in Phase 2; die Flags werden schon akzeptiert.
            } else if (arg.matches("\\d+")) {
                capacity = Integer.parseInt(arg);
            } else {
                System.err.println("Unbekanntes Argument: " + arg);
                return;
            }
        }

        // Ohne echtes Terminal (Redirect, docker logs) wären Escape-Codes unsinnig.
        boolean tty = System.console() != null && !"dumb".equals(System.getenv("TERM"));
        boolean interactive = dashboard && tty;

        StationLog log = new StationLog(logPath, !interactive);
        ConsoleView view = new ConsoleView(width, interactive);
        Runtime.getRuntime().addShutdownHook(new Thread(log::close, "station-log-close"));

        StationHandler handler = new StationHandler(capacity, log);     // EINE Instanz fuer alle Clients
        Station.Processor<StationHandler> processor = new Station.Processor<>(handler);

        TServerSocket socket = new TServerSocket(port);
        TServer server = new TThreadPoolServer(
                new TThreadPoolServer.Args(socket)
                        .processor(processor)
                        .minWorkerThreads(16)); // ein Thread pro Client-Verbindung (m + n)

        log.station("laeuft auf Port " + port + ", c = " + capacity);
        log.station("Log: " + logPath.toAbsolutePath());
        log.metric(handler.stats());

        // Alle 10 s kompakte Metriken in die Log-Datei (Daemon -> blockiert kein Ende)
        ScheduledExecutorService metrics = daemon("station-metrics");
        metrics.scheduleAtFixedRate(() -> log.metric(handler.stats()), 10, 10, TimeUnit.SECONDS);

        if (interactive) {
            view.render(handler.snapshot());
            ScheduledExecutorService viewTick = daemon("station-view");
            viewTick.scheduleAtFixedRate(() -> {
                try {
                    view.render(handler.snapshot());
                } catch (RuntimeException e) {
                    log.station("Dashboard-Fehler: " + e);
                }
            }, 250, 250, TimeUnit.MILLISECONDS);
        }

        server.serve();
    }

    private static ScheduledExecutorService daemon(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }
}
