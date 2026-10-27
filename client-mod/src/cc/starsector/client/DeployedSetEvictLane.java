package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import java.util.List;

/**
 * Evict a server-killed local
 * body from its side's fleet-manager DEPLOYED set, the way vanilla's own death listener does.
 *
 * <p>WHY: the late-match client stutter (0.5–0.9 s period, growing to ~200 ms) is vanilla
 * {@code CombatEngine.checkCornerCheese} × {@code CombatFleetManager.getDeployedFleetMember} — a
 * LINEAR scan of the deployed set per surround check, per eligible member, per interval. Every
 * remote body this client mints is a deployed member ({@code spawnFleetMember} /
 * {@code spawnShipOrWing}), and vanilla evicts a member only via its death LISTENER
 * ({@code CombatFleetManager.shipDestroyed/shipDisabled} → {@code removeDeployed(ship, false)},
 * in the engine), which is {@code !isFighter}-gated and skipped when the body is hulked
 * before the damage. Our fighters are SHIP-type members (per-fighter hull ids — the growth lane
 * announces those, never wing ids), so they are neither {@code isFighterWing()}-filtered by the
 * corner-cheese loops nor listener-evicted: every dead fighter stays a scanned member forever and
 * the cost grows super-linearly with the kill count (JFR read: the member scan's share 22→46 % across
 * the train, both seats; the F12 wave-pause freezes the growth). Server side is vanilla end to
 * end and never accumulates.
 *
 * <p>WHAT: {@link #evict} = the public API verb {@code CombatFleetManagerAPI.removeDeployed(ship,
 * false)} — IS the listener's own call (strength decrement, control-GROUP membership REMOVE
 * (fighters are never group members, so a no-op),
 * {@code saveStatus()}; the body stays in the world, so a drifting hulk / recall animation is
 * untouched). Idempotent: a member the listener already evicted is a no-op scan, counted as
 * {@link #alreadyGone} so the census shows whether vanilla's listener fires for our non-fighter
 * kills. Owner candidates = {@code getOriginalOwner()} (the listener's key) then {@code getOwner()}
 * (a neutralized body). NOT a fix: neutering the check (every other per-member scan keeps
 * growing). Presentation-only, no wire change (prefer-client-side law), B-legal (API only).
 * Fail-soft: a Throwable dead-latches the lane loudly; the death presentation never depends on
 * it. Render thread only.
 *
 * <p>Census ({@link #census}): per side {@code deployedN=size/wW/fF/deadD} — {@code w} wing
 * members, {@code f} SHIP-type fighter-hull members, {@code dead} members whose ship is dead or
 * out of play (= the residual no seam evicted; the alive-body replacement seams — roster
 * re-point, module mount, transfer re-home, own respawn — are NOT wired yet by design: rare, and
 * this count names them if they matter).
 */
final class DeployedSetEvictLane {
    /** Members this lane found deployed and evicted. */
    int evicted;
    /** Calls where no manager held the body: the listener already evicted it OR it was never a
     *  SHIP member (a wing body) — an ambiguous count; the affirmative proof is {@link #evicted}
     *  tracking fighter kills while census {@code dead} stays flat. Vanilla's
     *  destroyed/disabled lists are filled by the listener before its own removeDeployed — this
     *  lane's direct call fills neither, which is vanilla-shaped for fighters (never listed). */
    int alreadyGone;
    private boolean deadLatched;
    /** The current match-reset sweep's per-side evictions
     *  (per-body + backstop) and its post-backstop residual ({@code -1} = the backstop never ran);
     *  read-and-cleared by {@link #takeResetSweepTerm}. */
    private final int[] resetEvicted = new int[2];
    private int resetResidualAfter = -1;

    /** Evict {@code ship} from its side's deployed set; {@code why} labels the seam in the log.
     *  Returns the side it left, or -1 (gone / dead lane). */
    int evict(CombatEngineAPI engine, ShipAPI ship, String why) {
        if (deadLatched || engine == null || ship == null) {
            return -1;
        }
        try {
            int original = ship.getOriginalOwner();
            if (evictFrom(engine, original, ship)) {
                evicted++;
                return original;
            }
            int owner = ship.getOwner();
            if (owner != original && evictFrom(engine, owner, ship)) {
                evicted++;
                return owner;
            }
            alreadyGone++;
        } catch (Throwable t) {
            deadLatched = true;
            PilotWatchLog.error("predict deployed-evict: FAILED at " + why + " (evicted=" + evicted
                    + " gone=" + alreadyGone + ") -> lane dead-latched", t);
        }
        return -1;
    }

    /**
     * The match-reset sweep's per-body verb — evict BEFORE
     * the caller's {@code engine.removeEntity} (the {@code removeStaleBody} order; a bare removeEntity
     * never reaches {@code CombatFleetManager.removeDeployed}, so the member stays deployed and the
     * tactical map keeps its orange order line). Tallied per side.
     */
    void evictForReset(CombatEngineAPI engine, ShipAPI ship) {
        int side = evict(engine, ship, "match-reset");
        if (side == 0 || side == 1) {
            resetEvicted[side]++;
        }
    }

    /**
     * The match-reset BACKSTOP, run once right after the
     * per-body sweep — evicts every deployed non-wing member of sides 0/1 whose ship is out of play
     * ({@link #outOfPlay}, the census predicate) other than {@code keep} (the fresh own ship): the
     * members whose body the sweep never saw (an own hull dropped before the wrap, a hulk already
     * removed). Then records the residual by the same predicate. One walk per side per round wrap.
     */
    void resetBackstop(CombatEngineAPI engine, ShipAPI keep) {
        if (deadLatched || engine == null) {
            return;
        }
        try {
            int residual = 0;
            for (int pass = 0; pass < 2; pass++) { // pass 0 evicts, pass 1 counts what is left
                for (int side = 0; side < 2; side++) {
                    CombatFleetManagerAPI mgr = engine.getFleetManager(side);
                    List<DeployedFleetMemberAPI> members = mgr == null ? null : mgr.getDeployedCopyDFM();
                    for (int i = 0; members != null && i < members.size(); i++) {
                        DeployedFleetMemberAPI m = members.get(i);
                        ShipAPI s = m == null || m.isFighterWing() ? null : m.getShip();
                        // The kept ship's child modules
                        // are kept too (a module is a Ship whose getParentStation() is its parent).
                        if (s == null || s == keep || (keep != null && s.getParentStation() == keep)
                                || !outOfPlay(engine, s)) {
                            continue;
                        }
                        if (pass == 1) {
                            residual++;
                        } else if (evictFrom(engine, side, s)) {
                            evicted++;
                            resetEvicted[side]++;
                        }
                    }
                }
            }
            resetResidualAfter = residual;
        } catch (Throwable t) {
            deadLatched = true;
            PilotWatchLog.error("predict deployed-evict: FAILED at match-reset backstop (evicted="
                    + evicted + ") -> lane dead-latched", t);
        }
    }

    /** The {@code match_reset applied} row term for the
     *  sweep just run; clears the tallies for the next wrap. */
    String takeResetSweepTerm() {
        String term = " deployed_evicted=" + (resetEvicted[0] + resetEvicted[1]) + " (side0="
                + resetEvicted[0] + " side1=" + resetEvicted[1] + ") residual_after="
                + (resetResidualAfter < 0 ? "n/a" : String.valueOf(resetResidualAfter));
        resetEvicted[0] = 0;
        resetEvicted[1] = 0;
        resetResidualAfter = -1;
        return term;
    }

    /** The census {@code dead} predicate (shared with {@link #resetBackstop}): dead or out of play. */
    private static boolean outOfPlay(CombatEngineAPI engine, ShipAPI s) {
        return !s.isAlive() || !engine.isEntityInPlay(s);
    }

    /** Package: {@link RemoteTeamFlip} evicts a flipped replica's parts from their OLD side
     *  through this same verb. */
    static boolean evictFrom(CombatEngineAPI engine, int side, ShipAPI ship) {
        if (side != 0 && side != 1) {
            return false;
        }
        CombatFleetManagerAPI mgr = engine.getFleetManager(side);
        if (mgr == null || mgr.getDeployedFleetMember(ship) == null) {
            return false;
        }
        mgr.removeDeployed(ship, false);
        // Review: the guard matches by ship OR wing membership, removeDeployed(ShipAPI) by
        // ship only — count an eviction only when the member is really gone (wing bodies are not
        // this lane's; census `w` sizes them).
        return mgr.getDeployedFleetMember(ship) == null;
    }

    /** One walk per side per census window (~60 s); never per frame. */
    static String census(CombatEngineAPI engine) {
        StringBuilder sb = new StringBuilder(64);
        for (int side = 0; side < 2; side++) {
            sb.append(" deployed").append(side).append('=');
            CombatFleetManagerAPI mgr = engine.getFleetManager(side);
            List<DeployedFleetMemberAPI> members = mgr == null ? null : mgr.getDeployedCopyDFM();
            if (members == null) {
                sb.append('-');
                continue;
            }
            int wings = 0;
            int fighters = 0;
            int dead = 0;
            for (int i = 0; i < members.size(); i++) {
                DeployedFleetMemberAPI m = members.get(i);
                if (m == null) {
                    continue;
                }
                if (m.isFighterWing()) {
                    wings++;
                    continue;
                }
                ShipAPI s = m.getShip();
                if (s == null) {
                    continue;
                }
                if (s.isFighter()) {
                    fighters++;
                }
                if (outOfPlay(engine, s)) { // Shared predicate
                    dead++;
                }
            }
            sb.append(members.size()).append("/w").append(wings).append("/f").append(fighters)
                    .append("/dead").append(dead);
        }
        return sb.toString();
    }
}
