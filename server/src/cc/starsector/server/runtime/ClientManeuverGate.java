package cc.starsector.server.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The shared seam that closes the live authoritative MANEUVER round-trip
 * — the movement sibling of {@link ClientIntentGate}.
 *
 * <p>It bridges the two halves on the live real-engine path for movement: the
 * {@link LiveWorldSnapshotSocketPublisher} (which reads one inbound
 * {@link LiveManeuverIntent} off the socket and {@link #receive}s it here before the
 * publish loop) and the live combat step (the engine-backed gated command-authority
 * maneuver wrapper, or a game-free source) which consults {@link #maneuverActive()}
 * each tick. When the remote client asked to hold a maneuver, the gate is active and
 * the server issues the command-authority movement command (
 * {@code ShipAPI.giveCommand}) that moves the commanded ship; when the client sent a
 * hold/no intent, the gate stays inactive and no maneuver is issued — so the motion
 * change in the returned snapshot is attributable to the client's <em>own</em>
 * command, not a server script.
 *
 * <p>One {@code receive} per window (held semantics). The application counter lets the
 * publisher report, and tests assert, how many ticks actually issued the gated command.
 */
public final class ClientManeuverGate {

    private volatile LiveManeuverIntent received;
    private final AtomicInteger maneuverApplications = new AtomicInteger();

    /** Record the intent the publisher read off the socket. Idempotent per window. */
    public void receive(LiveManeuverIntent intent) {
        this.received = Objects.requireNonNull(intent, "intent");
    }

    /** True once the publisher has read and recorded an inbound intent. */
    public boolean intentReceived() {
        return received != null;
    }

    /** The received intent, or {@code null} if none has crossed yet. */
    public LiveManeuverIntent intentOrNull() {
        return received;
    }

    /**
     * Whether the live step should issue the command-authority maneuver this tick:
     * true iff a remote intent was received and it requested a maneuver.
     */
    public boolean maneuverActive() {
        LiveManeuverIntent intent = received;
        return intent != null && intent.maneuver();
    }

    /** Owned ship the intent targets, or {@code -1} if no intent received. */
    public long shipId() {
        LiveManeuverIntent intent = received;
        return intent == null ? -1L : intent.shipId();
    }

    /** Movement ordinal the intent requested, or {@code -1} if no intent received. */
    public int movementOrdinal() {
        LiveManeuverIntent intent = received;
        return intent == null ? -1 : intent.movementOrdinal();
    }

    /** Count one tick on which the gated command-authority maneuver was issued. */
    public void recordManeuverApplied() {
        maneuverApplications.incrementAndGet();
    }

    /** Number of ticks that issued the gated maneuver across the publish window. */
    public int maneuverApplications() {
        return maneuverApplications.get();
    }
}
