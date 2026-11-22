package rollerCoaster;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Sole writer to stdout in interactive mode: live dashboard with waiting queue,
 * platform, track, seats, counters, fairness and the last five events. Colors
 * are only used in a real terminal and when they are not disabled.
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

    public static final int REFRESH_MS = 250;

    private static final int LABEL_WIDTH = 11;
    private static final int EVENTS = 5;
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-9;]*m");

    private final int port;
    private final int width;
    private final boolean interactive;
    private final boolean colors;
    private final String logPath;
    private final long rideMs;
    private final long startedNanos = System.nanoTime();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private boolean firstFrame = true;

    public ConsoleView(int port, int width, boolean interactive, boolean colors, String logPath, long rideMs) {
        this.port = port;
        this.width = Math.max(60, width);
        this.interactive = interactive;
        this.colors = colors && interactive;
        this.logPath = logPath;
        this.rideMs = rideMs;
        if (interactive) {
            System.out.print("\033[?1049h"); // alternativer Screen: alter Terminalinhalt bleibt erhalten
            System.out.print("\033[?25l");   // Cursor verstecken
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

        Set<Integer> passengers = new HashSet<>(s.waiting());
        s.seats().values().forEach(passengers::addAll);
        passengers.addAll(s.rideCount().keySet());

        StringBuilder b = new StringBuilder(4096);
        top(b, "ROLLER COASTER");
        row(b, label("CONFIG") + tag("PORT", port)
                + " " + tag("CAPACITY", s.capacity())
                + " " + tag("WAGONS", s.seats().size())
                + " " + tag("REFRESH", (1000 / REFRESH_MS) + " Hz"));
        row(b, label("LOG") + col(CYAN, shortLogPath()));
        row(b, label("UPTIME") + col(MAGENTA, elapsed()));
        row(b, label("LINK") + link(s.seats().size(), passengers.size()));
        middle(b);
        row(b, label("WAITING") + ids(s.waiting()));
        row(b, label("PLATFORM") + wagons(s.platform(), s.seats(), s.capacity()));
        row(b, label("TRACK") + track(s.track(), s.rideStartedNanos(), s.capacity()));
        middle(b);
        row(b, label("COUNTERS") + counters(s));
        middle(b);
        fairness(b, s.rideCount());
        middle(b);
        row(b, label("LAST " + EVENTS));
        events(b, s.recentEvents());
        middle(b);
        row(b, label("HINT") + col(GRAY, "Ctrl+C to exit  ·  wagons: logs/wagons.log  ·  metrics: logs/station.log"));
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
            System.out.print("\033[?1049l" + RESET + "\033[?25h\n");
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
            content = fit(plain); // truncate long lines without colors
            plain = stripAnsi(content);
        }
        b.append("│ ").append(content);
        b.append(" ".repeat(Math.max(0, width - 4 - plain.length())));
        b.append(" │\n");
    }

    private String tag(String key, Object value) {
        return col(GRAY, "[") + col(CYAN, key) + " " + col(MAGENTA, String.valueOf(value)) + col(GRAY, "]");
    }

    private String link(int wagonCount, int passengerCount) {
        return "SERVER " + online() + " online"
                + "   WAGON " + online() + " online (" + col(MAGENTA, String.valueOf(wagonCount)) + ")"
                + "   PASSENGERS " + online() + " online (" + col(MAGENTA, String.valueOf(passengerCount)) + ")";
    }

    private String online() {
        return col(BOLD_GREEN, "●");
    }

    private String shortLogPath() {
        try {
            Path p = Path.of(logPath);
            int n = p.getNameCount();
            return n > 2 ? p.subpath(n - 2, n).toString() : p.toString();
        } catch (RuntimeException e) {
            return logPath;
        }
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
        return String.join("  ", parts);
    }

    private String track(List<Integer> values, Map<Integer, Long> rideStartedNanos, int capacity) {
        if (values.isEmpty()) {
            return col(GRAY, "[-]");
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            int id = values.get(i);
            String head = i == 0 ? col(BOLD_YELLOW, "▶ ") : "";
            parts.add("[" + col(BOLD_YELLOW, "W" + id) + " " + head
                    + col(GRAY, capacity + "/" + capacity) + " "
                    + progress(rideStartedNanos.get(id)) + "]");
        }
        return String.join(col(GRAY, " → "), parts);
    }

    /** Echter Fahrtfortschritt: Station merkt sich die Abfahrtszeit, RIDE_MS kommt aus .env. */
    private String progress(Long startNanos) {
        if (startNanos == null) {
            return col(GRAY, "--");
        }
        long elapsedMs = Math.max(0, Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        double ratio = rideMs > 0 ? (double) elapsedMs / rideMs : 0.0;
        ratio = Math.max(0.0, Math.min(0.99, ratio));

        final int cells = 10;
        int filled = (int) Math.floor(ratio * cells);
        String full = colors ? "━" : "=";
        String tip = colors ? "╸" : ">";
        String empty = colors ? "─" : "-";
        return col(GREEN, full.repeat(filled))
                + col(BOLD_YELLOW, tip)
                + col(GRAY, empty.repeat(cells - filled - 1))
                + " " + col(MAGENTA, String.format(Locale.ROOT, "%2.0f%%", ratio * 100));
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
                + "   DEPARTURES " + col(MAGENTA, num(s.departures()))
                + "   COMPLETED " + col(MAGENTA, num(s.completedRides()));
    }

    private void fairness(StringBuilder b, Map<Integer, Integer> rideCount) {
        if (rideCount.isEmpty()) {
            row(b, label("RIDES/PAX") + col(GRAY, "[-]"));
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
            row(b, (i == 0 ? label("RIDES/PAX") : indent()) + value);
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
            row(b, indent() + col(DIM, event));
        }
    }
}
