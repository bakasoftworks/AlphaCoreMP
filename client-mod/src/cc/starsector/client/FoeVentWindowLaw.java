package cc.starsector.client;

/**
 * Foe replica VENT-WINDOW law: one native vent per wire vent window, never a restart on the tail.
 *
 *
 * <p>WHY: a play-test report ("station modules sometimes venting after combat" —
 * the vent itself is VANILLA): the replica's flux is PINNED every frame from the lagging
 * wire ({@code FoeStateLaneCoordinator} {@code ft.setCurrFlux(pose.currFlux())}) and the lane
 * then calls {@code ventFlux()} whenever the wire says venting and the tracker does not. Once the
 * LOCAL vent drains the pinned residue (18.9 flux) to 0 the native vent ENDS by itself, the next
 * frame's pin restores the residue and the old rule started a NEW vent with its start FX — every
 * frame until the wire END: {@code foe-fluxflags vent_begins} rose +8 / +14 inside the END window
 * of ONE serve vent (id 23, 5565–5823). The law: a wire vent window gets exactly ONE begin; once
 * the tracker has been SEEN venting inside the window, a later not-venting frame is the tail and
 * is suppressed (metered), never re-begun; the wire END clears the memory so the next window
 * begins fresh. A refused begin (the tracker never read venting after the call — overloaded,
 * ventRate ≤ 0) leaves the memory clear so the begin retries next frame, exactly as before.
 *
 * <p>Client-side (zero wire; the wire flag is still authority); adds no latency (the
 * begin edge is unchanged); the {@code disagree} meter is untouched so the tail divergence stays
 * visible. Caller: {@code FoeStateLaneCoordinator.maintainFoeFluxFlags}. Smoke:
 * {@code FoeVentWindowLawSmoke}.
 */
final class FoeVentWindowLaw {

    /** Nothing to do this frame (states agree, or an overloaded tracker cannot vent yet). */
    static final int VENT_ACT_NONE = 0;
    /** Wire venting, tracker idle, no begin seen this window ⇒ {@code ventFlux()} (native FX). */
    static final int VENT_ACT_BEGIN = 1;
    /** Wire venting, tracker idle, a begin WAS seen this window ⇒ the drained tail: hold, no restart. */
    static final int VENT_ACT_SUPPRESS_RESTART = 2;
    /** Wire not venting, tracker venting ⇒ {@code stopVenting()} (the wire END). */
    static final int VENT_ACT_STOP = 3;

    private FoeVentWindowLaw() {
    }

    /**
     * The per-frame verdict. {@code startedThisWindow} = the tracker has read venting at least
     * once since the wire flag last rose (the caller's per-foe memory, advanced each frame by
     * {@link #nextStarted}).
     */
    static int action(boolean authVent, boolean localVenting, boolean localOverloaded,
            boolean startedThisWindow) {
        if (authVent) {
            if (localVenting || localOverloaded) {
                return VENT_ACT_NONE; // already venting, or the engine would refuse (overloaded)
            }
            return startedThisWindow ? VENT_ACT_SUPPRESS_RESTART : VENT_ACT_BEGIN;
        }
        return localVenting ? VENT_ACT_STOP : VENT_ACT_NONE;
    }

    /**
     * The memory's next value AFTER this frame's action ran: true once the tracker reads venting
     * under a raised wire flag (a begin that TOOK, or a vent already running), false the moment
     * the wire flag is down (window closed — the next window begins fresh). A begin the engine
     * refused leaves the prior value (false) so the begin retries next frame.
     */
    static boolean nextStarted(boolean authVent, boolean localVentingAfterAction,
            boolean startedThisWindow) {
        if (!authVent) {
            return false;
        }
        return startedThisWindow || localVentingAfterAction;
    }
}
