package rollerCoaster;
import org.apache.thrift.TException;
import java.util.*;

/**
 * Zentrale Station (Monitor). Alle Methoden sind synchronized auf dieselbe Instanz.
 * Clients werden blockiert, indem ihr Thrift-Call in wait() haengt, bis ihre Bedingung gilt.
 * notifyAll() weckt nach jeder Zustandsaenderung alle Wartenden, die ihre Bedingung neu pruefen.
 */
public class StationHandler implements Station.Iface {

    private final int capacity; // c
    private final StationLog log;
    private final int expectedWagons; // Anzahl erwarteter Wagen fuer die sortierte Startphase
    private final Set<Integer> wagonsSeen = new HashSet<>();

    // Passagiere, die auf eine Fahrt warten (FIFO -> keine Starvation, faire Reihenfolge)
    private final Deque<Integer> waitingPassengers = new ArrayDeque<>();

    // Wagen am Bahnsteig in Ankunftsreihenfolge; nur der vorderste darf beladen werden
    private final Deque<Integer> platformQueue = new ArrayDeque<>();

    // Wagen auf der Strecke in Abfahrtsreihenfolge (eine Strecke -> kein Ueberholen)
    private final Deque<Integer> track = new ArrayDeque<>();

    // Welche Passagiere sitzen in welchem Wagen
    private final Map<Integer, List<Integer>> seats = new HashMap<>();
    private final Map<Integer, Integer> passengerWagon = new HashMap<>();

    // Wagen, deren Fahrt beendet ist und die gerade entladen werden
    private final Set<Integer> rideOver = new HashSet<>();

    // Nur fuer die Demo: Fairness sichtbar machen
    private final Map<Integer, Integer> rideCount = new TreeMap<>();

    // Nur fuer die Anzeige: wann ist ein Wagen abgefahren? (keine Sync-Logik)
    private final Map<Integer, Long> rideStartedNanos = new HashMap<>();

    // Letzte Ereignisse fuer den Dashboard-Ticker
    private final Deque<String> recentEvents = new ArrayDeque<>();
    private long eventSeq;
    private static final int RECENT_EVENTS = 5;

    // Nur fuer die Anzeige: notifyAll()-Aktivitaet des Monitors (keine Sync-Logik)
    private long signalCount;
    private String lastSignalMethod = "";
    private long lastSignalNanos;

    // Zaehler fuer die Metrik-Ausgabe (keine Aenderung an der Thrift-IDL noetig)
    private long boardings;
    private long departures;
    private long completedRides;

    public StationHandler(int capacity, StationLog log, int expectedWagons) {
        this.capacity = capacity;
        this.log = log;
        this.expectedWagons = expectedWagons;
    }

    // ---------------- Passagiere ----------------

    @Override
    public synchronized int i_am_boarding(int passengerId) throws TException {
        waitingPassengers.addLast(passengerId);
        log.station("P" + passengerId + " wartet  | Queue: " + waitingPassengers);
        recordEvent("i_am_boarding(P" + passengerId + ") → wait");

        // Warten, bis: ein Wagen beladen wird, er noch Platz hat UND ich vorne in der Schlange bin
        while (!(isLoadingWagonFree() && Integer.valueOf(passengerId).equals(waitingPassengers.peekFirst()))) {
            waitUninterruptibly();
        }

        waitingPassengers.pollFirst();
        int wagonId = platformQueue.peekFirst();
        seats.get(wagonId).add(passengerId);
        passengerWagon.put(passengerId, wagonId);
        log.station("P" + passengerId + " steigt in W" + wagonId + " ein " + seats.get(wagonId));
        boardings++;
        recordEvent("i_am_boarding(P" + passengerId + ") → W" + wagonId
                + " [" + seats.get(wagonId).size() + "/" + capacity + "]");

        signal("i_am_boarding"); // naechster Passagier oder volle-Wagen-Bedingung pruefen
        return wagonId;
    }

    @Override
    public synchronized void wait_for_ride_end(int passengerId) throws TException {
        int wagonId = passengerWagon.get(passengerId);
        recordEvent("wait_for_ride_end(P" + passengerId + ") → wait");
        while (!rideOver.contains(wagonId)) {
            waitUninterruptibly();
        }
    }

    @Override
    public synchronized void i_am_disembarking(int passengerId) throws TException {
        int wagonId = passengerWagon.remove(passengerId);
        seats.get(wagonId).remove(Integer.valueOf(passengerId));
        int rides = rideCount.merge(passengerId, 1, Integer::sum);
        log.station("P" + passengerId + " steigt aus W" + wagonId + " aus | Fahrten: " + rideCount);
        recordEvent("i_am_disembarking(P" + passengerId + ") ← W" + wagonId + " [ride " + rides + "]");
        signal("i_am_disembarking"); // Wagen wartet evtl. darauf, leer zu sein
    }

    // ---------------- Wagen ----------------

    @Override
    public synchronized void arrive(int wagonId) throws TException {
        rideStartedNanos.remove(wagonId); // Sicherheit: alte Fahrt nicht mehr anzeigen
        seats.put(wagonId, new ArrayList<>());
        if (expectedWagons > 0 && wagonsSeen.add(wagonId) && wagonsSeen.size() <= expectedWagons) {
            // Startphase: unabhängig vom Start-Timing immer 1, 2, 3, 4 ...
            List<Integer> ordered = new ArrayList<>(platformQueue);
            ordered.add(wagonId);
            Collections.sort(ordered);
            platformQueue.clear();
            platformQueue.addAll(ordered);
        } else {
            platformQueue.addLast(wagonId); // normaler Betrieb / Wagen-Neustart
        }
        log.station("W" + wagonId + " am Bahnsteig | Wagen-Queue: " + platformQueue);
        recordEvent("arrive(W" + wagonId + ") → queue");
        signal("arrive"); // wartende Passagiere koennen evtl. einsteigen

        // Warten, bis ich vorne am Bahnsteig bin UND voll
        while (!(Integer.valueOf(wagonId).equals(platformQueue.peekFirst()) && seats.get(wagonId).size() == capacity)) {
            waitUninterruptibly();
        }
    }

    @Override
    public synchronized void departure(int wagonId) throws TException {
        platformQueue.pollFirst();   // Bahnsteig frei fuer den naechsten Wagen
        track.addLast(wagonId);      // Abfahrtsreihenfolge merken
        rideStartedNanos.put(wagonId, System.nanoTime()); // Fortschritt fuer das Dashboard
        log.station("W" + wagonId + " faehrt ab mit " + seats.get(wagonId) + " | Strecke: " + track);
        departures++;
        recordEvent("departure(W" + wagonId + ") → " + seats.get(wagonId));
        signal("departure"); // naechster Wagen darf jetzt beladen werden
    }

    @Override
    public synchronized void ride_finished(int wagonId) throws TException {
        // Kein Ueberholen: erst entladen, wenn alle vorher abgefahrenen Wagen entladen sind
        while (!Integer.valueOf(wagonId).equals(track.peekFirst())) {
            waitUninterruptibly();
        }

        rideOver.add(wagonId);
        log.station("W" + wagonId + " zurueck, Passagiere steigen aus");
        recordEvent("ride_finished(W" + wagonId + ") → unloading");
        signal("ride_finished"); // weckt die Passagiere in wait_for_ride_end

        // Warten, bis alle c Passagiere ausgestiegen sind
        while (!seats.get(wagonId).isEmpty()) {
            waitUninterruptibly();
        }

        rideOver.remove(wagonId);
        track.pollFirst();
        rideStartedNanos.remove(wagonId);
        completedRides++;
        log.station("W" + wagonId + " leer");
        recordEvent("ride_finished(W" + wagonId + ") → empty");
        signal("ride_finished"); // naechster Wagen auf der Strecke darf entladen
    }

    // ---------------- Metriken ----------------

    /** Snapshot fuer die periodische Ausgabe; synchronized wie alle anderen Methoden. */
    public synchronized String stats() {
        return String.format(
                "[METRIC] c=%d waiting=%d platform=%d track=%d boardings=%d departures=%d rides=%d",
                capacity, waitingPassengers.size(), platformQueue.size(), track.size(),
                boardings, departures, completedRides);
    }

    /** Zustand fuer das Live-Dashboard; kopiert, damit die View nicht auf Live-Collections zugreift. */
    public record Status(int capacity,
                         List<Integer> waiting,
                         List<Integer> platform,
                         List<Integer> track,
                         Map<Integer, List<Integer>> seats,
                         Map<Integer, Integer> rideCount,
                         Map<Integer, Long> rideStartedNanos,
                         List<String> recentEvents,
                         long boardings,
                         long departures,
                         long completedRides,
                          long signalCount,
                          String lastSignalMethod,
                          long lastSignalNanos) {}

    public synchronized Status snapshot() {
        Map<Integer, List<Integer>> seatsCopy = new LinkedHashMap<>();
        seats.forEach((id, passengers) -> seatsCopy.put(id, List.copyOf(passengers)));
        return new Status(
                capacity,
                List.copyOf(waitingPassengers),
                List.copyOf(platformQueue),
                List.copyOf(track),
                Collections.unmodifiableMap(seatsCopy),
                Collections.unmodifiableMap(new TreeMap<>(rideCount)),
                Map.copyOf(rideStartedNanos),
                List.copyOf(recentEvents),
                boardings, departures, completedRides,
                signalCount, lastSignalMethod, lastSignalNanos);
    }

    /** Ein Signal an alle Wartenden – mit Zaehler und Zeitstempel fuer das Dashboard. */
    private void signal(String method) {
        signalCount++;
        lastSignalMethod = method;
        lastSignalNanos = System.nanoTime();
        notifyAll();
    }

    private void recordEvent(String shortText) {
        eventSeq++;
        recentEvents.addLast(String.format(Locale.ROOT, "#%04d %s", eventSeq, shortText));
        while (recentEvents.size() > RECENT_EVENTS) {
            recentEvents.pollFirst();
        }
    }

    // ---------------- Hilfsmethoden ----------------

    private boolean isLoadingWagonFree() {
        Integer w = platformQueue.peekFirst();
        return w != null && seats.get(w).size() < capacity;
    }

    private void waitUninterruptibly() {
        try {
            wait();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}