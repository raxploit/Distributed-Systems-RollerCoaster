package rollerCoaster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Einziger Schreiber auf stdout: Live-Dashboard mit Warteschlange, Bahnsteig,
 * Strecke, Sitzplätzen, Zählern, Fairness und den letzten fünf Ereignissen.
 * Farben gibt es nur im echten Terminal und wenn sie nicht abgeschaltet sind.
 */
public class ConsoleView implements AutoCloseable {

    private static final String ESC = "\033[";
    private static final String RESET = ESC + "0m";
    private static final String BOLD_CYAN = ESC + "1;36m";
    private static final String CYAN = ESC + "36m";
    private static final String BOLD_GREEN = ESC + "1;32m";
    private static final String BOLD_YELLOW = ESC + "1;33m";
    private static final String GREEN = ESC + "32m";
    private static final String GRAY = ESC + "90m";
    private static final String MAGENTA = ESC + "35m";
    private static final String BLUE = ESC + "34m";
    private static final String DIM = ESC + "2m";

    private static final int LABEL_WIDTH = 11;
    private static final int EVENTS = 5;
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-9;]*m");

    private final int width;
    private final boolean interactive;
    private final boolean colors;
    private final long startedNanos = System.nanoTime();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private boolean firstFrame = true;

    public ConsoleView(int width, boolean interactive, boolean colors) {
        this.width = Math.max(60, width);
        this.interactive = interactive;
        this.colors = colors && interactive;
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

        StringBuilder b = new StringBuilder(4096);
        top(b, "ACHTERBAHN · :9090");
        row(b, "Laufzeit " + elapsed() + "   Kapazität " + s.capacity()
                + "   Wagen " + s.seats().size()
                + "   Farben " + (colors ? "an" : "aus")
                + "   4 Hz");
        middle(b);
        row(b, label("WARTEND") + ids(s.waiting()));
        row(b, label("BAHNSTEIG") + wagons(s.platform(), s.seats(), s.capacity()));
        row(b, label("STRECKE") + track(s.track(), s.seats(), s.capacity()));
        middle(b);
        row(b, label("ZÄHLER") + counters(s));
        middle(b);
        fairness(b, s.rideCount());
        middle(b);
        row(b, label("LETZTE " + EVENTS));
        events(b, s.recentEvents());
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

    // ---------------- Bausteine ----------------

    private void top(StringBuilder b, String title) {
        String t = col(BOLD_CYAN, "─ " + title + " ");
        b.append('┌').append(t)
                .append("─".repeat(Math.max(0, width - 2 - visible(t))))
                .append("┐\n");
    }

    private void middle(StringBuilder b) {
        b.append('├').append("─".repeat(width - 2)).append("┤\n");
    }

    private void bottom(StringBuilder b) {
        b.append('└').append("─".repeat(width - 2)).append("┘\n");
    }

    private void row(StringBuilder b, String content) {
        String plain = stripAnsi(content);
        if (plain.length() > width - 4) {
            content = fit(plain); // lange Zeilen ohne Farben kürzen
            plain = stripAnsi(content);
        }
        b.append("│ ").append(content);
        b.append(" ".repeat(Math.max(0, width - 4 - plain.length())));
        b.append(" │\n");
    }

    private String elapsed() {
        long secs = Duration.ofNanos(System.nanoTime() - startedNanos).getSeconds();
        return String.format(Locale.ROOT, "%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
    }

    private String label(String text) {
        return col(BOLD_CYAN, padRight(text, LABEL_WIDTH));
    }

    private String indent() {
        return " ".repeat(LABEL_WIDTH);
    }

    private String padRight(String s, int n) {
        return s + " ".repeat(Math.max(0, n - s.length()));
    }

    private String num(long n) {
        return String.format(Locale.ROOT, "%5d", n);
    }

    private String fit(String s) {
        int max = width - 4;
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private String stripAnsi(String s) {
        return ANSI.matcher(s).replaceAll("");
    }

    private int visible(String s) {
        return stripAnsi(s).length();
    }

    private String col(String code, String text) {
        return colors ? code + text + RESET : text;
    }

    // ---------------- Inhalte ----------------

    private String ids(List<Integer> values) {
        if (values.isEmpty()) {
            return col(GRAY, "[-]");
        }
        List<String> parts = new ArrayList<>();
        for (int id : values) {
            parts.add(col(CYAN, "[P" + id + "]"));
        }
        return String.join(" ", parts);
    }

    private String wagons(List<Integer> values, Map<Integer, List<Integer>> seats, int capacity) {
        if (values.isEmpty()) {
            return col(GRAY, "[-]");
        }
        List<String> parts = new ArrayList<>();
        for (int id : values) {
            List<Integer> passengers = seats.getOrDefault(id, List.of());
            String box = "[" + col(BOLD_GREEN, "W" + id) + " " + bar(passengers.size(), capacity)
                    + (passengers.isEmpty() ? "" : " (" + col(CYAN, joinIds(passengers)) + ")") + "]";
            parts.add(box);
        }
        return String.join(" ", parts);
    }

    private String track(List<Integer> values, Map<Integer, List<Integer>> seats, int capacity) {
        if (values.isEmpty()) {
            return col(GRAY, "[-]");
        }
        List<String> parts = new ArrayList<>();
        for (int id : values) {
            int occupied = seats.getOrDefault(id, List.of()).size();
            parts.add("[" + col(BOLD_YELLOW, "W" + id) + " " + bar(occupied, capacity) + "]");
        }
        return String.join(col(GRAY, " → "), parts);
    }

    private String bar(int occupied, int capacity) {
        int total = Math.max(0, capacity);
        int filled = Math.min(Math.max(0, occupied), total);
        return col(GREEN, "█".repeat(filled))
                + col(GRAY, "░".repeat(total - filled))
                + " " + col(MAGENTA, filled + "/" + total);
    }

    private String joinIds(List<Integer> values) {
        StringBuilder b = new StringBuilder();
        for (int id : values) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(id);
        }
        return b.toString();
    }

    private String counters(StationHandler.Status s) {
        return "BOARDINGS " + col(MAGENTA, num(s.boardings()))
                + "   ABFAHREN " + col(MAGENTA, num(s.departures()))
                + "   FAHRTEN FERTIG " + col(MAGENTA, num(s.completedRides()));
    }

    private void fairness(StringBuilder b, Map<Integer, Integer> rideCount) {
        if (rideCount.isEmpty()) {
            row(b, label("FAHRTEN/P") + col(GRAY, "[-]"));
            return;
        }

        List<String> entries = new ArrayList<>();
        int min = Integer.MAX_VALUE;
        int max = 0;
        int sum = 0;
        for (Map.Entry<Integer, Integer> e : rideCount.entrySet()) {
            int rides = e.getValue();
            min = Math.min(min, rides);
            max = Math.max(max, rides);
            sum += rides;
            entries.add("P" + e.getKey() + " " + rides);
        }

        for (int i = 0; i < entries.size(); i += 8) {
            List<String> chunk = entries.subList(i, Math.min(i + 8, entries.size()));
            String value = col(BLUE, String.join("  ", chunk));
            row(b, (i == 0 ? label("FAHRTEN/P") : indent()) + value);
        }

        double avg = (double) sum / entries.size();
        row(b, indent() + "min " + col(BLUE, String.valueOf(min))
                + " / max " + col(BLUE, String.valueOf(max))
                + " / avg " + col(BLUE, String.format(Locale.ROOT, "%.1f", avg)));
    }

    private void events(StringBuilder b, List<String> recentEvents) {
        if (recentEvents.isEmpty()) {
            row(b, indent() + col(GRAY, "[-]"));
            return;
        }
        for (String event : recentEvents) {
            row(b, indent() + col(DIM, fit(event)));
        }
    }
}
