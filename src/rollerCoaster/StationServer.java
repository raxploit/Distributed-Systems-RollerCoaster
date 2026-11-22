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
        int width = 100; // feste Dashboard-Breite; zu schmale Fenster schneiden ab
        long rideMs = 20000;
        int expectedWagons = 0; // 0 = altes Verhalten (keine sortierte Startphase)
        Path logPath = Path.of("logs", "station.log");
        boolean dashboard = true;
        boolean colorFlag = true;

        for (String arg : args) {
            if (arg.startsWith("--log=")) {
                logPath = Path.of(arg.substring("--log=".length()));
            } else if (arg.equals("--no-dashboard")) {
                dashboard = false;
            } else if (arg.equals("--dashboard")) {
                dashboard = true;
            } else if (arg.startsWith("--width=")) {
                width = Integer.parseInt(arg.substring("--width=".length()));
            } else if (arg.equals("--no-color")) {
                colorFlag = false;
            } else if (arg.equals("--color")) {
                colorFlag = true;
            } else if (arg.startsWith("--capacity=")) {
                capacity = Integer.parseInt(arg.substring("--capacity=".length()));
            } else if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            } else if (arg.startsWith("--ride-ms=")) {
                rideMs = Long.parseLong(arg.substring("--ride-ms=".length()));
            } else if (arg.startsWith("--wagons=")) {
                expectedWagons = Integer.parseInt(arg.substring("--wagons=".length()));
            } else if (arg.matches("\\d+")) {
                capacity = Integer.parseInt(arg);
            } else {
                System.err.println("Unbekanntes Argument: " + arg);
                return;
            }
        }

        // Ohne echtes Terminal (z. B. Redirect in eine Datei) wären Escape-Codes unsinnig.
        boolean tty = !"dumb".equals(System.getenv("TERM")) && isStdoutTty();
        boolean interactive = dashboard && tty;
        boolean noColorEnv = System.getenv("NO_COLOR") != null;
        boolean colors = colorFlag && !noColorEnv;

        StationLog log = new StationLog(logPath, !interactive);
        ConsoleView view = new ConsoleView(port, width, interactive, colors, logPath.toString(), rideMs);
        Runtime.getRuntime().addShutdownHook(new Thread(log::close, "station-log-close"));

        StationHandler handler = new StationHandler(capacity, log, expectedWagons); // EINE Instanz fuer alle Clients
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
            }, ConsoleView.REFRESH_MS, ConsoleView.REFRESH_MS, TimeUnit.MILLISECONDS);
        }

        server.serve();
    }

    /**
     * Das Dashboard schreibt nur auf stdout. Ein per '&' gestarteter Prozess hat
     * stdin=/dev/null (System.console() == null), aber weiterhin ein TTY auf fd 1.
     */
    private static boolean isStdoutTty() {
        try {
            return new ProcessBuilder("/bin/sh", "-c", "test -t 1")
                    .inheritIO().start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static ScheduledExecutorService daemon(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }
}
