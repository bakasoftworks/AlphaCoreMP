package cc.starsector.server.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The shared seam that closes the live authoritative round-trip.
 *
 * <p>It bridges the two halves that were never connected on the live real-engine
 * path: the {@link LiveWorldSnapshotSocketPublisher} (now bidirectional — it reads
 * one inbound {@link LiveClientIntent} off the socket and {@link #receive}s it here
 * before the publish loop) and the live combat step (the engine-backed gated
 * command-authority step wrapper, or a game-free source) which consults
 * {@link #fireActive()} each tick. When the remote client asked to hold fire, the
 * gate is active and the server issues the command-authority {@code FIRE}
 * that produces the beam crossing back; when the client sent a hold/no intent, the
 * gate stays inactive and no effect crosses — so the effect in the returned snapshot
 * is attributable to the client's <em>own</em> command, not a server script.
 *
 * <p>One {@code receive} per window (held semantics). The fire-application counter
 * lets the publisher report, and tests assert, how many ticks actually issued the
 * gated command.
 */
public final class ClientIntentGate {

    private volatile LiveClientIntent received;
    private final AtomicInteger fireApplications = new AtomicInteger();

    /** Record the intent the publisher read off the socket. Idempotent per window. */
    public void receive(LiveClientIntent intent) {
        this.received = Objects.requireNonNull(intent, "intent");
    }

    /** True once the publisher has read and recorded an inbound intent. */
    public boolean intentReceived() {
        return received != null;
    }

    /** The received intent, or {@code null} if none has crossed yet. */
    public LiveClientIntent intentOrNull() {
        return received;
    }

    /**
     * Whether the live step should issue the command-authority FIRE this tick:
     * true iff a remote intent was received and it requested fire.
     */
    public boolean fireActive() {
        LiveClientIntent intent = received;
        return intent != null && intent.fire();
    }

    /** Owned ship the intent targets, or {@code -1} if no intent received. */
    public long shipId() {
        LiveClientIntent intent = received;
        return intent == null ? -1L : intent.shipId();
    }

    /** Weapon group the intent targets, or {@code -1} if no intent received. */
    public int weaponGroup() {
        LiveClientIntent intent = received;
        return intent == null ? -1 : intent.weaponGroup();
    }

    /** Count one tick on which the gated command-authority FIRE was issued. */
    public void recordFireApplied() {
        fireApplications.incrementAndGet();
    }

    /** Number of ticks that issued the gated FIRE across the publish window. */
    public int fireApplications() {
        return fireApplications.get();
    }
}
