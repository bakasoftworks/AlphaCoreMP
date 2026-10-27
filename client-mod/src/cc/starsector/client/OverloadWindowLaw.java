package cc.starsector.client;

/**
 * Overload WINDOW law: one slaved overload BEGIN per wire overload window, never a restart on the
 * tail.
 *
 * <p>WHY: both overload slaves ({@code PredictReconcileCombatPlugin.maintainOwnFlux} for the own
 * ship, {@code FoeStateLaneCoordinator.maintainFoeFluxFlags} for replicas) begin a local overload
 * whenever the wire flag reads overloaded and the tracker does not — a LEVEL test with no rising
 * edge. The local overload runs on the engine's own clock (stat-modified duration) while the wire
 * flag falls 100-300 ms late, so when the local overload expires FIRST the next fresh frame reads
 * {@code authOvl && !isOverloaded()} again and begins a SECOND full overload with its sound and
 * "Overloaded!" floaty — the vent-restart shape ({@link FoeVentWindowLaw}) on
 * the overload axis, owner-visible under lag. The law: a wire overload window gets exactly ONE
 * begin; once the tracker has been SEEN overloaded inside the window, a later not-overloaded frame
 * is the tail and is suppressed (metered), never re-begun; the wire's fall clears the memory so the
 * next window begins fresh. A refused begin (the tracker never read overloaded after the call)
 * leaves the memory clear so the begin retries next frame, exactly as before.
 *
 * <p>Client-side by the client-derivation rule (zero wire; the wire flag is still authority); adds no latency (the
 * begin edge is unchanged; a real new overload begins on its first fresh frame); the existing
 * disagree meters are untouched so the tail divergence stays visible. Wire-END handling (a local
 * overload the wire says is over) is the callers' existing code, not this law. Smoke:
 * {@code OverloadWindowLawSmoke}.
 */
final class OverloadWindowLaw {

    /** Nothing to do this frame (wire not overloaded, or the tracker already reads overloaded). */
    static final int OVL_ACT_NONE = 0;
    /** Wire overloaded, tracker idle, no begin seen this window ⇒ slave a local overload. */
    static final int OVL_ACT_BEGIN = 1;
    /** Wire overloaded, tracker idle, a begin WAS seen this window ⇒ the expired tail: hold. */
    static final int OVL_ACT_SUPPRESS_RESTART = 2;

    /** "No arc yet" sentinel for a {@code System.nanoTime} stamp (the own-hull lash stamp's rest value). */
    static final long NO_ARC_NANOS = Long.MIN_VALUE;

    private OverloadWindowLaw() {
    }

    /**
     * {@code true} iff a lash arc stamped at {@code lastArcNanos}
     * lies inside {@code windowNanos} before {@code nowNanos}. The sentinel is checked FIRST:
     * {@code now - Long.MIN_VALUE} flips the sign bit, so for any non-negative {@code nanoTime}
     * the bare subtraction read NEGATIVE (= "recent") until the JVM's first own lash hit and every
     * ordinary own overload wore the EMP colour + "Phase Field Disruption!" floaty.
     */
    static boolean arcWithinLookback(long lastArcNanos, long nowNanos, long windowNanos) {
        return lastArcNanos != NO_ARC_NANOS && nowNanos - lastArcNanos < windowNanos;
    }

    /**
     * The per-frame verdict. {@code begunThisWindow} = the tracker has read overloaded at least
     * once since the wire flag last rose (the caller's per-ship memory, advanced each frame by
     * {@link #nextBegun}).
     */
    static int action(boolean authOvl, boolean localOverloaded, boolean begunThisWindow) {
        if (!authOvl || localOverloaded) {
            return OVL_ACT_NONE;
        }
        return begunThisWindow ? OVL_ACT_SUPPRESS_RESTART : OVL_ACT_BEGIN;
    }

    /**
     * The memory's next value AFTER this frame's action ran: true once the tracker reads overloaded
     * under a raised wire flag (a begin that TOOK, or an overload already running), false the
     * moment the wire flag is down (window closed — the next window begins fresh). A begin the
     * engine refused leaves the prior value so the begin retries next frame.
     */
    static boolean nextBegun(boolean authOvl, boolean localOverloadedAfter, boolean prevBegun) {
        if (!authOvl) {
            return false;
        }
        return prevBegun || localOverloadedAfter;
    }
}
