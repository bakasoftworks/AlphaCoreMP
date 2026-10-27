package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;

/**
 * D-HULKDEL fix — the REMOTE fly-in respawn's outgoing-body
 * law, born own-file (PRCP sits at its growth ceiling).
 *
 * <p><b>Defect:</b> the server reuses a seat's wire id across death → hulk → respawn and sends NO
 * hulk-remove message at the remount (only {@code fly_in} + reseed); the server's corpse keeps
 * living as a fracture family keyed {@code root=<id>:deathTick} (server {@code HulkFractureDetector}
 * pins the family at the parent's wire id and keeps posing it after the seat respawns).
 * {@code PredictReconcileCombatPlugin#respawnRemote} disposed EVERY outgoing body
 * ({@code engine.removeEntity} — the hardening for the rebirth-rejoin residue),
 * so on the OTHER client the wreck vanished exactly at the
 * pilot's respawn
 * (8 resets, both directions). The owning window never
 * disposes — its corpse persists, the same vanilla wreck the server world holds — so THAT is the
 * faithful target. The rebirth residue the disposal was minted for is now swept by the L-R1 life
 * reset itself ({@code finishMatchReset} life branch), not by this per-respawn path.
 *
 * <p><b>Law:</b> the outgoing body is KEPT iff it is a server-mirrored corpse — dead, still in play,
 * and its fracture family has LATCHED a wire hulk record ({@code HKPS} pose keyframe or {@code HKFR}
 * edge, {@link FractureFamilyCoordinator#wireLatchedDeathTick}): the server has a wreck for it, so the
 * client keeps one. The family lane keeps driving it by {@code root:deathTick} — it never resolves
 * through the {@code RemoteEntity} slot — and spawn-on-sight mints a fresh hull for the reused id
 * without scanning the ship list (no adopt-a-hulk hazard). The corpse's stale
 * {@code RemoteDamageCap} listener is identity-gated ({@code target != entity.ship} passes unchanged;
 * its {@code remote-stale:} witness label is structural) and the body stays a
 * wire-piece for the overkill veto. Everything else — an ALIVE body (a repoint dropping a live
 * replica through this path), an already-removed body, or a hulk whose family never saw the wire
 * (an absence-inferred kill the server never issued = the L-R1 residue class) — is DISPOSED as
 * before: the id now serves a different server hull and the client holds nothing the server world
 * holds. Both branches log one row per respawn; the DISPOSED row with {@code wireFamily=none} on a
 * dead body doubles as a fake-kill witness. The match-reset / life-reset sweeps still remove every
 * body, so a kept corpse never outlives its round. <b>Station modules:</b> a LIVE
 * station module ({@code plugin.mountedBodies} member or {@code isStationModule()}) is KEPT
 * untouched — the engine never re-creates a station child and the server keeps the module alive
 * under the seat id or its fresh dismount id; {@link ModuleMountLane#applyDismount} owns the
 * hand-back (the disposal here bound the fresh module row to a removed body).
 *
 * <p><b>Deployed-list eviction:</b> {@code removeEntity} only queues the body's removal from play;
 * it never reaches the side's fleet manager, whose DEPLOYED list kept the member for the rest of
 * the round (vanilla evicts a member only through its death listener's {@code removeDeployed}), so
 * the body census listed every disposed body as an out-of-play residual. A disposed body therefore
 * also leaves that list through the same public verb ({@link #evictDeployedMember}), and only after
 * a successful {@code removeEntity}: a body a failed removal left in play stays a deployed member.
 * The disposal flips the body's {@code isAlive()} false at once (the engine's alive test reads its
 * pending-removal queue), so no later alive-gated removal path ever evicted it. The KEPT branches
 * (live module, server-mirrored wreck) never evict.
 *
 * <p>No wire change; client reconcile lane only. Fail-soft: an unreadable body takes the disposal
 * posture, and a {@code removeEntity} throw logs and leaves the body (residue, never a render-thread
 * crash — the prior posture verbatim).
 */
final class RemoteRespawnCorpseLane {

    private final PredictReconcileCombatPlugin plugin;
    /** Bodies kept as server-mirrored wrecks at a remote respawn. */
    int corpsesKept;
    /** Bodies disposed as client-only residue at a remote respawn. */
    int bodiesDisposed;
    /** LIVE station-module bodies kept for the MMNT hand-back. */
    int modulesKept;
    /** Disposed bodies whose member left its side's
     *  fleet-manager DEPLOYED list. */
    int bodiesEvicted;
    /** Disposed bodies no side listed as a ship member (nothing to evict: a death listener or an
     *  earlier removal already took it). */
    int evictAbsent;
    /** Eviction calls that threw: the body stays disposed, its member stays listed, one error row. */
    int evictFailed;

    RemoteRespawnCorpseLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Release the outgoing body of a slot about to be reset UNBOUND (render thread). Never throws.
     *
     * @return true when the body was kept as wreckage, false when disposed (or there was none)
     */
    boolean releaseOutgoingBody(PredictReconcileCombatPlugin.RemoteEntity re) {
        ShipAPI body = re.ship;
        if (body == null || plugin.engine == null) {
            return false;
        }
        boolean alive;
        boolean inPlay;
        boolean liveModule;
        long wireDeathTick;
        try {
            alive = body.isAlive();
            inPlay = plugin.engine.isEntityInPlay(body);
            liveModule = alive && (plugin.mountedBodies.contains(body) || body.isStationModule());
            wireDeathTick = plugin.fractures.wireLatchedDeathTick(re.entityId, body);
        } catch (RuntimeException readFailed) {
            alive = true; // unreadable body ⇒ the disposal posture (today's behavior)
            inPlay = false;
            liveModule = false;
            wireDeathTick = -1L;
        }
        if (liveModule) {
            // A LIVE station module is never client-only residue.
            // The engine never re-creates a station child, and the server keeps the module alive
            // under either the seat id (still mounted) or its fresh above-band id (dismount) —
            // the MMNT dismount owns the hand-back ({@code readoptAfterDismount}). Disposing it
            // here bound the observer's fresh module row to a removed body (the log read
            // "no live local body for entity 59" → the slot never re-mounted).
            // The treatment ledger is NOT touched: the dismount re-ties the cap to the fresh row.
            modulesKept++;
            PilotWatchLog.info("predict respawn-corpse: entity " + re.entityId + " outgoing body"
                    + " is a LIVE station module -> KEPT (MMNT owns the hand-back, mounted="
                    + plugin.mountedBodies.contains(body) + ", modulesKept=" + modulesKept
                    + ")");
            return true;
        }
        plugin.forgetRemoteBodyTreatment(body); // the slot's treatment ledger ends with this life either way
        if (!alive && inPlay && wireDeathTick >= 0L) {
            corpsesKept++;
            PilotWatchLog.info("predict respawn-corpse: entity " + re.entityId + " outgoing body KEPT"
                    + " as server-mirrored wreck (family root=" + re.entityId + " wireDeathTick="
                    + wireDeathTick + ", kept=" + corpsesKept + ") - D-HULKDEL fix");
            return true;
        }
        try {
            plugin.engine.removeEntity(body);
            bodiesDisposed++;
            int evicted = evictDeployedMember(re.entityId, body); // never throws; only after a removal
            PilotWatchLog.info("predict respawn-corpse: entity " + re.entityId + " outgoing body"
                    + " DISPOSED (alive=" + alive + " inPlay=" + inPlay + " wireFamily="
                    + (wireDeathTick >= 0L ? "latched" : "none") + ", disposed=" + bodiesDisposed
                    + " evicted=" + evicted + ") - client-only residue, not a server wreck");
        } catch (RuntimeException removeFailed) {
            PilotWatchLog.error("predict respawn: outgoing-body removeEntity FAILED for entity "
                    + re.entityId + " -> body left behind (residue, not a crash)", removeFailed);
        }
        return false;
    }

    /**
     * Take a just-disposed body's member off its side's
     * fleet-manager DEPLOYED list through the engine's own verb ({@link DeployedSetEvictLane#evictFrom}
     * = {@code removeDeployed(ship, false)}, looked up by ship), the current owner's side first, then
     * the original owner's (a team-flipped body). Without it the member outlives the body for the
     * round: scanned by every per-member engine walk, drawn by any deployed-list consumer, and
     * counted OUT by the body census. The body already left play on this window, and the wire id it
     * served is re-fielded by its own fresh mint (its own member), so nothing the server holds is
     * hidden. Never throws: a throw logs one error row and counts {@link #evictFailed}; a body no
     * side lists counts {@link #evictAbsent}.
     *
     * @return 1 when the member left the list, else 0
     */
    private int evictDeployedMember(long entityId, ShipAPI body) {
        try {
            int owner = body.getOwner();
            int original = body.getOriginalOwner();
            if (DeployedSetEvictLane.evictFrom(plugin.engine, owner, body) || (original != owner
                    && DeployedSetEvictLane.evictFrom(plugin.engine, original, body))) {
                bodiesEvicted++;
                return 1;
            }
            evictAbsent++;
        } catch (RuntimeException evictThrew) {
            evictFailed++;
            PilotWatchLog.error("predict respawn-corpse: entity " + entityId + " deployed-list"
                    + " eviction FAILED -> body disposed, its member stays listed (evictFailed="
                    + evictFailed + ")", evictThrew);
        }
        return 0;
    }
}
