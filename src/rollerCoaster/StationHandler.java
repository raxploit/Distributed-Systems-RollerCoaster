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

    // Letzte Ereignisse fuer den Dashboard-Ticker
    private final Deque<String> recentEvents = new ArrayDeque<>();
    private long eventSeq;
    private static final int RECENT_EVENTS = 5;

    // Zaehler fuer die Metrik-Ausgabe (keine Aenderung an der Thrift-IDL noetig)
    private long boardings;
    private long departures;
    private long completedRides;

    public StationHandler(int capacity, StationLog log) {
        this.capacity = capacity;
        this.log = log;
    }

    // ---------------- Passagiere ----------------

    @Override
    public synchronized int i_am_boarding(int passengerId) throws TException {
        waitingPassengers.addLast(passengerId);
        log.station("P" + passengerId + " wartet  | Queue: " + waitingPassengers);
        recordEvent("P" + passengerId + " joined queue");

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
        recordEvent("P" + passengerId + " boarded W" + wagonId + " (" + seats.get(wagonId).size() + "/" + capacity + ")");

        notifyAll(); // naechster Passagier oder volle-Wagen-Bedingung pruefen
        return wagonId;
    }

    @Override
    public synchronized void wait_for_ride_end(int passengerId) throws TException {
        int wagonId = passengerWagon.get(passengerId);
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
        recordEvent("P" + passengerId + " left W" + wagonId + " (ride " + rides + ")");
        notifyAll(); // Wagen wartet evtl. darauf, leer zu sein
    }

    // ---------------- Wagen ----------------

    @Override
    public synchronized void arrive(int wagonId) throws TException {
        seats.put(wagonId, new ArrayList<>());
        platformQueue.addLast(wagonId);
        log.station("W" + wagonId + " am Bahnsteig | Wagen-Queue: " + platformQueue);
        recordEvent("W" + wagonId + " arrived");
        notifyAll(); // wartende Passagiere koennen evtl. einsteigen

        // Warten, bis ich vorne am Bahnsteig bin UND voll
        while (!(Integer.valueOf(wagonId).equals(platformQueue.peekFirst()) && seats.get(wagonId).size() == capacity)) {
            waitUninterruptibly();
        }
    }

    @Override
    public synchronized void departure(int wagonId) throws TException {
        platformQueue.pollFirst();   // Bahnsteig frei fuer den naechsten Wagen
        track.addLast(wagonId);      // Abfahrtsreihenfolge merken
        log.station("W" + wagonId + " faehrt ab mit " + seats.get(wagonId) + " | Strecke: " + track);
        departures++;
        recordEvent("W" + wagonId + " departed " + seats.get(wagonId));
        notifyAll(); // naechster Wagen darf jetzt beladen werden
    }

    @Override
    public synchronized void ride_finished(int wagonId) throws TException {
        // Kein Ueberholen: erst entladen, wenn alle vorher abgefahrenen Wagen entladen sind
        while (!Integer.valueOf(wagonId).equals(track.peekFirst())) {
            waitUninterruptibly();
        }

        rideOver.add(wagonId);
        log.station("W" + wagonId + " zurueck, Passagiere steigen aus");
        recordEvent("W" + wagonId + " returned");
        notifyAll(); // weckt die Passagiere in wait_for_ride_end

        // Warten, bis alle c Passagiere ausgestiegen sind
        while (!seats.get(wagonId).isEmpty()) {
            waitUninterruptibly();
        }

        rideOver.remove(wagonId);
        track.pollFirst();
        completedRides++;
        log.station("W" + wagonId + " leer");
        recordEvent("W" + wagonId + " empty");
        notifyAll(); // naechster Wagen auf der Strecke darf entladen
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
                         List<String> recentEvents,
                         long boardings,
                         long departures,
                         long completedRides) {}

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
                List.copyOf(recentEvents),
                boardings, departures, completedRides);
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