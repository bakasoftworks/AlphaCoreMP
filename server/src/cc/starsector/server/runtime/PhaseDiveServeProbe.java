package cc.starsector.server.runtime;

import static cc.starsector.server.runtime.ServeProbeSupport.call;
import static cc.starsector.server.runtime.ServeProbeSupport.floatOf;
import static cc.starsector.server.runtime.ServeProbeSupport.hullIdOf;

import cc.starsector.server.protocol.PhaseDiveEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The F4b+F4c phase-dive serve probe, engine-native:
 * per-frame rising-edge read of the engine's own {@code PhaseAnchor$PhaseAnchorScript}
 * listener on anchor hulls — the dive truth-source is the script's public
 * {@code emergencyDive} field, never a poll-and-guess detector.
 *
 * <p>Three jobs, all observe-or-one-shot:
 * <ul>
 * <li><b>F4b eligibility log</b> — at every tracked anchor hull's death/roster-drop, one
 *   {@code phase_anchor_gate} line naming the intercept's exact inputs:
 *   latch key presence, currentCR, deployCost,
 *   fleetMember presence, hp, showFloaty — settles the live CR wiring with data
 *   (CR values are UNCONSTRAINED by prior rulings; a seed fix waits on this line).</li>
 * <li><b>F4c dive-start edge</b> — on {@code emergencyDive} false→true: one
 *   {@code phase_anchor_dive_start} line, the wire id latched for the DEPARTED despawn
 *   override (a dived hull leaves at HP=1, which reads under the detector's DESTROYED
 *   threshold; the latch is consumed one-shot at the paired despawn), and one
 *   {@link PhaseDiveEvent} queued for the ctrl-lane DIVE frame.</li>
 * <li><b>F5 stall-driver walk</b> — a live test proved the dive STARTS
 *   live but stalls at one advance's worth of progress with no per-frame record. From
 *   dive-start on, bounded {@code phase_anchor_dive_walk} rows carry the full discriminator
 *   set from the offline probe: a probe-owned dynamic-proxy {@link java.lang.reflect.Proxy}
 *   {@code AdvanceableListener} on the same hull counts engine-driven listener advances and
 *   records the last {@code amount} (H1 advance-not-called vs H2 amount==0), plus per-row
 *   {@code getChargeUpDur()} (H3), ship+global time mults, script-still-listed, retreating,
 *   phased, alive, hp, flux, location. Dense for the first {@link #WALK_DENSE_ROWS} ticks,
 *   then every {@link #WALK_SPARSE_EVERY}th, hard cap {@link #WALK_MAX_ROWS} — a healthy
 *   ~61-frame dive fits the dense window; only a stall pays the sparse tail.</li>
 * </ul>
 *
 * <p>Anchor detection is listener-truth, not variant-id guesswork: one
 * {@code getListeners(PhaseAnchorScript.class)} check per newly seen ship (verdict cached
 * by identity), per-frame field reads only on actual anchor hulls — today exactly the seat
 * grendel; zero steady-state cost when none are fielded. All engine reads are reflective
 * (the serve path never holds API-typed refs). Fail-soft: reflective misses log up to
 * {@link ServeProbeSupport.FailSoft#FAIL_LOG_MAX} rows and the probe goes quiet — it must
 * never break the serve loop. Log-only stdout (write-only sink); single-line rows.
 *
 * Own-file per the PersistentPilotIntentCombatServer growth-anchor law:
 * PPICS carries only the field + tick delegate call +
 * the two drains.
 */
final class PhaseDiveServeProbe {

    private static final String SCRIPT_CLASS =
            "com.fs.starfarer.api.impl.hullmods.PhaseAnchor$PhaseAnchorScript";
    private static final String ADVANCEABLE_LISTENER =
            "com.fs.starfarer.api.combat.listeners.AdvanceableListener";
    /** The engine's battle-global one-shot latch key. */
    private static final String PHASE_ANCHOR_CAN_DIVE_KEY = "phaseAnchor_canDive";
    /** F5 walk cadence: every tick for the dense window, then sparse, then hard-capped. */
    private static final int WALK_DENSE_ROWS = 90;
    private static final int WALK_SPARSE_EVERY = 60;
    private static final int WALK_MAX_ROWS = 240;

    private final ServeProbeSupport.FailSoft failSoft = new ServeProbeSupport.FailSoft("phase-dive");
    private boolean resolved;
    private Class<?> scriptClass;
    private Class<?> advanceableListenerClass;
    private Method getListeners;
    private Method addListener;
    private Method getCustomData;
    private Object engine;

    private final Map<Object, Tracked> trackedByShip = new IdentityHashMap<>();
    private final Set<Object> nonAnchorShips =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Long> diveLatchedWireIds = new LinkedHashSet<>();
    private final List<PhaseDiveEvent> pendingDiveStarts = new ArrayList<>();

    /** Per-anchor-hull edge state + last-good gate samples (for the roster-drop line). */
    private static final class Tracked {
        Object script;
        long wireId = -1L;
        boolean diveStarted;
        boolean gateLogged;
        boolean lastCanDiveKeyPresent;
        float lastCr = Float.NaN;
        float lastDeployCost = Float.NaN;
        boolean lastFleetMemberPresent;
        float lastHp = Float.NaN;
        boolean lastShowFloaty;
        float lastDiveProgress = Float.NaN;
        AdvanceCounter counter;
        int walkRows;
        int ticksSinceWalkRow;
        long advancesAtLastWalkRow;
        float progressAtLastWalkRow = Float.NaN;
    }

    /**
     * F5 discriminator: engine-driven advance counter installed on the anchor hull via a
     * dynamic proxy over the API {@code AdvanceableListener} (the probe's (ii) pattern,
     * ported to the serve path). Serve loop + engine advance share one thread — plain fields.
     * The Object trio rides the shared {@link ServeProbeSupport.ListenerHandler} base.
     */
    private static final class AdvanceCounter extends ServeProbeSupport.ListenerHandler {
        long advances;
        float lastAmount = Float.NaN;

        AdvanceCounter() {
            super("cc-phase-dive-advance-counter");
        }

        @Override
        void onCall(String method, Object[] args) {
            if ("advance".equals(method)) {
                advances++;
                if (args != null && args.length == 1 && args[0] instanceof Number) {
                    lastAmount = ((Number) args[0]).floatValue();
                }
            }
        }
    }

    /**
     * Per-frame scan. {@code shipsSupplier} is the binding's live ship→positionShipId map;
     * {@code wireIdByPositionShipId} the snapshot source's positionShipId→wire-id map (the
     * L2863/L2883 composed idiom) — suppliers so off-path ticks never pay a map build.
     */
    void tick(long simTick, Supplier<Map<Object, Long>> shipsSupplier,
            Supplier<Map<Long, Long>> wireIdByPositionShipId) {
        if (failSoft.quiet()) {
            return;
        }
        Map<Object, Long> ships = shipsSupplier.get();
        if (ships == null) {
            return;
        }
        try {
            logDroppedTracked(simTick, ships);
            pruneNonAnchorShips(simTick, ships);
            Map<Long, Long> wireIds = null;
            for (Map.Entry<Object, Long> entry : ships.entrySet()) {
                Object ship = entry.getKey();
                if (ship == null || nonAnchorShips.contains(ship)) {
                    continue;
                }
                Tracked t = trackedByShip.get(ship);
                if (t == null) {
                    t = enrollOrSkip(simTick, ship);
                    if (t == null) {
                        continue;
                    }
                }
                if (wireIds == null) {
                    wireIds = wireIdByPositionShipId.get();
                }
                long wireId = ServeProbeSupport.wireIdOfPosition(entry.getValue(), wireIds);
                if (wireId > 0L) {
                    t.wireId = wireId;
                }
                sample(simTick, ship, t);
            }
        } catch (Throwable failure) {
            failSoft.fail(simTick, failure);
        }
    }

    /**
     * One-shot take: true exactly once per latched dive, at the despawn it pairs with —
     * the EDGE-not-LEVEL law. Wire ids REBIND to the refield hull at respawn, so the
     * old LEVEL read ({@code diveLatchedWireIds()}) misclassified every post-respawn death
     * of the once-dived seat as DEPARTED (own client presented nothing ⇒
     * duplicate grendel). One dive ⇒ one despawn ⇒ take-once is exact.
     */
    boolean takeDiveLatch(long wireId) {
        return diveLatchedWireIds.remove(wireId);
    }

    /** Take-and-clear this tick's dive starts (the F4d ctrl-lane frames). */
    List<PhaseDiveEvent> drainDiveStarts() {
        if (pendingDiveStarts.isEmpty()) {
            return List.of();
        }
        List<PhaseDiveEvent> starts = List.copyOf(pendingDiveStarts);
        pendingDiveStarts.clear();
        return starts;
    }

    /** One-time getListeners check per new ship; caches the anchor/non-anchor verdict. */
    private Tracked enrollOrSkip(long simTick, Object ship) throws Exception {
        if (!resolved) {
            resolve(ship);
        }
        List<?> scripts = (List<?>) getListeners.invoke(ship, scriptClass);
        if (scripts == null || scripts.size() != 1) {
            nonAnchorShips.add(ship);
            return null;
        }
        Tracked t = new Tracked();
        t.script = scripts.get(0);
        installAdvanceCounter(simTick, ship, t);
        trackedByShip.put(ship, t);
        System.out.println("phase-dive probe track tick=" + simTick
                + " ship=" + hullIdOf(ship)
                + " advance_counter=" + (t.counter != null));
        return t;
    }

    /**
     * F5: observe-only proxy listener on the anchor hull — its {@code advance} does nothing
     * but count. Fail-soft: a miss logs once and the walk rows carry {@code proxy_adv=-1}.
     */
    private void installAdvanceCounter(long simTick, Object ship, Tracked t) {
        try {
            AdvanceCounter counter = new AdvanceCounter();
            ServeProbeSupport.installListener(ship.getClass().getClassLoader(),
                    advanceableListenerClass, counter, addListener, ship);
            t.counter = counter;
        } catch (Throwable failure) {
            System.out.println("phase-dive probe advance_counter_install_fail tick=" + simTick
                    + " " + failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    private void resolve(Object ship) throws Exception {
        ClassLoader loader = ship.getClass().getClassLoader();
        scriptClass = Class.forName(SCRIPT_CLASS, false, loader);
        advanceableListenerClass = Class.forName(ADVANCEABLE_LISTENER, false, loader);
        getListeners = ship.getClass().getMethod("getListeners", Class.class);
        addListener = ship.getClass().getMethod("addListener", Object.class);
        ServeProbeSupport.EngineHandle handle = ServeProbeSupport.combatEngine(loader);
        engine = handle.instance();
        getCustomData = handle.type().getMethod("getCustomData");
        resolved = true;
    }

    /** One tracked hull's per-frame read: gate samples, death edge, dive edge, nudge. */
    private void sample(long simTick, Object ship, Tracked t) throws Exception {
        boolean emergencyDive = boolField(t.script, "emergencyDive");
        float diveProgress = floatField(t.script, "diveProgress");
        t.lastHp = floatOf(call(ship, "getHitpoints"));
        t.lastCr = floatOf(call(ship, "getCurrentCR"));
        Object fleetMember = call(ship, "getFleetMember");
        t.lastFleetMemberPresent = fleetMember != null;
        t.lastDeployCost = fleetMember == null ? 0f : floatOf(call(fleetMember, "getDeployCost"));
        Object fluxTracker = call(ship, "getFluxTracker");
        t.lastShowFloaty = fluxTracker != null
                && Boolean.TRUE.equals(call(fluxTracker, "showFloaty"));
        Map<?, ?> customData = (Map<?, ?>) getCustomData.invoke(engine);
        t.lastCanDiveKeyPresent =
                customData != null && customData.containsKey(PHASE_ANCHOR_CAN_DIVE_KEY);
        t.lastDiveProgress = diveProgress;
        if (!t.gateLogged && !Boolean.TRUE.equals(call(ship, "isAlive"))) {
            logGate(simTick, t, "dead", emergencyDive);
        }
        if (emergencyDive && !t.diveStarted) {
            t.diveStarted = true;
            System.out.println("phase-dive probe phase_anchor_dive_start tick=" + simTick
                    + " wire_id=" + t.wireId + " show_floaty=" + t.lastShowFloaty
                    + " dive_progress=" + diveProgress + " cr=" + t.lastCr);
            if (t.wireId > 0L) {
                diveLatchedWireIds.add(t.wireId);
                pendingDiveStarts.add(new PhaseDiveEvent(t.wireId, simTick));
            } else {
                System.out.println("phase-dive probe dive_start_unmapped tick=" + simTick
                        + " ship=" + hullIdOf(ship) + " — no wire id; no DIVE frame,"
                        + " no despawn override for this dive");
            }
        }
        if (t.diveStarted) {
            walk(simTick, ship, t, diveProgress);
        }
    }

    /**
     * F5 per-frame dive walk (bounded; see class doc). Emission: dense window every tick,
     * then every {@link #WALK_SPARSE_EVERY}th, stop at {@link #WALK_MAX_ROWS}. The row set
     * splits the stall four ways: {@code d_adv==0} = engine stopped calling this hull's
     * listeners (H1); {@code d_adv>0, amount==0} = zeroed advance amount (H2);
     * {@code charge_up_dur==0} = zeroed charge-up (H3); all healthy yet {@code d_prog==0}
     * = script-specific skip (check {@code script_listed}).
     */
    private void walk(long simTick, Object ship, Tracked t, float diveProgress) {
        if (t.walkRows >= WALK_MAX_ROWS) {
            return;
        }
        t.ticksSinceWalkRow++;
        boolean due = t.walkRows < WALK_DENSE_ROWS || t.ticksSinceWalkRow >= WALK_SPARSE_EVERY;
        if (!due) {
            return;
        }
        try {
            walkRow(simTick, ship, t, diveProgress);
        } catch (Throwable failure) {
            // Own fail-soft: a walk-only reflective miss must not burn the probe's
            // FAIL_LOG_MAX budget and silence the gate/dive_start/nudge lanes.
            t.walkRows = WALK_MAX_ROWS;
            System.out.println("phase-dive probe walk_fail tick=" + simTick
                    + " wire_id=" + t.wireId + " [walk disabled] "
                    + failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    private void walkRow(long simTick, Object ship, Tracked t, float diveProgress)
            throws Exception {
        long adv = t.counter == null ? -1L : t.counter.advances;
        long dAdv = t.counter == null ? -1L : adv - t.advancesAtLastWalkRow;
        float dProg = Float.isNaN(t.progressAtLastWalkRow)
                ? diveProgress : diveProgress - t.progressAtLastWalkRow;
        Object cloak = call(ship, "getPhaseCloak");
        float chargeUpDur = cloak == null ? Float.NaN : floatOf(call(cloak, "getChargeUpDur"));
        Object stats = call(ship, "getMutableStats");
        Object shipTimeMult = stats == null ? null : call(stats, "getTimeMult");
        float shipMult = shipTimeMult == null
                ? Float.NaN : floatOf(call(shipTimeMult, "getModifiedValue"));
        Object globalTimeMult = call(engine, "getTimeMult");
        float globalMult = globalTimeMult == null
                ? Float.NaN : floatOf(call(globalTimeMult, "getModifiedValue"));
        List<?> scripts = (List<?>) getListeners.invoke(ship, scriptClass);
        Object fluxTracker = call(ship, "getFluxTracker");
        float fluxLevel = fluxTracker == null ? Float.NaN : floatOf(call(fluxTracker, "getFluxLevel"));
        Object loc = call(ship, "getLocation");
        float locX = loc == null ? Float.NaN : loc.getClass().getField("x").getFloat(loc);
        float locY = loc == null ? Float.NaN : loc.getClass().getField("y").getFloat(loc);
        // The live reaper-gate discriminator: offline the CFM
        // reaper removes the dived hull ONE frame past terminus with these gates healthy, so
        // the live non-removal must show here as either owner!=0 (G3 sentinel) or
        // mgr_attached=false (G1 — two-call getFleetManager(int) identity: a REGISTERED
        // (= advanced, reap-capable) manager is list-returned both calls; an unregistered
        // owner mints a fresh DETACHED throwaway per call).
        int owner = ((Number) call(ship, "getOwner")).intValue();
        Method getFleetManager = engine.getClass().getMethod("getFleetManager", int.class);
        boolean mgrAttached = getFleetManager.invoke(engine, owner)
                == getFleetManager.invoke(engine, owner);
        System.out.println("phase-dive probe phase_anchor_dive_walk tick=" + simTick
                + " wire_id=" + t.wireId
                + " progress=" + diveProgress
                + " d_prog=" + dProg
                + " adv=" + adv
                + " d_adv=" + dAdv
                + " last_amount=" + (t.counter == null ? Float.NaN : t.counter.lastAmount)
                + " charge_up_dur=" + chargeUpDur
                + " ship_time_mult=" + shipMult
                + " global_time_mult=" + globalMult
                + " script_listed=" + (scripts != null && scripts.contains(t.script))
                + " retreating=" + call(ship, "isRetreating")
                + " phased=" + call(ship, "isPhased")
                + " alive=" + call(ship, "isAlive")
                + " owner=" + owner
                + " mgr_attached=" + mgrAttached
                + " hp=" + t.lastHp
                + " cr=" + t.lastCr
                + " flux_level=" + fluxLevel
                + " loc=" + locX + "," + locY);
        t.walkRows++;
        t.ticksSinceWalkRow = 0;
        t.advancesAtLastWalkRow = adv;
        t.progressAtLastWalkRow = diveProgress;
    }

    /** Tracked hulls that left the live roster since last tick: last-good gate line. */
    private void logDroppedTracked(long simTick, Map<Object, Long> ships) {
        if (trackedByShip.isEmpty()) {
            return;
        }
        List<Object> dropped = null;
        for (Map.Entry<Object, Tracked> entry : trackedByShip.entrySet()) {
            if (ships.containsKey(entry.getKey())) {
                continue;
            }
            Tracked t = entry.getValue();
            if (!t.gateLogged) {
                logGate(simTick, t, "roster_drop", t.diveStarted);
            }
            if (dropped == null) {
                dropped = new ArrayList<>();
            }
            dropped.add(entry.getKey());
        }
        if (dropped != null) {
            for (Object ship : dropped) {
                trackedByShip.remove(ship);
            }
        }
    }

    /**
     * The non-anchor verdict cache strongly
     * retained every engine ship ever seen (logDroppedTracked prunes only trackedByShip), so
     * a long serve grew it without bound. Past a slack bound (2x live + 64) it is trimmed to
     * the live roster — amortised O(live), never per-frame work. No summary line exists on
     * this probe, so the prune line IS the export (cumulative meter pruned_total=).
     */
    private void pruneNonAnchorShips(long simTick, Map<Object, Long> ships) {
        if (nonAnchorShips.size() <= 2 * ships.size() + 64) {
            return;
        }
        int before = nonAnchorShips.size();
        nonAnchorShips.retainAll(ships.keySet());
        int pruned = before - nonAnchorShips.size();
        nonAnchorPrunedTotal += pruned;
        System.out.println("npeer-serve phase_dive_probe tick=" + simTick
                + " pruned=" + pruned + " kept=" + nonAnchorShips.size()
                + " pruned_total=" + nonAnchorPrunedTotal);
    }

    /** Cumulative non-anchor cache prunes. */
    private long nonAnchorPrunedTotal;

    /** The F4b line — field names mirror the anchor-dive probe's W1 row. */
    private void logGate(long simTick, Tracked t, String edge, boolean emergencyDive) {
        t.gateLogged = true;
        System.out.println("phase-dive probe phase_anchor_gate tick=" + simTick
                + " edge=" + edge + " wire_id=" + t.wireId
                + " can_dive_key_present=" + t.lastCanDiveKeyPresent
                + " current_cr=" + t.lastCr
                + " deploy_cost=" + t.lastDeployCost
                + " fleet_member=" + t.lastFleetMemberPresent
                + " hp=" + t.lastHp
                + " show_floaty=" + t.lastShowFloaty
                + " emergency_dive=" + emergencyDive
                + " dive_progress=" + t.lastDiveProgress);
    }

    private static boolean boolField(Object target, String name) throws Exception {
        return target.getClass().getField(name).getBoolean(target);
    }

    private static float floatField(Object target, String name) throws Exception {
        return target.getClass().getField(name).getFloat(target);
    }
}
