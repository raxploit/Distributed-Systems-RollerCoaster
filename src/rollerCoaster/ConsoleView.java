package rollerCoaster;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
    private final String wagonLogPath;
    private final long rideMs;
    private final String laptopHost;
    private final String vmHost;
    private final long startedNanos = System.nanoTime();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private boolean firstFrame = true;

    public ConsoleView(int port, int width, boolean interactive, boolean colors, String logPath, long rideMs,
                       String laptopHost, String vmHost) {
        this.port = port;
        this.width = Math.max(60, width);
        this.interactive = interactive;
        this.colors = colors && interactive;
        this.logPath = logPath;
        this.wagonLogPath = wagonLogSibling(logPath);
        this.rideMs = rideMs;
        this.laptopHost = laptopHost;
        this.vmHost = vmHost;
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
        row(b, label("LOG") + "STATION " + col(CYAN, shortLog(logPath))
                + "   WAGONS " + col(CYAN, shortLog(wagonLogPath)));
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
        row(b, label("COORD") + coord(s));
        row(b, label("NODES") + nodes(s.seats().keySet(), s.rideCount().keySet()));
        row(b, label("BLOCKED") + blocked(s));
        middle(b);
        row(b, label("LAST " + EVENTS));
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

    /** Coordinator: notifyAll() ist der Herzschlag des einen synchronized-Monitors. */
    private String coord(StationHandler.Status s) {
        String count = col(MAGENTA, String.format(Locale.ROOT, "%,d", s.signalCount()));
        String last;
        if (s.lastSignalNanos() == 0 || s.lastSignalMethod().isEmpty()) {
            last = col(GRAY, "-");
        } else {
            long ageMs = Math.max(0, Duration.ofNanos(System.nanoTime() - s.lastSignalNanos()).toMillis());
            last = col(CYAN, s.lastSignalMethod())
                    + " " + col(GRAY, String.format(Locale.ROOT, "(%.1fs)", ageMs / 1000.0));
        }
        return "StationServer [Java] · 1 monitor · wait()/notifyAll() · signals " + count
                + " · last: " + last;
    }

    /** Physische Verteilung und Sprachmischung; leere IPs erscheinen als "?". */
    private String nodes(Set<Integer> wagonIds, Set<Integer> passengerIds) {
        return "[" + host(laptopHost, "LAPTOP") + "] Station (Java) + " + idRange(wagonIds, "W") + " (Python)"
                + "   |   [" + host(vmHost, "VM") + "] " + idRange(passengerIds, "P") + " (Java)";
    }

    private String host(String ip, String role) {
        return (ip == null || ip.isBlank() ? "?" : ip) + " " + role;
    }

    /** W1-W4 bei lueckenfreien IDs, sonst eine kompakte Liste. */
    private String idRange(Set<Integer> ids, String prefix) {
        if (ids.isEmpty()) {
            return "-";
        }
        List<Integer> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        int first = sorted.get(0);
        int last = sorted.get(sorted.size() - 1);
        if (sorted.size() == 1) {
            return prefix + first;
        }
        if (last - first == sorted.size() - 1) {
            return prefix + first + "-" + prefix + last;
        }
        if (sorted.size() > 6) {
            // Lückenhafte IDs kompakt als Bereich + Anzahl (sonst sprengt die Zeile das Layout)
            return prefix + first + "-" + prefix + last + " (" + sorted.size() + ")";
        }
        List<String> parts = new ArrayList<>();
        for (int id : sorted) {
            parts.add(prefix + id);
        }
        return String.join(",", parts);
    }

    /** Clients, die gerade im Monitor des Coordinators parken (aus dem Snapshot ableitbar). */
    private String blocked(StationHandler.Status s) {
        List<Integer> riding = new ArrayList<>();
        s.track().forEach(id -> {
            List<Integer> seats = s.seats().get(id);
            if (seats != null) {
                riding.addAll(seats);
            }
        });
        return blockedCall("i_am_boarding", "P", s.waiting())
                + " · " + blockedCall("arrive", "W", s.platform())
                + " · " + blockedCall("wait_for_ride_end", "P", riding);
    }

    private String blockedCall(String method, String prefix, List<Integer> ids) {
        List<Integer> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        int shown = Math.min(4, sorted.size());
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            parts.add(prefix + sorted.get(i));
        }
        String list = parts.isEmpty() ? "-" : String.join(",", parts);
        if (sorted.size() > shown) {
            list += "+" + (sorted.size() - shown);
        }
        return col(CYAN, method) + " " + list + " " + col(MAGENTA, "(" + sorted.size() + ")");
    }

    /** Wagen-Log liegt im selben Verzeichnis wie das Stations-Log. */
    private static String wagonLogSibling(String stationLog) {
        try {
            Path parent = Path.of(stationLog).getParent();
            return parent == null ? "wagons.log" : parent.resolve("wagons.log").toString();
        } catch (RuntimeException e) {
            return "wagons.log";
        }
    }

    private String shortLog(String path) {
        try {
            Path p = Path.of(path);
            int n = p.getNameCount();
            return n > 2 ? p.subpath(n - 2, n).toString() : p.toString();
        } catch (RuntimeException e) {
            return path;
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

        int min = Integer.MAX_VALUE;
        int max = 0;
        int minId = 0;
        int maxId = 0;
        int sum = 0;
        for (Map.Entry<Integer, Integer> e : rideCount.entrySet()) {
            int rides = e.getValue();
            if (rides < min) {
                min = rides;
                minId = e.getKey();
            }
            if (rides > max) {
                max = rides;
                maxId = e.getKey();
            }
            sum += rides;
        }

        double avg = (double) sum / rideCount.size();
        row(b, label("RIDES/PAX")
                + "min " + col(BLUE, String.valueOf(min)) + " (" + col(BLUE, "P" + minId) + ")"
                + " · max " + col(BLUE, String.valueOf(max)) + " (" + col(BLUE, "P" + maxId) + ")"
                + " · avg " + col(BLUE, String.format(Locale.ROOT, "%.1f", avg))
                + " · spread " + col(BLUE, String.valueOf(max - min)));

        List<String> chips = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : rideCount.entrySet()) {
            int rides = e.getValue();
            int delta = (int) Math.round(rides - avg);
            String arrow = delta > 0 ? "↑" + delta : delta < 0 ? "↓" + (-delta) : "→ ";
            chips.add(paxChip(e.getKey(), rides, arrow, delta));
        }

        for (int i = 0; i < chips.size(); i += 5) {
            List<String> chunk = chips.subList(i, Math.min(i + 5, chips.size()));
            row(b, indent() + String.join(" ", chunk));
        }
    }

    /** Eine fest breite Kachel pro Passagier: [P1  29 ↑2]. */
    private String paxChip(int id, int rides, String arrow, int delta) {
        String arrowColor = delta > 0 ? GREEN : delta < 0 ? BLUE : GRAY;
        return col(GRAY, "[")
                + col(CYAN, String.format(Locale.ROOT, "P%-2d", id)) + " "
                + col(MAGENTA, String.format(Locale.ROOT, "%2d", rides)) + " "
                + col(arrowColor, arrow)
                + col(GRAY, "]");
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
