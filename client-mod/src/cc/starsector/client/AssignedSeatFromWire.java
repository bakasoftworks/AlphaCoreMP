package cc.starsector.client;

/**
 * The client-side holder of the SERVER-ASSIGNED
 * seat — seeded from the WELCOME's {@code boundShipId} tail at BOTH admit lanes (initial connect
 * + B4 re-admit), the {@link ServerTickRate#seedFromWire} / {@link MatchConfigFromWire}
 * discipline: the wire is the ONE source, no sysprop, no io (loader-B-legal by construction).
 *
 * <p>Consumer: {@code MatchMissionBuilder.resolveOwnShipId()} under the AUTO knob
 * ({@code cc.predict.shipId=0}, the kit bat's pin). <b>Timing (measured on two live
 * joins):</b> the premise "the WELCOME completes at app load, the mission is built later" is
 * FALSE — the engine's title screen defines the mission AT application load
 * 0.1–1 s after the connect thread
 * starts, while attempt 1 is still in flight (its WELCOME landed +79 ms / +379 ms after the
 * thread start; the build came first BOTH times) — so the build fielded seat 1 and the init
 * race guard refused the bind on every first join ("leave and re-enter"). Hence
 * {@link #boundShipIdOrZeroAfterFirstAttempt}: the builder waits (bounded) for attempt 1 to
 * SETTLE — WELCOME seeded, or failed/gave up — before falling back. Server down at launch
 * (attempt 1 fails fast) keeps today's shape: deterministic fallback, a later WELCOME seeds
 * the holder for the NEXT build, the guard refuses a stale one (fail-closed, no hull swap).
 *
 * <p>0 = no assignment seen yet (the pre-admit sentinel — also what a lane that never assigns
 * would deliver); a non-positive wire value never seeds, so a reader always sees either the
 * sentinel or a concrete seat.
 */
public final class AssignedSeatFromWire {

    private static volatile long boundShipId;
    /** Set by {@code CcClientModPlugin.dialPredictAtJoin} before its thread starts. */
    private static volatile boolean connectStarted;
    /** Attempt 1 landed (session published) or failed/gave up — the wait's release. */
    private static volatile boolean firstAttemptSettled;

    private AssignedSeatFromWire() {
    }

    /**
     * A connect thread is about to start — arm the first-attempt wait.
     * The dial runs at the Multiplayer Test click, and the caller builds the mission right after
     * (same thread), so the wait binds on every click that dials. {@code dropSeed} = a RE-dial
     * (the prior session is dead): the old seat seed is stale — the fresh HELLO may land another
     * seat — so it clears and the build waits for the new WELCOME like a first join.
     */
    public static void markConnectStarted(boolean dropSeed) {
        if (dropSeed) {
            boundShipId = 0L;
        }
        firstAttemptSettled = false;
        connectStarted = true;
    }

    /** Attempt 1 has a verdict (called AFTER the session is published / the seed is
     * adopted, so a released waiter always sees the seed). Idempotent. */
    public static void markFirstAttemptSettled() {
        firstAttemptSettled = true;
    }

    /**
     * {@link #boundShipIdOrZero()}, but when the app-load connect is in flight and
     * attempt 1 has not settled yet, block up to {@code maxWaitMillis} for it (25 ms polls; the
     * caller is the engine's load thread, so the cost is a bounded pause on the loading screen).
     * Returns immediately when no connect was started (verify harness, explicit-seat lanes),
     * when a seed is already present, or once attempt 1 has settled. Logs the wait when it
     * actually happened, with the outcome.
     */
    public static long boundShipIdOrZeroAfterFirstAttempt(long maxWaitMillis) {
        if (boundShipId != 0L || !connectStarted || firstAttemptSettled) {
            return boundShipId;
        }
        long t0 = System.nanoTime();
        long deadline = t0 + maxWaitMillis * 1_000_000L;
        while (boundShipId == 0L && !firstAttemptSettled && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long waitedMs = (System.nanoTime() - t0) / 1_000_000L;
        PilotWatchLog.info("cc-assignedseat first-attempt wait " + waitedMs + "ms -> boundShipId="
                + boundShipId + (firstAttemptSettled ? " (attempt 1 settled)"
                        : boundShipId != 0L ? " (seeded)" : " (deadline " + maxWaitMillis
                                + "ms - falling back; a later WELCOME seeds the next build)"));
        return boundShipId;
    }

    /**
     * Adopt the WELCOME's {@code boundShipId} (re-seedable on every admit — the wire wins).
     * 0 is the legal "no seat bound" sentinel, not an error: keep the current value quietly
     * (the AUTO connect lane already treats an AUTO admit with 0 as a fail-closed reject
     * before this can matter). Sink = {@link PilotWatchLog} (pre-warmed at application load).
     */
    public static void seedFromWire(long wireBoundShipId) {
        if (wireBoundShipId <= 0L) {
            return; // no-assignment sentinel — nothing to adopt, keep the prior seed
        }
        long prior = boundShipId;
        boundShipId = wireBoundShipId;
        if (prior != 0L && prior != wireBoundShipId) {
            PilotWatchLog.info("cc-assignedseat wire_seed_override prior=" + prior
                    + " boundShipId=" + wireBoundShipId);
        }
    }

    /** The server-assigned seat's ship id, or 0 when no admit has delivered one yet. */
    public static long boundShipIdOrZero() {
        return boundShipId;
    }
}
