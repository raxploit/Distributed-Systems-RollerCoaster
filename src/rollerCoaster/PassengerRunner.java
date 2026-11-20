package rollerCoaster;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Startet m Passagier-Prozesse in einem Container. Der Runner wartet auf die
 * Station, vergibt feste IDs und beendet alle Kinder bei SIGTERM. Jeder
 * Passagier bleibt damit ein eigener Knoten mit eigener Thrift-Verbindung.
 */
public final class PassengerRunner {

    private static volatile boolean stopping = false;
    private static final List<Child> children = Collections.synchronizedList(new ArrayList<>());

    private static final class Child {
        final int id;
        final String[] cmd;
        Process process;

        Child(int id, String[] cmd, Process process) {
            this.id = id;
            this.cmd = cmd;
            this.process = process;
        }
    }

    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = 9090;
        int count = 10;
        int offset = 0;
        int pauseMs = 500;

        for (String arg : args) {
            if (arg.startsWith("--host=")) {
                host = arg.substring("--host=".length());
            } else if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            } else if (arg.startsWith("--count=")) {
                count = Integer.parseInt(arg.substring("--count=".length()));
            } else if (arg.startsWith("--id-offset=")) {
                offset = Integer.parseInt(arg.substring("--id-offset=".length()));
            } else if (arg.startsWith("--pause-ms=")) {
                pauseMs = Integer.parseInt(arg.substring("--pause-ms=".length()));
            } else {
                System.err.println("Unbekanntes Argument: " + arg);
                return;
            }
        }

        Runtime.getRuntime().addShutdownHook(new Thread(PassengerRunner::shutdown, "passenger-runner-shutdown"));
        waitForPort(host, port);

        for (int i = 1; i <= count; i++) {
            int id = offset + i;
            String[] cmd = {
                    "java", "-cp", "build:lib/*", "rollerCoaster.PassengerClient",
                    host, String.valueOf(port), String.valueOf(id), String.valueOf(pauseMs)
            };
            children.add(new Child(id, cmd, start(cmd)));
        }

        while (!stopping) {
            synchronized (children) {
                for (Child child : children) {
                    if (!child.process.isAlive() && !stopping) {
                        System.out.println("[passenger-runner] P" + child.id + " beendet – starte neu");
                        child.process = start(child.cmd);
                    }
                }
            }
            Thread.sleep(1000);
        }
    }

    private static Process start(String[] cmd) throws Exception {
        System.out.println("[passenger-runner] starte " + String.join(" ", cmd));
        return new ProcessBuilder(cmd).inheritIO().start();
    }

    private static void waitForPort(String host, int port) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300);
        while (true) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 1000);
                System.out.println("[passenger-runner] Station " + host + ":" + port + " ist erreichbar");
                return;
            } catch (Exception e) {
                if (System.nanoTime() >= deadline) {
                    System.err.println("Station " + host + ":" + port + " nach 300s nicht erreichbar");
                    System.exit(1);
                }
                Thread.sleep(1000);
            }
        }
    }

    private static void shutdown() {
        stopping = true;
        List<Child> snapshot;
        synchronized (children) {
            snapshot = new ArrayList<>(children);
        }
        for (Child child : snapshot) {
            if (child.process.isAlive()) {
                child.process.destroy();
            }
        }
        for (Child child : snapshot) {
            try {
                if (!child.process.waitFor(5, TimeUnit.SECONDS)) {
                    child.process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                child.process.destroyForcibly();
            }
        }
        System.out.println("[passenger-runner] alle Passagiere beendet");
    }
}
