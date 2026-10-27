package cc.starsector.client;

import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import org.lwjgl.util.vector.Vector2f;

import cc.starsector.client.net.ServerEntityPose;

/**
 * P8 spawn leg — the OWN-SEAT LIFECYCLE carved out of
 * {@link PredictReconcileCombatPlugin} (R6: zero behaviour change) as a REAL extraction:
 * the server-decided death edges ({@link #killFoe}, {@link #killOwn}), the
 * B2(c) owner-mint fix ({@link #fixOwnSpawnOwner}), the fly-in and at-rest respawns
 * ({@link #respawnOwn}, {@link #respawnOwnAtRest}), the W13 claim-at-play field
 * ({@link #claimOwnSeatAtPose}), the alive re-field resolver
 * ({@link #maintainOwnVariantRefield}), the shared own-spawn core ({@link #fieldOwnAtPose}) and
 * the post-respawn HUD/FoW sampler ({@link #tickOwnRespawnSampler}). The bodies are the plugin's,
 * unchanged but for the {@code plugin.} / {@code PredictReconcileCombatPlugin.} qualification of
 * the SHARED state they read (the seat body, the kill latches, the pose-stream baselines, the
 * claim latch and its counters — the roster, transfer and mount lanes read those too). The state
 * that only these bodies ever touched moved WITH them and is private here: the one-shot log
 * latches, the bounded re-field attempt counter and its cap, the sampler clock, the owner-mint
 * counter and its log cap. Render-thread only (every caller is the plugin's advance() /
 * applyMatchReset() or {@link CommandTransferLane}); fail-soft per edge (a local FX or spawn
 * failure logs and leaves the latch for the next frame's retry). B-legal: engine API + the plugin.
 * Own file per the NEW-LANE LAW ({@code deferred-refactors.md}).
 */
final class OwnSeatLifecycleLane {
    private final PredictReconcileCombatPlugin plugin;

    OwnSeatLifecycleLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** one-shot log latch for the transfer-pod HOLD in the resolver. */
    private boolean ownRefieldHeldForPodLogged;
    private int ownRefieldAttempts;
    private boolean claimDeathSkipLogged;
    /** Bounded per-frame retry of a failed alive re-field (~2 s at 60 fps) — loud give-up, never a silent forever-loop. */
    private static final int OWN_REFIELD_MAX_ATTEMPTS = 120;
    /** One-shot: respawnOwn could resolve NO variant (defensive — ownKilled implies a prior own ship to read). */
    private boolean ownRespawnNoVariantLogged;
    /** Post-respawn sampler: 1 Hz ×5 s window end (0 = disarmed) + next due. */
    private long ownRespawnSampleEndMillis;
    private long ownRespawnSampleNextMillis;
    /** B2(c): fresh own hulls re-minted from the engine's neutral (100) at the spawn instant. */
    private int ownSpawnOwnerMints;
    private static final int OWNER_MINT_LOG_MAX = 20;

    /**
     * Run the engine's OWN death sequence on the foe when the server reports it dead (reusing the proven
     * server-owned-real-death path): place it at its last authoritative pose, force a
     * hulk, then apply massive shield-bypassing HE so the engine runs its real explosion + drifting hulk.
     * Idempotent via {@link PredictReconcileCombatPlugin#foeKilled}; fail-soft. Clears {@link PredictReconcileCombatPlugin#capFoeDamage} first
     * (axis C) so {@link HullCapLane.FoeDamageCap} does NOT cap this authoritative kill blow — the cap only ever bounds
     * the player's cosmetic local hits, never the server's decided death.
     */
    void killFoe(ServerEntityPose lastPose) {
        if (plugin.foeKilled || plugin.foeShip == null || plugin.engine == null || !plugin.foeShip.isAlive()) {
            return;
        }
        plugin.foeKilled = true;
        plugin.foeDeathTick = plugin.lastSnapshotSimTick; // r1 kill tick (ServerDealtSourceLaw)
        plugin.logFireMeter("killFoe"); // the kill = end of the firing window — emit the GAP 2 meter once here
        plugin.logSpeedMeter("killFoe"); // and the speed divergence summary at the same edge
        plugin.logClockMeter("killFoe"); // and the clock meter at the same edge
        try {
            if (lastPose != null) {
                plugin.foeState.reconcilePosition(plugin.foeShip, lastPose.locX(), lastPose.locY(), 1.0f); // snap to last known pose
            }
            // axis C: lift the cap so the 1,000,000 HE authoritative kill blow is NOT reduced to the floor.
            plugin.capFoeDamage = false;
            // Close the hull ledger on the SAME axis-C edge — the theater HE blow
            // below must never enter rawHull (post-close records are counted; expect exactly 1).
            if (plugin.hullLane.foeHullLedger != null) {
                plugin.hullLane.foeHullLedger.close("foe", lastPose != null ? lastPose.entityId() : -1L);
            }
            plugin.presenter.presentServerDeath(plugin.foeShip, lastPose != null ? lastPose.facing() : Float.NaN);
            plugin.foeState.deployedEvict.evict(plugin.engine, plugin.foeShip, "1v1-foe"); //
            // deathTick-keyed mint (v2) — the 1v1 foe's wire id from the pose when
            // carried, else the deploy-order id contract (the applySeatName idiom). Registry-shape
            // deaths route killRemote instead; the mint's per-corpse guard absorbs any overlap.
            // A dweller hull leaves no corpse (D7) — presentServerDeath already
            // routed it instant-remove; minting a fracture family here re-pinned the invisible hulk.
            if (!DwellerHullLaw.isDweller(plugin.foeShip)) {
                plugin.fractures.mintFractureFamily(lastPose != null ? lastPose.entityId()
                        : plugin.session.shipId() == 1L ? 2L : plugin.session.shipId() == 2L ? 1L : -1L, plugin.foeShip);
            }
        } catch (Throwable ignore) {
            // the authoritative death is already decided server-side; a local FX failure must not break the watch
        }
    }

    /**
     * The server-decided REAL death of the OWN ship: the proven {@link #killFoe}/
     * {@link FoeStateLaneCoordinator#killRemote} core applied to the seat. Triggered ONLY on the authoritative edge (own id
     * absent after an established mapping, or an authoritative hull&le;0), never locally:
     * {@link HullCapLane.OwnDamageCap} keeps a local hit from deciding death (axis B) and is cleared HERE (axis C)
     * so the presenter's kill blow passes. Snaps to {@link PredictReconcileCombatPlugin#lastOwnPose} — the same-frame
     * kill+roster-drop means the drop frame carries no own pose, so the previous frame's pose is the
     * authoritative death pose (a measured proximity of ≈408 u ⇒ low visible-jump risk). The engine then
     * runs its NATIVE death — control loss, the correct corpse at the authoritative pose, real FX —
     * and the native mission end reads the now-truthful local world by itself (DEFEAT here, VICTORY in
     * the killer's window; D3 = zero UI code). The consumed EDSP reason (D4) is carried in the log
     * line for the WATCH axis; it does not gate the edge. One-shot via {@link PredictReconcileCombatPlugin#ownKilled}; fail-soft.
     * Afterwards ownPose stays null, so every own-lane treatment idles via its existing null-skip, and
     * killOwn touches no flagship state (the {@code getCombatUI()} NPE avoid-item).
     */
    void killOwn(ServerEntityPose poseOrNull) {
        if (plugin.claimPending) {
            // W13 leg 2c: own-id absence while the seat claim is pending is "not yet
            // fielded", never a death — the own body here is the engine placeholder pod.
            if (!claimDeathSkipLogged) {
                claimDeathSkipLogged = true;
                PilotWatchLog.info("predict claim: own id absent while the seat claim is pending"
                        + " -> death edge SKIPPED (not yet fielded; one-shot log)");
            }
            return;
        }
        // The not-alive disjunct EXEMPTS a DEPARTED read — a genuine own
        // retreat's native fly-off REMOVES the body, and vanilla isShipAlive() is false for a
        // removed hull even at hp>0 (wasRemoved() fails its final clause), so this guard was
        // rejecting exactly the bodies the DEPARTED skip-branch below expects: ownKilled never
        // latched and the respawn edge (which requires it) stayed dead — ANY own retreat
        // permanently stranded the own client. Entry on DEPARTED is safe: that branch presents
        // nothing (latch + fail-quiet arc-seed only), and post-latch the proven death-respawn
        // machinery fields the fly-in.
        if (plugin.ownKilled || plugin.ownShip == null || plugin.engine == null
                || (!plugin.ownShip.isAlive()
                        && plugin.ownDespawnReason != cc.starsector.client.net.ClientDespawnReason.DEPARTED)) {
            return;
        }
        plugin.ownKilled = true;
        plugin.ownDeathTick = plugin.lastSnapshotSimTick; // r1 kill tick — the dismount latch never sets it
        plugin.fireLanes.lastLifecycleEdgeTick = plugin.lastSnapshotSimTick; // the kill is a lifecycle edge
        plugin.logFireMeter("killOwn"); // own death = end of this window's firing window — same edge trio as killFoe
        plugin.logSpeedMeter("killOwn");
        plugin.logClockMeter("killOwn");
        // Seed the engine's death shuttle BEFORE the body goes — the
        // player-ship flip feeds the arc renderer the shuttle's selected group;
        // bare = null feed = no-op = the dead hull's arcs linger on the wreck. Every branch below
        // removes/hulks the body (death, dive, retreat), so the seed precedes them all. Fail-quiet.
        DeathShuttleArcSeed.seedIfBare(plugin.engine);
        try {
            // match-flow: an own DEPARTURE (retreat) is a server removal the client's
            // LOCAL engine already presented natively — its own accepted retreat flew the ship off
            // the map and removed it (the bit-9 capture gate guarantees the local engine ran the
            // retreat too). Presenting a server DEATH here would snap the departed ship back and
            // mint a corpse + kill FX for a ship that left intact. The round-end verdict is
            // untouched — a full retreat IS the loss, decided server-side (no new
            // RETREATED verdict tier).
            // OWN hull dived out (emergency dive) — unlike the retreat skip
            // below, NO native fly-off happened locally (the server decided the dive), so the
            // body must go: corpse-free removal, no death FX, no fracture mint. ownKilled is
            // already latched above, so the fly-in respawn flow stays armed.
            if (plugin.phaseDive.finishDeparture(plugin.engine, plugin.session.shipId(), plugin.ownShip)) {
                PilotWatchLog.info("predict killOwn: OWN ship emergency-dive DEPARTED ->"
                        + " corpse-free removal despawnTick=" + plugin.ownDespawnTick);
                return;
            }
            if (plugin.ownDespawnReason == cc.starsector.client.net.ClientDespawnReason.DEPARTED) {
                // fail-safe: a genuine retreat's native fly-off already removed
                // the local body, so a DEPARTED read with the own body still IN-PLAY means no
                // fly-off ran and no dive enrolled — upstream misclassified (a
                // stale dive latch turned a plain death into DEPARTED; the live hull then
                // duplicated at the fly-in respawn). Remove it corpse-free. The log string is
                // deliberately OUTSIDE watch-verdict's killOwn_reason accepted list — a firing
                // fail-safe means upstream misread, AMBER is the correct grade.
                if (plugin.engine.isEntityInPlay(plugin.ownShip)) {
                    plugin.removeStaleBody(plugin.ownShip, "own-departed-misread"); // Evict + remove
                    PilotWatchLog.info("predict killOwn: OWN ship DEPARTED-misread fail-safe -"
                            + " live in-play body removed (no native fly-off, no dive"
                            + " enrollment) despawnTick=" + plugin.ownDespawnTick);
                    return;
                }
                PilotWatchLog.info("predict killOwn: OWN ship DEPARTED (server retreat removal;"
                        + " local engine already presented the native fly-off) -> presentServerDeath"
                        + " SKIPPED despawnTick=" + plugin.ownDespawnTick);
                return;
            }
            ServerEntityPose deathPose = poseOrNull != null ? poseOrNull : plugin.lastOwnPose;
            if (deathPose != null) {
                plugin.foeState.reconcilePosition(plugin.ownShip, deathPose.locX(), deathPose.locY(), 1.0f); // snap to the death pose
            }
            plugin.capOwnDamage = false; // axis C: the authoritative kill blow must pass OwnDamageCap untouched
            plugin.presenter.presentServerDeath(plugin.ownShip, deathPose != null ? deathPose.facing() : Float.NaN);
            plugin.foeState.deployedEvict.evict(plugin.engine, plugin.ownShip, "own-death"); //
            // deathTick-keyed mint (v2; reads ownDespawnTick while still set —
            // the DEPARTED skip above mints nothing: a retreat leaves no corpse).
            plugin.fractures.mintFractureFamily(plugin.session.shipId(), plugin.ownShip);
            PilotWatchLog.info("predict killOwn: OWN ship server-decided death applied locally"
                    + " pose=" + (deathPose != null ? "authoritative" : "local-fallback")
                    + " reason=" + (plugin.ownDespawnReason != null ? plugin.ownDespawnReason : "none-received")
                    + " despawnTick=" + plugin.ownDespawnTick);
        } catch (Throwable ignore) {
            // the death is decided server-side; a local FX failure must not break the watch
        }
    }

    /**
     * The H-A owner-mint fix, shared by the three OWN spawn sites: the engine
     * mints the fresh {@code Ship} NEUTRAL (owner 100) on our deploy arg-shape (post-respawn sampler
     * ground truth: {@code owner=100 originalOwner=100} at the post-spawn instant, both respawns)
     * while the {@code FleetMember} carries the correct side — and HUD palette + FoW reveal read
     * the LIVE owner, so the blue own-bars + fog miss cure here in one move. {@code setOwner} is
     * the engine's OWN lever (its station-hulk deploy path calls {@code setOwner(100)} the same
     * way). {@code originalOwner} may stay 100 — FleetMember-lane, cosmetic ({@code isAlly} stays
     * false, sampler watches it). FOE replica spawns are deliberately untouched.
     */
    void fixOwnSpawnOwner(ShipAPI newOwn, int side, String where) { // package: OwnSeatLifecycleLaneSmoke L1 drives it
        int before = newOwn.getOwner();
        if (before == side) {
            return;
        }
        newOwn.setOwner(side);
        ownSpawnOwnerMints++;
        if (ownSpawnOwnerMints <= OWNER_MINT_LOG_MAX) {
            PilotWatchLog.info("predict B2(c) owner-mint fix #" + ownSpawnOwnerMints + " [" + where
                    + "]: fresh own hull owner " + before + " -> " + side
                    + " (H-A neutral mint; HUD/FoW read the live owner)"
                    + (ownSpawnOwnerMints == OWNER_MINT_LOG_MAX
                            ? " [log cap reached; counter continues]" : ""));
        }
    }

    /**
     * THE own-seat
     * MINT, shared by the three OWN spawn sites (the death-edge / re-field core {@link #fieldOwnAtPose},
     * the registry match-reset rebuild and the 1v1 match-reset rebuild in
     * {@link PredictReconcileCombatPlugin#applyMatchReset}): feed gate muted (no
     * "Incoming vessels" line), the captain-bearing 5-arg overload (a fresh seat hull must
     * carry the SAME json-assigned officer the mission build attached and the server re-attaches on every
     * remount, or it predicts with vanilla-default stats against an officered authority; a null captain is
     * the 4-arg body verbatim), then the post-spawn sequence in
     * the order the H-A fix requires: {@link #fixOwnSpawnOwner} BEFORE the player re-point (B2(c)), the
     * CR seam, native player control ({@code setShipAI(null)}, the mission-deploy shape),
     * {@code setPlayerShipExternal} (the full vanilla UI re-point). {@code locOrNull} = the
     * engine-edge spawn (the match-reset expired-hold pose, never a hardcoded loc). Returns null when the
     * engine declines; every site keeps its own null branch (retry / bindShips-binds-what-exists).
     */
    ShipAPI spawnOwnSeat(CombatFleetManagerAPI mgr, int side, String variantId, Vector2f locOrNull,
            float facing, float flyinBurnSec, String where) {
        Boolean feedTok = plugin.feedGate.mute(mgr);
        ShipAPI newOwn;
        try {
            newOwn = mgr.spawnShipOrWing(variantId, locOrNull, facing, flyinBurnSec,
                    OfficerLoadout.captainOrNull(variantId,
                            MatchMissionBuilder.ownDescriptorVariantOrNull(), // the transfer case
                            MatchMissionBuilder.isOwnSeatShipId())); // the officer rule + W4 seat exclusion
        } finally {
            plugin.feedGate.unmute(mgr, feedTok);
        }
        if (newOwn == null) {
            return null;
        }
        // The registry match-reset SITE (not this shared mint) marks its fresh own
        // body for the adopt's fly-in keep — a respawn / re-field mint never adopts, the 1v1 reset's
        // server twin is a deployAll context burn.
        fixOwnSpawnOwner(newOwn, side, where); // B2(c): BEFORE setPlayerShipExternal
        CrDeploySeam.applyDeployCr(newOwn, plugin.descriptorDeployCr); // fresh own hull at descriptor CR
        newOwn.setShipAI(null);
        plugin.engine.setPlayerShipExternal(newOwn); // includes the CombatUI re-point
        plugin.driveLock.reassertOn(newOwn); // this frame's hold ran on the body newOwn replaces
        return newOwn;
    }

    /**
     * The OWN fly-in respawn: the server remounted the seat (cooldown:N
     * policy) and the own wire id reappeared ALIVE after {@link #killOwn}. Fields a
     * fresh own ship at the authoritative pose — the {@link PredictReconcileCombatPlugin#applyMatchReset} registry own-spawn
     * block MINUS the round sweep (mid-round: the world keeps fighting; the corpse stays as
     * wreckage, mirroring the server world's own wreck) — hands the player native control of it,
     * re-installs the axis-B kill guard on the fresh hull, and re-arms the pose-stream reconcile
     * baselines (the death gap + spawn teleport would otherwise read as one huge authoritative
     * velocity step into the rebase/glide detectors). Per-round queues/meters/counters deliberately
     * KEEP flowing — the session, intent lane and round baselines never stopped; only the body died.
     * Fail-soft: a failed spawn logs loud, leaves {@link PredictReconcileCombatPlugin#ownKilled} latched, and the advance() edge
     * retries next frame.
     */
    void respawnOwn(ServerEntityPose pose) {
        fieldOwnAtPose(pose, null, null, PredictReconcileCombatPlugin.OWN_SPAWN_FLYIN_BURN_SEC);
    }

    /**
     * The ONE-ENTITY pod's STRANDED / observer own mint: the own wire
     * id is ALIVE (it IS the pod; registry variant already {@code shuttlepod_cc_pod} via the RAPP
     * repoint), so the body appears AT the wire pose at rest — burn 0, no fly-in, no grow-in —
     * under the hidden local vanilla pod that lands onto it 3 s later ({@code B} needs a landing
     * to reach IDLE). Same spawn core as the death-edge respawn. {@link CommandTransferLane} only.
     */
    void respawnOwnAtRest(ServerEntityPose pose) {
        fieldOwnAtPose(pose, null, null, 0f);
    }

    // The in-place-adopted own body and the captain it would carry had the seat spawned it (null
    // twin = no override). Identity-keyed by design: engine bodies hash unreliably, and only the
    // one object the adopt kept ever matches. Overwritten by every in-place adopt and never
    // cleared at a transfer BEGIN: a RETURNING abort re-departs the very same body.
    private ShipAPI adoptedBody;
    private PersonAPI adoptedTwin;

    /**
     * The captain the BEGIN revert hands back to
     * {@code body} instead of the engine's {@code getOriginalCaptain()}. The in-place adopt keeps
     * the mission-built placeholder, whose original captain is the BUILD-time person (fielded
     * variant only); a spawned seat hull's original is the seat mint (fielded + descriptor, the
     * all-skills person when that match flag is on) — and the server's vacated hull gets the
     * latter back. Returns that twin for the adopted body ({@code ==}), else null (= the caller
     * keeps {@code getOriginalCaptain()}).
     */
    PersonAPI adoptedOriginalOrNull(ShipAPI body) {
        return body != null && body == adoptedBody ? adoptedTwin : null;
    }

    /** Records the adopted pair (both overwritten). */
    void rememberAdopted(ShipAPI body, PersonAPI twinOrNull) {
        adoptedBody = body;
        adoptedTwin = twinOrNull;
    }

    /**
     * The seat mint {@link #spawnOwnSeat} would have
     * passed for {@code fielded} — the same variant / descriptor / seat triple as that spawn and the
     * t0 stats lever. A mint throw (an unreadable skill table) logs once and yields null, so the
     * adopt still completes and the revert falls back to the build-time original.
     */
    private PersonAPI mintAdoptTwinOrNull(String fielded) {
        try {
            return OfficerLoadout.captainOrNull(fielded, MatchMissionBuilder.ownDescriptorVariantOrNull(),
                    MatchMissionBuilder.isOwnSeatShipId());
        } catch (Throwable t) {
            PilotWatchLog.error("predict claim: adopt twin captain mint threw (no revert override:"
                    + " the vacated hull reverts to its build-time captain)", t);
            return null;
        }
    }

    /**
     * W13 leg 2c: THE CLAIM — field the WELCOME's seat hull at its first alive wire
     * pose and re-point the player from the engine's placeholder pod (the observer posture the
     * zero-hull claim-at-play build leaves). Respawn core = {@link #fieldOwnAtPose} at rest (burn
     * 0: spawn → owner fix → deploy CR → {@code setShipAI(null)} → {@code setPlayerShipExternal}
     * → own cap / seat name / reconcile baselines — {@code CombatState.setPlayerShip}
     * re-points camera, HUD ship-info and control; the pod gets no AI back under
     * {@code doNotEndCombat}, and it was never in getShips). Then the transfer pointer deploy
     * would have set ({@code setShipPlayerLastTransferredCommandTo}). The
     * pod is NEVER removed or nulled ({@code setPlayerShipExternal(null)} dereferences
     * {@code getShield()}). The respawn core's log row says "AT REST"; the claim row
     * below is the grade tell.
     */
    void claimOwnSeatAtPose(ServerEntityPose pose) {
        ShipAPI before = plugin.ownShip;
        plugin.claimAttempts++;
        // The build fields ONE placeholder flagship (reserves==1 auto-deploy,
        // no deploy dialog) and it is the anchor here. Variants already agree => adopt it IN PLACE
        // (no spawn, no swap; snapped to the wire pose); else the respawn core fields the seat hull
        // with the placeholder as the stale body (removed after the re-point). An engine shuttle
        // anchor (a build that fielded nothing) is never passed as stale — it is not in getShips().
        String announced = plugin.variantById.get(Long.valueOf(plugin.session.shipId()));
        String fielded = before != null && before.getVariant() != null
                ? before.getVariant().getHullVariantId() : null;
        boolean placeholderAlive = before != null && before.isAlive() && !before.isShuttlePod();
        if (placeholderAlive && announced != null && announced.equals(fielded)) {
            plugin.claimPending = false;
            plugin.claimHold.release(before); // rider 6: the held placeholder IS the seat hull now
            rememberAdopted(before, mintAdoptTwinOrNull(fielded)); // its own original = the seat's twin, never the build-time person
            plugin.rejoin.hardSnapToPose(before, pose);
            try {
                plugin.engine.setShipPlayerLastTransferredCommandTo(before);
            } catch (Throwable t) {
                PilotWatchLog.error("predict claim: setShipPlayerLastTransferredCommandTo threw (transfer"
                        + " pointer stays engine-default)", t);
            }
            MatchMissionBuilder.noteFieldedOwnVariant(fielded);
            CcClientModPlugin.noteClaimSettled();
            PilotWatchLog.info("predict claim: seat " + plugin.session.shipId() + " placeholder flagship IS the"
                    + " seat hull (" + fielded + ") -> adopted IN PLACE at wire pose (" + pose.locX() + ","
                    + pose.locY() + ") on attempt " + plugin.claimAttempts + " (no spawn; W13 leg 2c CLAIM AT"
                    + " PLAY)");
            return;
        }
        fieldOwnAtPose(pose, placeholderAlive ? before : null, placeholderAlive ? fielded : null, 0f);
        if (plugin.ownShip == null || plugin.ownShip == before) {
            if (plugin.claimAttempts == 1 || plugin.claimAttempts % 120 == 0) {
                PilotWatchLog.error("predict claim: own seat hull did NOT field at wire pose (attempt "
                        + plugin.claimAttempts + ") -> retrying next frame", null);
            }
            return;
        }
        plugin.claimPending = false;
        plugin.claimHold.release(plugin.ownShip); // rider 6: the held body was the stale placeholder (removed)
        try {
            plugin.engine.setShipPlayerLastTransferredCommandTo(plugin.ownShip);
        } catch (Throwable t) {
            PilotWatchLog.error("predict claim: setShipPlayerLastTransferredCommandTo threw (transfer"
                    + " pointer stays engine-default)", t);
        }
        CcClientModPlugin.noteClaimSettled();
        PilotWatchLog.info("predict claim: seat " + plugin.session.shipId() + " hull FIELDED at wire pose ("
                + pose.locX() + "," + pose.locY() + ") variant="
                + (plugin.ownShip.getVariant() != null ? plugin.ownShip.getVariant().getHullVariantId() : "?")
                + " on attempt " + plugin.claimAttempts + " -> player re-pointed from the placeholder "
                + (placeholderAlive ? "flagship " + fielded + " (removed)" : "engine pod")
                + " (W13 leg 2c CLAIM AT PLAY)");
    }

    /**
     * Resolve an armed {@link PredictReconcileCombatPlugin#ownRefieldPending}: the
     * registry's OWN variant (the server's announced fielded variant) against the LIVE own hull's.
     * Equal ⇒ a replay/duplicate row, or a respawn already fielded it (cleared, one log line);
     * different ⇒ the alive re-field through {@link #fieldOwnAtPose} with the live hull as the
     * stale body. A failed spawn retries per frame, bounded — a seat that can never re-field
     * stays LOUD (never conceal) but must not error every frame forever.
     */
    void maintainOwnVariantRefield(ServerEntityPose pose) {
        String announced = plugin.variantById.get(Long.valueOf(plugin.session.shipId()));
        String fielded = plugin.ownShip.getVariant() != null ? plugin.ownShip.getVariant().getHullVariantId() : null;
        if (announced == null || announced.equals(fielded)) {
            plugin.ownRefieldPending = false;
            ownRefieldAttempts = 0;
            ownRefieldHeldForPodLogged = false;
            PilotWatchLog.info("predict refieldOwn: announced OWN variant " + FallbackAnnounce.sanitize(announced, 64)
                    + " already fielded -> no re-field (pending cleared)");
            return;
        }
        // The triple-pod case: the server fans the seat's
        // cc_pod re-point (RAPP) one datagram BEFORE the transfer BEGIN; when a render frame lands
        // between them this resolver saw "announced pod != fielded hull while ALIVE" with
        // !xfer.ownTransit() still true and swapped the player's hull for a pod A one frame before
        // the BEGIN captured ownShip as the vacated source — pod A then stood as the vacated hull's
        // wire body, the vanilla shuttle (no longer the player ship) went unadopted, and the
        // observer lane minted pod B. The cc_pod re-point is TRANSFER-OWNED: the BEGIN/observer
        // lane fields the pod body (respawnOwnAtRest); this resolver never does. HOLD the pending
        // arm (never clear: the 2 s own-hull invariant backstop would re-arm it mid-transit) — it
        // resolves "already fielded" at the COMMIT re-point or on the next non-pod row.
        if (cc.starsector.client.net.ClientCommandTransfer.POD_WIRE_VARIANT_ID.equals(announced)) {
            if (!ownRefieldHeldForPodLogged) {
                ownRefieldHeldForPodLogged = true;
                PilotWatchLog.info("predict refieldOwn: announced OWN variant is the transfer pod ("
                        + FallbackAnnounce.sanitize(announced, 64) + ") while fielded=" + fielded
                        + " -> HELD, transfer-owned (the"
                        + " BEGIN/observer lane fields the pod body; pending stays armed until the"
                        + " COMMIT re-point resolves it; one-shot log)");
            }
            return;
        }
        if (ownRefieldAttempts >= OWN_REFIELD_MAX_ATTEMPTS) {
            plugin.ownRefieldPending = false;
            ownRefieldAttempts = 0;
            PilotWatchLog.error("predict refieldOwn: GAVE UP after " + OWN_REFIELD_MAX_ATTEMPTS
                    + " failed attempts (announced=" + FallbackAnnounce.sanitize(announced, 64) + " fielded=" + fielded
                    + ") -> the seat keeps flying the DESCRIPTOR hull (own-variant DESYNC stays"
                    + " until the next own respawn)", null);
            return;
        }
        ownRefieldAttempts++;
        fieldOwnAtPose(pose, plugin.ownShip, fielded, PredictReconcileCombatPlugin.OWN_SPAWN_FLYIN_BURN_SEC);
    }

    /**
     * The OWN spawn core shared by the death-edge respawn ({@code staleAlive == null}) and the
     * alive re-field ({@code staleAlive} = the live descriptor hull, removed AFTER the
     * fresh hull is the player ship — the match-reset spawn-before-remove idiom, never
     * player-shipless; corpse-free, no death FX, no fracture mint). Reads the registry variant
     * for the own wire id; everything below the spawn is the same fresh-hull re-arm either way.
     */
    private void fieldOwnAtPose(ServerEntityPose pose, ShipAPI staleAlive, String staleVariant,
            float flyinBurnSec) {
        if (plugin.engine == null || pose == null) {
            return;
        }
        String variantId = plugin.variantById.get(Long.valueOf(plugin.session.shipId()));
        if (variantId == null && plugin.ownShip != null) {
            variantId = plugin.ownShip.getVariant().getHullVariantId();
        }
        if (variantId == null) {
            if (!ownRespawnNoVariantLogged) {
                ownRespawnNoVariantLogged = true;
                PilotWatchLog.error("predict respawnOwn: own wire id reappeared ALIVE but NO variant"
                        + " resolves (no descriptor row for ownId=" + plugin.session.shipId()
                        + " and no prior own ship) -> respawn skipped (one-shot log)", null);
            }
            return;
        }
        try {
            float facing = Float.isFinite(pose.facing()) ? pose.facing() : 90f;
            ShipAPI newOwn = spawnOwnSeat(plugin.engine.getFleetManager(plugin.sideAuthority()),
                    plugin.sideAuthority(), variantId, new Vector2f(pose.locX(), pose.locY()), facing,
                    flyinBurnSec, "respawnOwn"); // The shared own-seat mint
            if (newOwn == null) {
                PilotWatchLog.error("predict respawnOwn: spawnShipOrWing returned null (variant="
                        + FallbackAnnounce.sanitize(variantId, 64) + ") -> retry next frame", null);
                return;
            }
            plugin.ownShip = newOwn;
            MatchMissionBuilder.noteFieldedOwnVariant(variantId); // The next claim-at-play placeholder guess
            if (staleAlive != null && staleAlive != newOwn) {
                // The stale descriptor body has no wire feed of its own and would
                // ghost beside the fresh hull — remove it now that the player ship is re-pointed.
                // Through the evict+remove verb — the claim-at-play placeholder
                // pod is a mission-DEPLOYED member; a bare removeEntity left it in the deployed set.
                plugin.removeStaleBody(staleAlive, "own-field-stale-body");
            }
            // Fresh hull at the SAME wire id (OwnFireLockLaw sub-4 hazard) —
            // drop held autofire picks + ordinal cache; the fresh weapons re-enter the pick
            // hook so wrappers re-mint free, and the server lane re-emits on its next diff.
            plugin.afTargets.dropPicks();
            // The DEFECT-1 lever on the fresh hull (immediate fire stays + arms the settled re-fire).
            OfficerLoadout.applySeatStatsLever(newOwn, variantId,
                    MatchMissionBuilder.ownDescriptorVariantOrNull(),
                    MatchMissionBuilder.isOwnSeatShipId(),
                    staleAlive != null ? "refield" : "respawn", plugin.initialWorldAdopted);
            // The respawn-armed settled re-fire must ride out the fly-in burn — a bare
            // 2 s deadline landed mid-travel-drive (burn=4 s) and graded warp-in
            // transients as settled rows (the off_lever_sym 686.8 == fly-in 685.0).
            plugin.leverSettledRefireAtMillis = System.currentTimeMillis()
                    + (long) (flyinBurnSec * 1000f)
                    + PredictReconcileCombatPlugin.LEVER_SETTLED_REFIRE_DELAY_MILLIS;
            plugin.rejoin.hardSnapToPose(newOwn, pose); // adopt the fly-in kinematics (vel + angVel) — first-sight idiom
            plugin.ownKilled = false;
            plugin.ownDeathTick = Long.MIN_VALUE; // r1 — the fresh body is alive
            plugin.capOwnDamage = true;     // axis B re-arms on the fresh hull...
            plugin.ownCapInstalled = false;
            plugin.installOwnCap();         // ...via a fresh OwnDamageCap listener on the new body
            plugin.seatHud.applySeatName(newOwn, plugin.session.shipId());
            plugin.fractures.suppressLocalBreakProb(newOwn); // The fresh hull's roll is server-owned too
            // Re-arm the pose-stream reconcile baselines the death gap made discontinuous:
            // step-rebase detector, velocity glide, above-cap escape clock (the finishMatchReset
            // subset that tracks the own pose stream; round-scoped state stays untouched).
            plugin.prevAuthVelX = Float.NaN;
            plugin.prevAuthVelY = Float.NaN;
            plugin.prevAuthVelSimTick = Long.MIN_VALUE;
            plugin.lastAuthStepPerTick = Float.NaN;
            plugin.glideWasWriting = false;
            plugin.escapePrevSimTick = Long.MIN_VALUE;
            plugin.aboveCapTicks = 0L;
            plugin.velRecon.reset(); // The death gap ends any engage episode with the life
            plugin.ownDespawnReason = null; // the consumed EDSP reason belonged to the ended life
            plugin.ownDespawnTick = -1L;
            plugin.lastOwnPose = pose;
            // The fresh hull is an own-hull EPOCH bump (arm the
            // dark-gun disable settle) and a lifecycle edge (own-engine disagree detail).
            plugin.fireLanes.ownHullEpoch++;
            plugin.fireLanes.ownHullEpochBumpTick = plugin.lastSnapshotSimTick;
            plugin.fireLanes.lastLifecycleEdgeTick = plugin.lastSnapshotSimTick;
            // Either path fields the registry variant, so any armed alive re-field is resolved.
            plugin.ownRefieldPending = false;
            ownRefieldAttempts = 0;
            ownRefieldHeldForPodLogged = false;
            if (staleAlive != null) {
                plugin.ownRefields++;
                PilotWatchLog.info("predict refieldOwn: announced OWN variant != fielded while ALIVE"
                        + " -> fresh own ship fielded at wire pose (" + pose.locX() + ","
                        + pose.locY() + ") variant=" + FallbackAnnounce.sanitize(variantId, 64)
                        + " (stale " + staleVariant
                        + " removed) refields=" + plugin.ownRefields);
            } else {
                plugin.ownRespawns++;
                PilotWatchLog.info("predict respawnOwn: OWN wire id reappeared ALIVE -> fresh own ship"
                        + " fielded at wire pose (" + pose.locX() + "," + pose.locY() + ") variant="
                        + FallbackAnnounce.sanitize(variantId, 64) + " respawns=" + plugin.ownRespawns
                        + " burn=" + flyinBurnSec
                        + (flyinBurnSec > 0f ? " (fly-in respawn)"
                                : " (AT REST: one-entity pod strand/observer)"));
            }
            // spawn-instant identity row + arm the 1 Hz ×5 s discriminator.
            PilotWatchLog.info("predict respawnOwn identity: newOwn owner=" + newOwn.getOwner()
                    + " originalOwner=" + newOwn.getOriginalOwner() + " ally=" + newOwn.isAlly()
                    + " hulk=" + newOwn.isHulk() + " alive=" + newOwn.isAlive());
            ownRespawnSampleEndMillis = System.currentTimeMillis() + 5_000L;
            ownRespawnSampleNextMillis = 0L;
        } catch (Throwable t) {
            PilotWatchLog.error((staleAlive != null ? "predict refieldOwn" : "predict respawnOwn")
                    + ": fresh own spawn FAILED -> retry next frame ("
                    + (staleAlive != null ? "ownRefieldPending stays armed, bounded"
                            : "ownKilled stays latched") + ")", t);
        }
    }

    /**
     * The post-respawn HUD/FoW discriminator sampler (H-A vs H-C data; rides
     * ANY watch with a death cycle, no dedicated watch): for 5 s after {@link #respawnOwn}, log at
     * 1 Hz the fresh hull's owner/alive/hulk state plus {@code engine.getFogOfWar(0).isVisible}
     * (public FogOfWarAPI — does the player-side fog treat the fresh hull as a reveal source?).
     * Render-thread only (called beside {@link PredictReconcileCombatPlugin#captureOwnCommands}); fail-soft by contract.
     */
    void tickOwnRespawnSampler() {
        if (ownRespawnSampleEndMillis == 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= ownRespawnSampleEndMillis) {
            ownRespawnSampleEndMillis = 0L;
            return;
        }
        if (now < ownRespawnSampleNextMillis || plugin.ownShip == null) {
            return;
        }
        ownRespawnSampleNextMillis = now + 1_000L;
        try {
            String fog;
            try {
                fog = String.valueOf(plugin.engine.getFogOfWar(0).isVisible(plugin.ownShip));
            } catch (Throwable t) {
                fog = "read-failed:" + t.getClass().getSimpleName();
            }
            // The live getOwner() read IS the H-A probe (does the fresh hull's owner read
            // non-0 at HUD/FoW time?) — instrumentation only, never a side filter.
            PilotWatchLog.info("predict respawn-sampler identity: owner=" + plugin.ownShip.getOwner() // LINT-ALLOW corpse-side
                    + " originalOwner=" + plugin.ownShip.getOriginalOwner() + " ally=" + plugin.ownShip.isAlly()
                    + " hulk=" + plugin.ownShip.isHulk() + " alive=" + plugin.ownShip.isAlive()
                    + " fogVisibleToSide0=" + fog);
        } catch (Throwable ignore) {
            // sampler is instrumentation; never break the frame
        }
    }
}
