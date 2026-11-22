package rollerCoaster;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class StationServer {
    public static void main(String[] args) throws Exception {
        int port = 9090;
        int capacity = 4;
        Integer widthFlag = null;
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
                widthFlag = Integer.parseInt(arg.substring("--width=".length()));
            } else if (arg.equals("--no-color")) {
                colorFlag = false;
            } else if (arg.equals("--color")) {
                colorFlag = true;
            } else if (arg.startsWith("--capacity=")) {
                capacity = Integer.parseInt(arg.substring("--capacity=".length()));
            } else if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
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
        int width = widthFlag != null ? widthFlag : (tty ? detectWidth(96) : 96);

        StationLog log = new StationLog(logPath, !interactive);
        ConsoleView view = new ConsoleView(port, width, interactive, colors, logPath.toString());
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

    /** Terminalbreite aus COLUMNS oder stty; Fallback 96. --width=N überschreibt alles. */
    private static int detectWidth(int fallback) {
        String columns = System.getenv("COLUMNS");
        if (columns != null) {
            try {
                int value = Integer.parseInt(columns.trim());
                if (value >= 60) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // Ignore and try stty below.
            }
        }
        try {
            Process p = new ProcessBuilder("stty", "size")
                    .redirectInput(new File("/dev/tty"))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0) {
                String[] parts = out.split("\\s+");
                if (parts.length >= 2) {
                    int value = Integer.parseInt(parts[1]);
                    if (value >= 60) {
                        return value;
                    }
                }
            }
        } catch (Exception ignored) {
            // No stty or no usable terminal size: keep the fallback.
        }
        return fallback;
    }

    private static ScheduledExecutorService daemon(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }
}
