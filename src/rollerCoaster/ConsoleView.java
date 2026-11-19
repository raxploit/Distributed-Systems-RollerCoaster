package rollerCoaster;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Einziger Schreiber auf stdout. Phase 1: reines Zustands-Dashboard mit
 * Warteschlange, Bahnsteig, Strecke und Zählern. Farben und Event-Ticker
 * kommen in späteren Phasen.
 */
public class ConsoleView implements AutoCloseable {

    private final int width;
    private final boolean interactive;
    private final long startedNanos = System.nanoTime();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private boolean firstFrame = true;

    public ConsoleView(int width, boolean interactive) {
        this.width = Math.max(60, width);
        this.interactive = interactive;
        if (interactive) {
            System.out.print("\033[?25l"); // Cursor verstecken
            System.out.flush();
            Runtime.getRuntime().addShutdownHook(new Thread(this::reset, "station-view-reset"));
        }
    }

    public boolean isInteractive() {
        return interactive;
    }

    public synchronized void render(StationHandler.Status s) {
        if (!interactive || closed.get()) {
            return;
        }

        StringBuilder b = new StringBuilder(2048);
        top(b, "ACHTERBAHN · :9090");
        row(b, "Laufzeit " + elapsed() + "   Kapazität " + s.capacity()
                + "   Wagen " + s.seats().size() + "   Aktualisierung 4 Hz");
        middle(b);
        row(b, labeled("WARTEND", ids(s.waiting())));
        row(b, labeled("BAHNSTEIG", wagons(s.platform())));
        row(b, labeled("STRECKE", track(s.track())));
        middle(b);
        row(b, counters(s));
        bottom(b);

        System.out.print((firstFrame ? "\033[2J" : "") + "\033[H" + b + "\033[J");
        System.out.flush();
        firstFrame = false;
    }

    @Override
    public synchronized void close() {
        reset();
    }

    private void reset() {
        if (interactive && closed.compareAndSet(false, true)) {
            System.out.print("\033[?25h\n"); // Cursor wieder an
            System.out.flush();
        }
    }

    private String elapsed() {
        long secs = Duration.ofNanos(System.nanoTime() - startedNanos).getSeconds();
        return String.format(Locale.ROOT, "%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
    }

    private String labeled(String label, String value) {
        return String.format(Locale.ROOT, "%-10s", label) + value;
    }

    private String counters(StationHandler.Status s) {
        return "BOARDINGS  " + num(s.boardings())
                + "   ABFAHREN  " + num(s.departures())
                + "   FAHRTEN FERTIG  " + num(s.completedRides());
    }

    private String num(long n) {
        return String.format(Locale.ROOT, "%5d", n);
    }

    private String ids(List<Integer> values) {
        if (values.isEmpty()) {
            return "[-]";
        }
        StringBuilder b = new StringBuilder();
        for (int id : values) {
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append("[P").append(id).append(']');
        }
        return b.toString();
    }

    private String wagons(List<Integer> values) {
        if (values.isEmpty()) {
            return "[-]";
        }
        StringBuilder b = new StringBuilder();
        for (int id : values) {
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append("[W").append(id).append(']');
        }
        return b.toString();
    }

    private String track(List<Integer> values) {
        if (values.isEmpty()) {
            return "[-]";
        }
        StringBuilder b = new StringBuilder();
        for (int id : values) {
            if (b.length() > 0) {
                b.append(" → ");
            }
            b.append("[W").append(id).append(']');
        }
        return b.toString();
    }

    private void top(StringBuilder b, String title) {
        String t = "─ " + title + " ";
        b.append('┌').append(t).append("─".repeat(Math.max(0, width - 2 - t.length()))).append("┐\n");
    }

    private void middle(StringBuilder b) {
        b.append('├').append("─".repeat(width - 2)).append("┤\n");
    }

    private void bottom(StringBuilder b) {
        b.append('└').append("─".repeat(width - 2)).append("┘\n");
    }

    private void row(StringBuilder b, String content) {
        String text = fit(content);
        b.append("│ ").append(text);
        b.append(" ".repeat(Math.max(0, width - 4 - text.length())));
        b.append(" │\n");
    }

    private String fit(String s) {
        int max = width - 4;
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
