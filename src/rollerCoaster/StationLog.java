package rollerCoaster;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Schreibt die bisherigen, maschinenlesbaren Zeilen in eine Log-Datei.
 * Das Format bleibt unverändert ([Station] ... und [METRIC] ...), damit
 * bestehende Auswertungen weiter funktionieren.
 *
 * echoToStdout ist nur für den Log-Modus gedacht (kein TTY / --no-dashboard):
 * Dann gehört stdout nicht dem Dashboard und die Zeilen bleiben sichtbar.
 */
public class StationLog implements AutoCloseable {

    private final Path path;
    private final PrintWriter out;
    private final boolean echoToStdout;

    public StationLog(Path path, boolean echoToStdout) throws IOException {
        this.path = path;
        this.echoToStdout = echoToStdout;
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        this.out = new PrintWriter(Files.newBufferedWriter(
                path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND));
    }

    public synchronized void station(String msg) {
        write("[Station] " + msg);
    }

    public synchronized void metric(String line) {
        write(line);
    }

    public Path path() {
        return path;
    }

    private void write(String line) {
        out.println(line);
        out.flush(); // tail -f soll sofort etwas zeigen
        if (echoToStdout) {
            System.out.println(line);
        }
    }

    @Override
    public synchronized void close() {
        out.close();
    }
}
