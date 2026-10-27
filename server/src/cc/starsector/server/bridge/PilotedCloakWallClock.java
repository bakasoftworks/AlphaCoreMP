package cc.starsector.server.bridge;

import java.util.IdentityHashMap;

import cc.starsector.server.runtime.ServerConfig;

/**
 * Piloted hulls' phase-cloak charge
 * tracker ticks WALL time, not fast-pass-dilated time — on the IN half only
 * (OUT engine-native both sides, see {@link #wallPaced}).
 *
 * <p>WHY: the engine's fast-time machinery advances a ship with per-ship {@code timeMult > 1}
 * multiple times per wall frame, and the cloak state
 * tracker accumulates RAW dt per advance
 * — so a phased hull's IN/OUT windows compress ~1.87-3x vs wall clock (measured:
 * dil_in=dil_out=1.87). Vanilla-SP hides this from the player with a
 * player-only GLOBAL 1/timeMult compensation that is
 * structurally impossible in MP (the world cannot slow for one seat) and is scrubbed by our
 * client as a correctness law. The wall clock restores the vanilla-player cycle FEEL on every human seat
 * by repacing the tracker itself: uniform across all seats, server-authoritative, zero desync.
 * AI/foe hulls stay engine-native BY DESIGN — vanilla
 * never compensates AI phase ships, so exempting them would CREATE a divergence.
 *
 *
 * <p>MECHANISM: one visit per piloted hull per frame, post-step ({@link
 * EngineBackedCombatStepBoundary#step}): compare the tracker's (state, elapsed) against the
 * previous visit; if the state is UNCHANGED and elapsed advanced more than one wall dt (+eps),
 * rewind {@code elapsed = prevElapsed + wallDt} via the {@link EngineReflectionBinding}
 * piloted-cloak family (raw field write, side-effect-free — threshold callbacks fire only in
 * the advance cascade, never at write time). State transitions resync
 * without writing (accepted one-frame slip &le; (timeMult-1)&middot;dt &asymp; 33 ms);
 * COOLDOWN/IDLE tick wall-true already (modifiers unapplied there),
 * so the delta gate no-ops them naturally. An implausible jump (&gt;
 * {@link #CAP_MULT}x wall dt — an external writer or a hitch) resyncs without writing rather
 * than fighting it.
 *
 * <p>FAIL-CLOSED: any reflective miss after a non-null cloak disables the lane for the process
 * with ONE log line (the fast-time-shim error idiom) — the pre-F2b dilated behavior is the
 * fallback, never a broken write. Kill-switch knob {@code cc.server.pilotedCloakWallClock}
 * (default ON — the fix IS the product; {@code false} = byte-identical pre-F2b behavior, the
 * A/B probe lever). Sim/serve thread only.
 */
final class PilotedCloakWallClock {

    /** Kill-switch: {@code true|false}, compiled default ON; invalid values THROW at class load. */
    static final boolean ENABLED = parseEnabled(ServerConfig.resolve(
            "cc.server.pilotedCloakWallClock", "CC_SERVER_PILOTED_CLOAK_WALL_CLOCK",
            "server.pilotedCloakWallClock", "true"));

    /** Rewind only when elapsed outran wall dt by this fraction of a frame (float-noise guard). */
    private static final float EPS_FRAC = 0.25f;
    /** Resync-don't-fight ceiling: a per-frame jump beyond this many wall frames is external. */
    private static final float CAP_MULT = 6f;
    /** Per-tracker record cap — trackers churn only at hull remint; clear() beyond this. */
    private static final int MAX_RECORDS = 64;

    /** Rollup cadence: first line early (a short fight must still emit), then sparse. */
    private static final long ROLLUP_FIRST_FRAMES = 600L;
    private static final long ROLLUP_EVERY_FRAMES = 3600L;

    private static final class Rec {
        Object state;
        float elapsed;
        long visits;
        long rewinds;
    }

    /** Previous visit per tracker object (identity: a reminted hull gets a fresh tracker). */
    private final IdentityHashMap<Object, Rec> lastByTracker = new IdentityHashMap<>();
    private boolean disabled;
    private long rewinds;
    private long visits;
    private boolean armedLogged;
    // Owned-walk stats (ECB feeds one call per post-step frame): kills the
    // diagnosability gap — owned=0 means the walk never saw the seat hull (misbind hypothesis (a)),
    // resolved<owned means wire-id lookup misses, visits<resolved means getPhaseCloak returned null.
    private long walkFrames;
    private long walkOwned;
    private long walkResolved;

    /** Whether the lane runs: knob ON and no reflective failure so far. */
    boolean active() {
        return ENABLED && !disabled;
    }

    /** Total rewinds applied this process (probe/debrief surface). */
    long rewinds() {
        return rewinds;
    }

    /**
     * One post-step visit of one piloted hull's cloak. Reads (state, elapsed) through the
     * reflection family, rewinds when the dilation signature is present, and updates the
     * per-tracker record. Any reflective miss disables the lane (fail-closed, one log line).
     */
    void visit(EngineReflectionBinding reflection, Object cloak, float wallDt) {
        if (!active() || cloak == null || wallDt <= 0f) {
            return;
        }
        Object tracker = reflection.cloakChargeTrackerOrNull(cloak);
        if (tracker == null) {
            disable("chargeTracker unresolved on " + cloak.getClass().getName());
            return;
        }
        Object state = reflection.cloakTrackerStateOrNull(tracker);
        Float elapsedBox = reflection.cloakTrackerElapsedOrNull(tracker);
        if (state == null || elapsedBox == null) {
            disable("tracker state/elapsed unresolved on " + tracker.getClass().getName());
            return;
        }
        float elapsed = elapsedBox.floatValue();
        if (lastByTracker.size() > MAX_RECORDS) {
            lastByTracker.clear();
        }
        visits++;
        Rec rec = lastByTracker.get(tracker);
        if (rec == null) {
            rec = new Rec();
            lastByTracker.put(tracker, rec);
        } else if (rec.state == state && wallPaced(state)) {
            float delta = elapsed - rec.elapsed;
            if (delta > wallDt * (1f + EPS_FRAC) && delta <= wallDt * CAP_MULT) {
                float rewound = rec.elapsed + wallDt;
                if (!reflection.cloakTrackerElapsedWrite(tracker, rewound)) {
                    disable("elapsed write failed on " + tracker.getClass().getName());
                    return;
                }
                rewinds++;
                rec.visits++;
                rec.rewinds++;
                rec.elapsed = rewound;
                if (!armedLogged) {
                    // The positive engagement proof — before this line existed,
                    // "ran healthy" and "never engaged" were indistinguishable.
                    armedLogged = true;
                    System.out.println("piloted-cloak-wall-clock ARMED: first rewind applied"
                            + " (delta=" + delta + " wallDt=" + wallDt + ")");
                }
                return;
            }
        }
        rec.visits++;
        rec.state = state;
        rec.elapsed = elapsed;
    }

    /**
     * One call per post-step owned-walk frame ({@link
     * EngineCombatBinding#pilotedCloakWallClockPostStep}): fold the walk shape and emit the
     * periodic greppable rollup ({@link #ROLLUP_FIRST_FRAMES} then every
     * {@link #ROLLUP_EVERY_FRAMES}). Counters are cumulative; per-hull pairs are visits/rewinds
     * per live tracker record.
     */
    void frameStats(int owned, int resolved) {
        walkFrames++;
        walkOwned += owned;
        walkResolved += resolved;
        if (walkFrames == ROLLUP_FIRST_FRAMES || walkFrames % ROLLUP_EVERY_FRAMES == 0L) {
            StringBuilder perHull = new StringBuilder();
            for (Rec rec : lastByTracker.values()) {
                if (perHull.length() > 0) {
                    perHull.append(' ');
                }
                perHull.append(rec.visits).append('/').append(rec.rewinds);
            }
            System.out.println("piloted-cloak-wall-clock rollup [frame=" + walkFrames + "]:"
                    + " owned=" + walkOwned + " resolved=" + walkResolved + " visits=" + visits
                    + " rewinds=" + rewinds + " hulls=" + lastByTracker.size()
                    + " per-hull[v/r]=" + (perHull.length() > 0 ? perHull : "-"));
        }
    }

    /**
     * The wall clock
     * paces the IN half ONLY — OUT ticks engine-native on BOTH sides. WHY: during OUT the hull's
     * weapons share the tracker's advance dt (timeMult 1+2·level for the whole ramp),
     * so wall-pacing OUT to ~30 wall ticks gave the pilot
     * ~1.0 s of WEAPON time inside the fade (vanilla 0.5 s) — the measured 10/18-tick
     * bursts. Engine-native OUT = exactly vanilla's 0.5 s ship-time in the fade (≈16.5 wall
     * ticks, the AI/world-relative 17/20 burst), AI parity restored. The tracker's state field IS
     * the public {@code ShipSystemAPI.SystemState} enum; its names are
     * public API names, so a name compare needs no reflective handle and no API import in this
     * tree. A non-enum state is never wall-paced (fail-closed: no write).
     */
    private static boolean wallPaced(Object state) {
        return state instanceof Enum && "IN".equals(((Enum<?>) state).name());
    }

    /** External fail-closed entry: the ECB call-site's catch routes engine-path throws here. */
    void disableOnError(String reason) {
        disable(reason);
    }

    private void disable(String reason) {
        if (!disabled) {
            disabled = true;
            System.out.println("piloted-cloak-wall-clock DISABLED (fail-closed; pre-F2b dilated"
                    + " behavior stands): " + reason);
        }
    }

    private static boolean parseEnabled(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalStateException(
                "cc.server.pilotedCloakWallClock must be true|false, got: " + raw);
    }
}
