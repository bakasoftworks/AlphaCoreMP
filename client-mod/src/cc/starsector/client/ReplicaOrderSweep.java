package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import java.util.List;
import java.util.Set;

/**
 * The yellow-map-arrows fix. The warroom draws a 2000-unit
 * RETREAT band for every deployed side-0 member whose
 * task-manager ORDERS read RETREAT. One test flight refuted the direct-order model:
 * every banded hull read {@code direct=false locked=true} — the writer was
 * {@code instantiateForCombat}'s {@code !canBeDeployedForCombat()} check on a zero-crew factory member, i.e.
 * {@code CombatTaskManager}'s LOCK-derived RETREAT, fixed at the mint
 * ({@link PredictReconcileCombatPlugin#replicaCrew}). What stays here (a later fix removed
 * the dead direct-order-cancel arm, which fired 8× there and cleared nothing): the once-a-second
 * LOCK catch-all over the replay-driven side-0 bodies (any other writer's lock is cleared AND
 * named), the stray {@code isRetreating()} clear (the engine removes a retreating ship at the map
 * edge locally, {@code CombatFleetManager} — a desync), and the order-tuple INSTRUMENT
 * ({@link #describe} / {@link #appendOrderWalk}: the walker's {@code order_walk} line still reads
 * the engine-internal orders so a future direct-order writer is named, never cancelled blind).
 *
 * <p>Three call shapes: adopt (own COMMIT — the target hull), re-home (BEGIN — the vacated hull)
 * and the sweep. A carrier's LOCK, where it has one (a foe's, or any carrier registered while the
 * window's bay kill switch was NOT latched — an own-team carrier under the live latch takes none),
 * is the {@code registerReplayDriven} bay lock and is left alone (rider R11). Engine refs are typed inline (the TransferManagerAdapter law: {@code
 * com.fs.starfarer.combat.*} is classloader-B-legal, gate {@code check-b-deny-refs}); the engine
 * read is try-wrapped and the first throw retires it for the run (fail-soft, presentation-side).
 * Laws pinned game-free by {@code JoinPipelineLawSmoke} L2.
 */
final class ReplicaOrderSweep {

    /** Sweep cadence in render frames (~1 s at 60 fps). */
    private static final int SWEEP_EVERY_FRAMES = 60;
    /** The first N lock clears log, per instance (= per Play). */
    static final int LOCK_LOG_CAP = 40;
    /** Past the cap every Nth clear still logs — a
     *  persistent re-lock writer surfaces about once a minute instead of never. */
    static final int LOCK_LOG_EVERY = 60;
    private int lockClears;

    private static volatile boolean obfDead;
    private static volatile boolean obfDeadLogged;
    private int frame;
    /** Rows that CLEARED something (rider 3: never a logged-but-unchanged adopt / re-home row). */
    private int clearsTotal;

    /** One compact order tuple for a hull: {@code type=.. direct=.. dflag=.. locked=.. retreating=.. directRetreat=.. ai=..}. */
    static String describe(CombatEngineAPI engine, ShipAPI ship) {
        if (engine == null || ship == null) {
            return "type=? (null)";
        }
        StringBuilder sb = new StringBuilder(96);
        try {
            CombatFleetManagerAPI fm = engine.getFleetManager(ship.getOwner());
            DeployedFleetMemberAPI dfm = fm == null ? null : fm.getDeployedFleetMember(ship);
            if (dfm == null) {
                sb.append("type=undeployed");
            } else if (obfDead) {
                CombatFleetManagerAPI.AssignmentInfo a = fm.getTaskManager(dfm.isAlly()).getAssignmentFor(ship);
                sb.append("type=").append(a == null ? "null(api)" : String.valueOf(a.getType()));
            } else {
                com.fs.starfarer.combat.tasks.CombatTaskManager tm =
                        (com.fs.starfarer.combat.tasks.CombatTaskManager) fm.getTaskManager(dfm.isAlly());
                com.fs.starfarer.combat.CombatFleetManager.O0 o0 =
                        (com.fs.starfarer.combat.CombatFleetManager.O0) dfm;
                com.fs.starfarer.combat.tasks.Object orders = tm.getOrdersForFleetMember(o0);
                CombatAssignmentType type = orders == null ? null : tm.getTypeForOrders(orders);
                sb.append("type=").append(type)
                  .append(" direct=").append(tm.hasDirectOrders(o0))
                  .append(" dflag=").append(orders != null && orders.Ø00000());
            }
        } catch (Throwable t) {
            retireObf(t);
            sb.append("type=ERR:").append(t.getClass().getSimpleName());
        }
        try {
            sb.append(" locked=").append(ship.controlsLocked())
              .append(" retreating=").append(ship.isRetreating())
              .append(" directRetreat=").append(ship.isDirectRetreat())
              .append(" ai=").append(ship.getAI() == null ? "null" : ship.getAI().getClass().getSimpleName());
        } catch (Throwable t) {
            sb.append(" flags=ERR");
        }
        return sb.toString();
    }

    /**
     * Clears a stray retreating flag on {@code ship}. Returns true when anything was cleared; logs
     * the order tuple it found either way when {@code why} names a one-shot site (adopt /
     * re-home; the instrument half), silently when nothing was found on a sweep.
     */
    boolean clearRetreat(CombatEngineAPI engine, ShipAPI ship, String why, long wireId, boolean logQuiet) {
        if (engine == null || ship == null) {
            return false;
        }
        boolean cleared = false;
        String found = describe(engine, ship);
        try {
            if (ship.isRetreating()) {
                ship.setRetreating(false, false);
                cleared = true;
            }
        } catch (Throwable ignore) {
        }
        if (cleared) {
            clearsTotal++;
        }
        if (cleared || !logQuiet) {
            PilotWatchLog.info("cc-order-sweep [" + why + "] wire=" + wireId
                    + " hull=" + hullId(ship) + " found{" + found + "} cleared=" + cleared
                    + " now{" + (cleared ? describe(engine, ship) : "unchanged") + "}"
                    + " total=" + clearsTotal);
        }
        return cleared;
    }

    /**
     * Per-frame entry (render thread): every {@link #SWEEP_EVERY_FRAMES} frames, clear a foreign
     * controls LOCK (non-carriers) and a stray retreat flag on every replay-driven side-0 body
     * that is not the player ship. Cheap: two flag reads per body, no fleet-manager lookup.
     */
    void advance(CombatEngineAPI engine, Set<ShipAPI> replayDriven, ShipAPI own,
            PredictReconcileCombatPlugin plugin) {
        if (engine == null || replayDriven == null || replayDriven.isEmpty()) {
            return;
        }
        if (++frame < SWEEP_EVERY_FRAMES) {
            return;
        }
        frame = 0;
        ShipAPI player = engine.getPlayerShip();
        for (ShipAPI s : replayDriven) {
            try {
                if (s == null || s == own || s == player || s.getOwner() != 0 || !s.isAlive()
                        || s.isFighter() || s.isStationModule() || s.isStation()) {
                    continue;
                }
                long wireId = plugin == null ? -1L : plugin.wireIdForLocalShip(s);
                // A LOCKED non-carrier replica reads RETREAT at
                // CombatTaskManager (every banded hull was locked=true
                // direct=false) — the mint now crews the member so the engine never latches it;
                // this catch-all clears any other writer's lock and NAMES it (a LOCKED carrier is
                // left alone, rider R11: a foe's, or one registered with the window's bay kill switch
                // un-latched, keeps the registerReplayDriven bay lock; an own carrier under the live
                // latch was never locked). Bounded log, never per frame.
                if (s.getNumFighterBays() == 0 && s.controlsLocked()) {
                    s.setControlsLocked(false);
                    lockClears++;
                    if (lockRowLogged(lockClears)) {
                        PilotWatchLog.info("cc-order-sweep [lock] wire=" + wireId + " hull="
                                + hullId(s) + " controlsLocked=true on a non-carrier replica"
                                + " -> cleared (total=" + lockClears + ")");
                    }
                }
                if (!s.isRetreating()) {
                    continue;
                }
                clearRetreat(engine, s, "sweep", wireId, true);
            } catch (Throwable ignore) {
                // presentation-side; never break the frame
            }
        }
    }

    /** The lock row's log budget: the first {@link #LOCK_LOG_CAP} clears, then every {@link #LOCK_LOG_EVERY}th. */
    static boolean lockRowLogged(int lockClears) {
        return lockClears <= LOCK_LOG_CAP || lockClears % LOCK_LOG_EVERY == 0;
    }

    /** Lock clears this instance (= this Play); the grade counter behind the {@code [lock]} rows. */
    int lockClears() {
        return lockClears;
    }

    /** Retreat-flag clears this instance (adopt + re-home + sweep), never a logged no-op row. */
    int clearsTotal() {
        return clearsTotal;
    }

    /** Side-0 hull enumeration for the walker's {@code order_walk} line (alive, non-fighter, non-module). */
    static void appendOrderWalk(StringBuilder sb, CombatEngineAPI engine, PredictReconcileCombatPlugin plugin) {
        List<ShipAPI> ships = engine.getShips();
        boolean first = true;
        int n = 0;
        for (int i = 0; i < ships.size(); i++) {
            ShipAPI s = ships.get(i);
            try {
                if (s == null || s.getOwner() != 0 || !s.isAlive() || s.isFighter()
                        || s.isStationModule() || s.isStation() || s.isShuttlePod()) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                n++;
                sb.append("{\"wire\":").append(plugin == null ? -1L : plugin.wireIdForLocalShip(s))
                  .append(",\"hull\":\"").append(hullId(s)).append('"')
                  .append(",\"player\":").append(s == engine.getPlayerShip())
                  .append(",\"o\":\"").append(describe(engine, s).replace('"', '\'')).append("\"}");
            } catch (Throwable ignore) {
            }
        }
        if (n == 0) {
            sb.append("{\"none\":true}");
        }
    }

    private static String hullId(ShipAPI s) {
        try {
            return s.getHullSpec() != null ? s.getHullSpec().getHullId() : "?";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void retireObf(Throwable t) {
        obfDead = true;
        if (!obfDeadLogged) {
            obfDeadLogged = true;
            PilotWatchLog.error("cc-order-sweep: engine task-manager seam threw — engine path retired for"
                    + " this run (API-only describe from here)", t);
        }
    }
}
