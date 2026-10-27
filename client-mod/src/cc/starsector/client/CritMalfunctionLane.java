package cc.starsector.client;

import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.lwjgl.util.vector.Vector2f;

import cc.starsector.client.net.ClientGameplayEdgeEvent;

/**
 * The GPEE v14 {@code CRIT_MALFUNCTION} (kind 10) presenter.
 *
 *
 * <p><b>Why.</b> A critical malfunction is decided by the SERVER engine (the
 * {@code CombatReadinessPlugin} seam: the crit roll nested inside the ordinary malfunction roll);
 * the server observes that decision and
 * names it on the wire (victim, module, permanent flag, hull branch, hull amount). This lane
 * replays vanilla's OWN script ({@code Ship.applyCriticalMalfunction})
 * on the local body through the public API: the
 * module {@code disable(perm)} (+ {@code setAmmo(0)} / {@code removeWeaponFromGroups} for a
 * permanent weapon), then the hull branch through ONE door for both DIRECT and DAMAGE: vanilla's
 * own floaty + {@code setHitpoints} write. Never a second local roll, never
 * painted FX.
 *
 * <p><b>Why DAMAGE is not {@code applyDamage} (the floaty
 * door of {@link ReplicaDevourPresenter}):</b> the wire amount is the SERVER engine's
 * POST-mitigation number (its {@code applyDamage(ENERGY)} already ran the armor grid on the
 * authoritative body); re-feeding it through the local {@code applyDamage} mitigates it TWICE, so
 * the armor grid and the floaty diverge from the serve (
 * a post-armor wire amount is never re-applied through {@code applyDamage}). The
 * server ships the HULL part of that self-report, so the direct write
 * lands the same hull the wire mirror carries; the armor cells are the armor lane's.
 * <b>Residue (flight FREE read):</b> the write may subtract a crit the mirror
 * already applied for ONE frame (a hull-bar dip) — older behaviour, kept as is; graded
 * on the next flight's hull-bar filmstrip.
 *
 * <p><b>Vanilla's lethal guard guards the WRITE only</b>:
 * the server's branch was DIRECT/DAMAGE, so ITS hull already
 * passed that guard and the floaty always prints; a body whose LOCAL hull is at or under the amount
 * (the mirror may already carry the post-crit hull, or a foe hit landed locally first) is never
 * written -- the mirror / kill lane own that hull. The write is clamped {@code >= 1} for the same reason.
 *
 * <p><b>Overlaps.</b> The hull-reconcile lane folds any residual hull delta;
 * the mount-tier / engine-mask state lanes still
 * carry the disabled state (idempotent overlap -- a second {@code disable} on a disabled module is
 * a no-op). LETHAL is never replayed: the kill lane owns the death, so the {@code 10x max} write is
 * skipped (the module disable still presents).
 *
 * <p>Knob {@code cc.predict.critMalfunctionFx} = {@code on|off}, DEFAULT ON (the FX-lane
 * kill-switch idiom of {@link ArcHitFxLane}); invalid values THROW at class load. Render-thread
 * only; counters cumulative; fail-soft everywhere.
 */
final class CritMalfunctionLane {

    /** The crit-malfunction replay kill-switch, {@code cc.predict.critMalfunctionFx} = {@code on|off}. */
    static final boolean CRIT_MALFUNCTION_FX = parseOnOff("cc.predict.critMalfunctionFx",
            System.getProperty("cc.predict.critMalfunctionFx", "on"));

    private static boolean parseOnOff(String knob, String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(value)) {
            return true;
        }
        if ("off".equals(value)) {
            return false;
        }
        throw new IllegalStateException(knob + " must be on|off; got '" + raw + "'");
    }

    /** Vanilla's direct-branch floaty colour. */
    static final Color CRIT_FLOATY = new Color(255, 50, 0, 220);

    /** Log cap (counters continue past it). */
    private static final int LOG_MAX = 20;

    /** The DIRECT-branch
     *  rows' OWN log cap. The damage-delta auditor sums ONLY the {@code branch=direct}
     *  rows as client hull (they emit no dmg-apply row); under the one shared {@link #LOG_MAX} the
     *  damage/lethal rows could spend the budget first and starve the auditor. One
     *  {@code LOG_CAP reached} line marks the fill (its text never matches the auditor's row regex). */
    static final int DIRECT_LOG_MAX = 20;

    /** Result codes of {@link #presentOn}. */
    static final int RESULT_PRESENTED = 1;
    static final int RESULT_MODULE_MISS = -1;
    static final int RESULT_LETHAL = -2;
    /** Module presented, the hull write SKIPPED by vanilla's lethal guard
     *  (local hull {@code <=} amount; the kill lane owns any death).
     *  The floaty still printed. */
    static final int RESULT_HULL_GUARDED = 2;

    /** Distinct failure messages already logged (one line per distinct message). */
    private static final Set<String> failuresLogged = new HashSet<String>();

    private final PredictReconcileCombatPlugin plugin;
    private int presented;          // predict_event_rebase_crit_presented
    private int hullGuarded;        // Presented, hull write skipped (local hull <= amount)
    private int skippedUnresolved;  // victim / engine unresolvable or dead
    private int skippedModule;      // module ordinal outside the local body's lists (or a throw)
    private int skippedLethal;      // LETHAL: module disabled, hull left to the kill lane
    private int skippedOff;         // knob off
    private int failures;           // The intake threw (counted, logged once per message)
    private int logLines;           // non-direct rows (damage / lethal), capped at LOG_MAX
    private int directLogLines;     // Direct rows, capped at DIRECT_LOG_MAX

    CritMalfunctionLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** PRCP kind-10 intake (route-FIRST, the trap law). {@code own} = PRCP's
     *  {@code entityId == session.shipId()} verdict -- for this kind the VICTIM. */
    void present(ClientGameplayEdgeEvent event, boolean own) {
        if (!CRIT_MALFUNCTION_FX) {
            skippedOff++;
            return;
        }
        try {
            ShipAPI ship = resolve(event.entityId());
            if (ship == null || !ship.isAlive()) {
                skippedUnresolved++;
                return;
            }
            CombatEngineAPI engine = plugin.engine;
            if (engine == null) {
                skippedUnresolved++;
                return;
            }
            boolean isEngine = event.critModuleIsEngine();
            int ordinal = event.critModuleOrdinal();
            boolean permanent = event.critPermanent();
            int branch = event.critBranch();
            float amount = event.critAmount();
            int r = presentOn(engine, ship, isEngine, ordinal, permanent, branch, amount);
            if (r == RESULT_PRESENTED) {
                presented++;
            } else if (r == RESULT_HULL_GUARDED) {
                presented++;
                hullGuarded++;
            } else if (r == RESULT_LETHAL) {
                skippedLethal++;
            } else {
                skippedModule++;
            }
            // The direct rows (the auditor's hull input) ride their own budget.
            boolean direct = branch == ClientGameplayEdgeEvent.CRIT_BRANCH_DIRECT;
            boolean logRow;
            if (direct) {
                logRow = directLogLines < DIRECT_LOG_MAX;
                if (logRow) {
                    directLogLines++;
                }
            } else {
                logRow = logLines < LOG_MAX;
                if (logRow) {
                    logLines++;
                }
            }
            if (logRow) {
                PilotWatchLog.info("predict crit-malfunction: victim=" + event.entityId()
                        + " own=" + own
                        + " module=" + (isEngine ? "engine:" : "weapon:") + ordinal
                        + " perm=" + permanent
                        + " branch=" + branchName(branch)
                        + " amount=" + amount
                        + " tick=" + event.simTick()
                        + " result=" + r);
                if (direct && directLogLines == DIRECT_LOG_MAX) {
                    // ONE line at the fill (no ':' after the prefix -- never an auditor row).
                    PilotWatchLog.info("predict crit-malfunction direct-rows LOG_CAP reached cap="
                            + DIRECT_LOG_MAX + " (counters continue; later direct rows unlogged)");
                }
            }
        } catch (Throwable t) {
            // presentation lane; never break the watch -- but every catch counts and logs once
            // (the silent catch-all hid the lane's own faults)
            failures++;
            logOnce("crit_malfunction_intake_failed " + t);
        }
    }

    /** One log line per distinct message (the {@link #presentOn} failure idiom), capped at 64. */
    private static void logOnce(String msg) {
        synchronized (failuresLogged) {
            if (failuresLogged.size() < 64 && failuresLogged.add(msg)) {
                PilotWatchLog.info(msg);
            }
        }
    }

    private static String branchName(int branch) {
        if (branch == ClientGameplayEdgeEvent.CRIT_BRANCH_DIRECT) {
            return "direct";
        }
        if (branch == ClientGameplayEdgeEvent.CRIT_BRANCH_DAMAGE) {
            return "damage";
        }
        return "lethal";
    }

    /**
     * Vanilla's {@code Ship.applyCriticalMalfunction} replayed on
     * {@code ship} through the public API. PURE over the API (the smoke drives it through
     * proxies). Returns {@link #RESULT_PRESENTED}, {@link #RESULT_HULL_GUARDED} (module disabled,
     * the hull write refused by the lethal guard), {@link #RESULT_MODULE_MISS} (ordinal outside
     * the local lists, a null controller, or a throw) or {@link #RESULT_LETHAL} (module disabled,
     * hull left to the kill lane).
     */
    static int presentOn(CombatEngineAPI engine, ShipAPI ship, boolean isEngine, int ordinal,
            boolean permanent, int branch, float amount) {
        try {
            if (ordinal < 0) {
                return RESULT_MODULE_MISS;
            }
            Vector2f loc;
            if (!isEngine) {
                List<WeaponAPI> ws = ship.getAllWeapons();
                if (ws == null || ordinal >= ws.size()) {
                    return RESULT_MODULE_MISS;
                }
                WeaponAPI w = ws.get(ordinal);
                if (w == null) {
                    return RESULT_MODULE_MISS;
                }
                w.disable(permanent);
                if (permanent) {
                    if (w.usesAmmo()) {
                        w.setAmmo(0);
                    }
                    ship.removeWeaponFromGroups(w);
                }
                loc = w.getLocation();
            } else {
                ShipEngineControllerAPI ctrl = ship.getEngineController();
                if (ctrl == null) {
                    return RESULT_MODULE_MISS;
                }
                List<ShipEngineControllerAPI.ShipEngineAPI> es = ctrl.getShipEngines();
                if (es == null || ordinal >= es.size()) {
                    return RESULT_MODULE_MISS;
                }
                ShipEngineControllerAPI.ShipEngineAPI e = es.get(ordinal);
                if (e == null) {
                    return RESULT_MODULE_MISS;
                }
                e.disable(permanent);
                loc = e.getLocation();
            }
            if (branch == ClientGameplayEdgeEvent.CRIT_BRANCH_LETHAL) {
                return RESULT_LETHAL; // the kill lane owns the death -- never replay the 10x max write
            }
            if (amount <= 0f) {
                return RESULT_PRESENTED; // module only; the server saw no hull change
            }
            if (loc == null) {
                loc = ship.getLocation();
            }
            if (branch != ClientGameplayEdgeEvent.CRIT_BRANCH_DIRECT
                    && branch != ClientGameplayEdgeEvent.CRIT_BRANCH_DAMAGE) {
                return RESULT_PRESENTED; // an unknown branch never reaches the hull (the record is exact-set; belt)
            }
            // ONE door for DIRECT and DAMAGE = vanilla's own branch body
            // verbatim (vanilla's own floaty + direct write). DAMAGE never goes through the local
            // applyDamage: the amount is the server engine's post-mitigation hull number, and a
            // second armor pass would double-mitigate it (the floaty door).
            //
            // the floaty prints on EVERY server non-lethal amount -- the server's DIRECT/DAMAGE
            // branch already passed vanilla's lethal guard on the AUTHORITATIVE hull, while
            // the local hull may already be the mirrored post-crit value (PRCP.mirrorHull), so a
            // local re-check hid the orange number exactly when crits are common (corona 100).
            engine.addFloatingDamageText(loc, amount, CRIT_FLOATY, ship, ship);
            float hull = ship.getHitpoints();
            if (hull <= amount) {
                // The guard now protects the WRITE only: never drive the local hull to/below 0 on
                // a crit the server survived -- the mirror or the kill lane owns that hull. Counted
                // (a nonzero count is NOT by itself a finding: the mirror may simply have landed first).
                return RESULT_HULL_GUARDED;
            }
            ship.setHitpoints(Math.max(1f, hull - amount)); // >= 1: the guard above makes this a float-tie belt
            return RESULT_PRESENTED;
        } catch (Throwable t) {
            logOnce("crit_malfunction_present_failed " + t);
            return RESULT_MODULE_MISS;
        }
    }

    /** Wire id -> local ShipAPI: the own ship via the session id, else the PRCP replica
     *  resolution idiom ({@code remoteEntities} when {@code registryShape}, else the 1v1 foe).
     *  Delegates to the ONE shared
     *  {@link WireShipResolver#resolve} (was a verbatim copy of {@code ArcHitFxLane.resolve}). */
    private ShipAPI resolve(long wireId) {
        return WireShipResolver.resolve(plugin, wireId);
    }

    int presented() {
        return presented;
    }

    /** Of {@link #presented()}: hull write refused by the lethal guard. */
    int hullGuarded() {
        return hullGuarded;
    }

    /** Intake throws (counted + logged once per distinct message). */
    int failures() {
        return failures;
    }

    int skippedUnresolved() {
        return skippedUnresolved;
    }

    int skippedModule() {
        return skippedModule;
    }

    int skippedLethal() {
        return skippedLethal;
    }

    int skippedOff() {
        return skippedOff;
    }
}
