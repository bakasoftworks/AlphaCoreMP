package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * The PERIODIC remote-entity census, by class.
 *
 * <p>WHY: a recorded match reproduced the D-STUTTERGROW shape
 * (client-render-thread-only per-frame cost growing
 * monotonically, worst frame 142→190 ms across the F9 marks, GC and server exonerated) AFTER the
 * fighter reap-on-kill landed (`reaped=518`, working). The reap is fed ONLY by
 * {@code FoeStateLaneCoordinator#killFighterRemote}; a NON-fighter death ({@code #killRemote})
 * latches {@code killed=true} + {@code engine.removeEntity} but the registry row stays (never
 * {@code remoteEntities.remove} for a BODIED row, {@code re.ship} never nulled; the entity cap's
 * {@code RosterAppendLane.retireDeadBeforeSight} removes only BODILESS
 * dead-before-sight APPENDED rows) — so dead station modules / ship
 * hulks / drone replicas are the prime suspect, and the one-shot stream-end census could not show
 * a TREND. This lane emits ONE line per {@value #PERIOD_NANOS}-ns window (~60 s) from ONE walk of
 * the registry + the engine's ship list (never per frame), so the next flight's sidelog shows
 * whether a killed-unreaped class climbs with frame time. Diagnostic only: every throw is
 * swallowed, nothing here mutates state. Package-private, render thread only (the maps it reads
 * are render-thread owned). NEW-LANE LAW: the host plugin carries only the field + one call.
 */
final class RemoteEntityCensus {
    /** Emit cadence (wall clock, render thread). */
    static final long PERIOD_NANOS = 60_000_000_000L;

    private long lastEmitNanos;
    private int window;

    /** Per render frame: cheap clock compare; one census line per window. */
    void tick(PredictReconcileCombatPlugin plugin, long nowNanos) {
        if (lastEmitNanos == 0L) {
            lastEmitNanos = nowNanos;
            return;
        }
        if (nowNanos - lastEmitNanos < PERIOD_NANOS) {
            return;
        }
        lastEmitNanos = nowNanos;
        window++;
        try {
            // render thread (the per-frame tick) ⇒ this line DRAINS the fps window.
            PilotWatchLog.info("predict remote census win=" + window + " " + breakdown(plugin, true));
        } catch (Throwable ignore) {
            // diagnostic only; never touches the frame
        }
    }

    /**
     * The by-class breakdown (one registry walk + one engine ship walk). Fields:
     * {@code total} registry rows; {@code unbound} rows without a local ship; {@code live}
     * bound + alive ({@code liveModule} of them station modules); {@code killedUnreaped} rows
     * with {@code killed=true} still in the map, split {@code kf} fighter (a reap is pending or
     * lost) / {@code km} station module / {@code kd} drone-gate replica / {@code ko} other hull;
     * {@code deadNotKilled} engine-dead bodies the registry never killed; {@code reaped} the
     * cumulative reap count + {@code reapQueue} pending; then every other per-frame-walked or
     * id-keyed client collection that could grow ({@code shieldFacing} =
     * {@code lastShieldFacingU8}, {@code variantById}, {@code foeProj} = presentedFoeProj,
     * {@code seatLabels} = the seat-tag label cache) and the ENGINE's own lists
     * ({@code engineShips}/{@code engineHulks}/{@code engineFighters}/{@code engineProj}) — the
     * engine keeps non-fighter hulks on the field, and a growing hulk pile is render + collision
     * cost the registry never sees.
     */
    static String breakdown(PredictReconcileCombatPlugin plugin, boolean renderThread) {
        int total = plugin.remoteEntities.size();
        int unbound = 0;
        int live = 0;
        int liveModule = 0;
        int killed = 0;
        int killedFighter = 0;
        int killedModule = 0;
        int killedDrone = 0;
        int killedOther = 0;
        int deadNotKilled = 0;
        for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
            ShipAPI ship = re.ship;
            if (ship == null) {
                unbound++;
                continue;
            }
            if (re.killed) {
                killed++;
                if (ship.isFighter()) {
                    killedFighter++;
                } else if (re.stationModule) {
                    killedModule++;
                } else if (re.droneGate) {
                    killedDrone++;
                } else {
                    killedOther++;
                }
            } else if (ship.isAlive()) {
                live++;
                if (re.stationModule) {
                    liveModule++;
                }
            } else {
                deadNotKilled++;
            }
        }
        int engineShips = -1;
        int engineHulks = 0;
        int engineFighters = 0;
        int engineProj = -1;
        CombatEngineAPI engine = plugin.engine;
        if (engine != null) {
            java.util.List<ShipAPI> ships = engine.getShips();
            if (ships != null) {
                engineShips = ships.size();
                for (int i = 0; i < ships.size(); i++) {
                    ShipAPI s = ships.get(i);
                    if (s == null) {
                        continue;
                    }
                    if (!s.isAlive()) {
                        engineHulks++;
                    }
                    if (s.isFighter()) {
                        engineFighters++;
                    }
                }
            }
            java.util.List<?> proj = engine.getProjectiles();
            engineProj = proj == null ? -1 : proj.size();
        }
        // The fleet-manager deployed sets — THE set checkCornerCheese scans.
        String deployed = engine == null ? "" : DeployedSetEvictLane.census(engine)
                + " evicted=" + plugin.foeState.deployedEvict.evicted
                + " gone=" + plugin.foeState.deployedEvict.alreadyGone
                + BodyCensusRows.residualSummary(engine); // Name the residuals
        // The per-frame hold walk's set + its self-prune count, and
        // the window's fps mean/min — the FPS-vs-population read on ONE line.
        // Only the render-thread emit DRAINS the window; backstops peek.
        String walk = " replayDriven=" + plugin.replayDrivenSize()
                + " pruned=" + plugin.replicaBody.replayDrivenPruned
                + " fpsWin=" + (renderThread ? plugin.fpsWindowTake() : plugin.fpsWindowPeek())
                + " weaponLists=" + plugin.weaponLists.census();
        return "total=" + total + " unbound=" + unbound + " live=" + live + deployed + walk
                + " liveModule=" + liveModule + " killedUnreaped=" + killed
                + " kf=" + killedFighter + " km=" + killedModule + " kd=" + killedDrone
                + " ko=" + killedOther + " deadNotKilled=" + deadNotKilled
                + " reaped=" + plugin.remoteReaped + " reapQueue=" + plugin.remoteReapQueue.size()
                + " shieldFacing=" + plugin.lastShieldFacingU8.size()
                + " variantById=" + plugin.variantById.size()
                + " foeProj=" + plugin.presentedFoeProj.size()
                + " seatLabels=" + plugin.seatHud.labelCacheSize()
                + " engineShips=" + engineShips + " engineHulks=" + engineHulks
                + " engineFighters=" + engineFighters + " engineProj=" + engineProj;
    }
}
