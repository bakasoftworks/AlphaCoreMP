package cc.starsector.client;

import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.net.ServerSnapshotFrame;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;

import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The client ADOPT-BIND lane for a served STATION (the D5 Bastion leg-4
 * Star Fortress, {@code station3_Standard}). NEW-LANE LAW collaborator of
 * {@link PredictReconcileCombatPlugin} (the {@link FractureFamilyCoordinator} shape precedent):
 * the plugin owns descriptor acceptance, the per-frame drive call and the round reset; every
 * station law lives here.
 *
 * <p><b>Why an ADOPT lane and not another spawn lane.</b> A station body is minted by the ENGINE
 * in one deploy — base hull plus its whole engine-minted module cluster, 24 of them on this hull
 * — never piecewise by the registry. A NON-OWN row gets that mint at mission init:
 * {@link MatchMissionBuilder} fields it into the ENEMY fleet and vanilla deploys it in
 * {@code CombatState} init ({@code deployStationAndRelatedIfNeeded} → the
 * one-member deploy body) BEFORE any plugin advance, so this lane CLAIMS the existing
 * bodies for their authoritative wire ids ({@link MissilePoseLane}'s {@code adoptAndLink} idiom:
 * claim by natural key, refuse anything ambiguous). An OWN-SIDE row (the DEFEND fortress)
 * is never mission-fielded — a station in the local side's reserves kills the
 * seat's reserves==1 auto-deploy and pops the
 * modal deploy dialog — so its base rides the registry's own spawn lane instead
 * ({@code spawnRemoteEntities} → {@code spawnShipOrWing} → the SAME one-member deploy body; its
 * station branch carries the module cluster along) and this lane adopts the base
 * from {@code re.ship} — exactly the post-MRST re-form path, where the reset sweeps every body,
 * the descriptor row survives, and spawn-on-sight re-fields the BASE. Both mints converge on one
 * code path here.
 *
 * <p><b>What binds and what does NOT.</b> Modules are NEVER pose-reconciled:
 * {@code CombatEngine.updateStationModuleLocations} re-derives every module's
 * location AND facing from its parent every frame, so a module pose write is overwritten within
 * the frame and the authoritative correction rides the BASE alone. A module row exists for
 * identity, hull, engine/weapon state and DEATH only ({@code re.stationModule} gates the pose
 * lanes off in {@code maintainRemoteEntities}).
 *
 * <p><b>The natural key.</b> {@code spec(variantId) + module-center position}. Mirror-slot aliasing
 * is real on this hull (WS 010/011, WS 019/020, WS 012/014 are same-variant, same-HP pairs), so a
 * distance-from-base magnitude cannot disambiguate a wire module: the assignment rotates the wire
 * module's offset from the WIRE base pose into the base's own frame (by {@code -facing}, degrees,
 * CCW — ship-tier wire poses are RAW floats as {@code AwssSnapshotReader} reads them; the u16 facing
 * quantization is missile/point tier only) and takes the NEAREST declared slot within
 * {@link #toleranceFor} = 0.5 × the minimum pairwise MODULE-CENTER separation (76.909 su ⇒ 38.45
 * on station3, while the closest same-variant ALIAS pair sits 136.6 su apart and every module
 * center sits 240-584 su from the base). The declared position is the module's CENTER, never the
 * raw weapon-slot node — see {@link #ensureSlots}. Slot ANGLE is deliberately not a discriminator:
 * WS 018 and WS 021 share a slot angle exactly, so facing is degenerate here.
 *
 * <p><b>Fail-closed posture.</b> Every refusal path NEUTRALIZES the cluster (base + children)
 * through the ONE ledgered helper {@link PredictReconcileCombatPlugin#neutralizeRemoteBody} —
 * inert AI, replay-driven hold-fire, local break-prob suppression, and AT MOST ONE
 * {@code RemoteDamageCap} per body (a second cap on an unregistered entry is never cleared by
 * {@code killRemote} ⇒ a locally IMMORTAL station and a double-counted damage witness). A
 * contract break LATCHES: the cluster stops binding, stays inert, and says so once, loudly. Zero
 * wire change: this lane is client-side presentation/binding only.
 */
final class StationAdoptBindLane {

    /**
     * How many drive frames a cluster may WAIT for its local children / the authority's module
     * rows before the wait expires and logs. A wait is never a latch: an incomplete picture is
     * "not yet", not a contract break (~10 s at 60 fps).
     */
    private static final int MODULE_WAIT_FRAMES = 600;

    private final PredictReconcileCombatPlugin plugin;

    /** ACCEPTED station rows: base wire id → station variant id (declaration order). Descriptor
     *  rows (plugin init) AND wire-appended rows ({@link #acceptAppendedRow}) share this map. */
    private final Map<Long, String> acceptedRows = new LinkedHashMap<Long, String>();

    /**
     * The subset of {@link #acceptedRows} that arrived as RAPP appends (a
     * {@code /spawnfoe}-fielded modular parent such as the Survey Mothership). ROUND-LOCAL like
     * the registry rows they mirror ({@link #resetRound} drops them); an appended base is never
     * the last descriptor row, so its band premise is the server's mint-time contiguous intern
     * (root+1..root+N, {@code PPICS.internWaveHull}) — {@link #ensureSlots} skips the
     * descriptor-only {@code maxDescriptorId} check for it.
     */
    private final Set<Long> appendedRows = new java.util.HashSet<Long>();

    /** Per-base bind state, keyed by base wire id (round-local; {@link #resetRound} clears it). */
    private final Map<Long, Cluster> clusters = new LinkedHashMap<Long, Cluster>();

    /** Local bodies already claimed as a cluster base — no second cluster may claim one. */
    private final Set<ShipAPI> claimedBases =
            Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());

    /**
     * The HIGHEST descriptor wire id (the dense id contract's N). The server interns a station's
     * module ids first-seen at t=0 directly after the descriptor rows, so {@code N+1 ..} is where
     * a module band can legally start — the {@link #assignWirePoses} band gate's premise.
     */
    private long maxDescriptorId;

    /** Descriptor-level refusal latch (a station row that IS the own seat): sweep + neutralize forever. */
    private boolean refused;
    private String refusedWhy = "";
    private boolean refusedSweepLogged;

    // Cumulative counters (round resets keep them; the totals line is the round's debrief).
    private int basesAdopted;
    private int bandCommits;
    private int bandDrops;
    private int moduleRowsBound;
    private int moduleRowsRepaired;
    private int moduleRowsKilled;
    private int latches;
    private int deadAtFirstSight;
    private int inertSweeps;

    StationAdoptBindLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================================================================================
    // Descriptor acceptance (plugin init)
    // ==================================================================================

    /**
     * Walk the dense id contract the plugin just built and take the STATION rows (engine seam
     * {@code getVariant(id).isStation()}, via {@link MatchMissionBuilder#isStationVariant}) —
     * ANY side: under DEFEND the fortress is an OWN-SIDE ally, and side-0 non-own
     * replicas are the established teammate lane (every treatment is body-identity-keyed and
     * side-agnostic, {@code neutralizeRemoteBody}; band/geometry/binding math never read side).
     *
     * <p>The ONE refusal left at this level is a station row that IS the own seat
     * ({@code wireId == ownShipId}): a station can never be the flagship ({@code getCombatUI}
     * NPEs) and the seat's controls must never drive a cluster. The refusal keeps the full
     * latch+sweep machinery — a station body that appears anyway is neutralized rather than left
     * fighting as a local sim. {@link MatchMissionBuilder} separately refuses to MISSION-FIELD
     * own-side station rows for its own mechanism (a station in
     * the local side's reserves sets {@code isDeployedStation}, which kills the seat's
     * reserves==1 auto-deploy regardless of
     * reserves count and pops the modal deploy dialog) — the own-side base rides the
     * direct spawn lane instead and this lane adopts it from the registry entry.
     */
    void acceptDescriptorRows(Map<Long, String> variantById, Map<Long, Integer> descSideById,
            int ownDescSide, long ownShipId) {
        try {
            for (Map.Entry<Long, String> row : variantById.entrySet()) {
                long wireId = row.getKey().longValue();
                String variantId = row.getValue();
                if (wireId > maxDescriptorId) {
                    maxDescriptorId = wireId; // the module id band's floor (see the field doc)
                }
                if (!MatchMissionBuilder.isStationVariant(variantId)) {
                    continue;
                }
                Integer absSide = descSideById.get(row.getKey());
                if (wireId == ownShipId) {
                    refuse("descriptor row id=" + wireId + " variant=" + variantId
                            + " IS the own seat (absSide=" + absSide + " ownDescSide="
                            + ownDescSide + ") — a station can never be the seat (flagship law:"
                            + " getCombatUI NPEs; the seat's controls must never drive a cluster)");
                    continue;
                }
                if (absSide == null) {
                    // Review fix: fail-closed twin of the plugin's dense walk —
                    // variantById/descSideById are filled in one loop today, so a missing side
                    // entry means the maps drifted; refuse the ambiguous row, never fabricate
                    // a side (the adoptAndLink doctrine: refuse anything ambiguous).
                    refuse("descriptor row id=" + wireId + " variant=" + variantId
                            + " has NO descriptor side entry (variantById/descSideById drift)");
                    continue;
                }
                int relSide = absSide.intValue() == ownDescSide ? 0 : 1;
                acceptedRows.put(row.getKey(), variantId);
                PilotWatchLog.info("predict station_bind: ACCEPTED descriptor station row id="
                        + wireId + " variant=" + variantId + " absSide=" + absSide
                        + " (relative side " + relSide + ") -> adopt-bind lane engaged");
            }
        } catch (Throwable t) {
            // Acceptance is the ONE place a throw could leave a half-armed lane: refuse the whole
            // lane (inert sweep only) rather than bind against a picture we do not trust.
            refuse("descriptor acceptance FAILED: " + t);
        }
    }

    /**
     * Accept a WIRE-APPENDED station row (the RAPP drain's unknown-id branch,
     * {@code RosterAppendLane.drain}, render thread) — a modular parent the server fielded
     * mid-match ({@code /spawnfoe station_derelict_survey_mothership_Standard @x,y}). The base
     * is minted by spawn-on-sight at its first pose (the engine builds the STATION_MODULE
     * children inside that same spawn call, neutralized there) and adopted from its registry
     * entry next frame; the module wire ids are the server's contiguous mint-time band
     * (root+1..root+N, never RAPP-announced), which {@link #assignWirePoses} claims by geometry
     * exactly as the fortress's t=0 band. Side-agnostic like every other treatment here. The
     * ONE refusal kept from the descriptor path: the own seat can never be a cluster base.
     */
    void acceptAppendedRow(long wireId, String variantId) {
        try {
            if (refused) {
                return; // the descriptor-level latch already neutralizes every station body
            }
            long ownId = plugin.session != null ? plugin.session.shipId() : -1L;
            if (wireId == ownId) {
                PilotWatchLog.error("predict station_bind: appended station row id=" + wireId
                        + " variant=" + variantId + " IS the own seat — not accepted (flagship"
                        + " law); the sweep keeps any such body inert", null);
                return;
            }
            if (acceptedRows.containsKey(Long.valueOf(wireId))) {
                return; // a replayed/duplicate append — the RAPP dedupe never reaches here, belt only
            }
            Long key = Long.valueOf(wireId);
            acceptedRows.put(key, variantId);
            appendedRows.add(key);
            PilotWatchLog.info("predict station_bind: ACCEPTED appended station row id=" + wireId
                    + " variant=" + variantId + " -> adopt-bind lane engaged (band = the server's"
                    + " mint-time intern root+1..root+N)");
        } catch (Throwable t) {
            refuse("appended-row acceptance FAILED: " + t);
        }
    }

    /** Latch the descriptor-level refusal (idempotent; the first reason wins and is logged once). */
    private void refuse(String why) {
        if (!refused) {
            refused = true;
            refusedWhy = why;
            latches++;
            PilotWatchLog.error("predict station_bind: REFUSED — " + why
                    + " -> no station is bound this match; every station body found locally is"
                    + " neutralized (inert AI + replay-driven hold-fire + break-prob suppression)",
                    null);
        }
    }

    // ==================================================================================
    // Per-frame drive
    // ==================================================================================

    /**
     * The fail-closed INERT sweep, driven on every live combat frame — feed or no feed. The
     * mission-minted cluster exists from combat init, so a pre-match hold (connected, no
     * snapshot yet) would otherwise leave 24 vanilla modules shooting at the seat with a local
     * AI. Neutralizing is unconditional; only the CAP install is entry-gated (a cap on a body
     * with no registry row is the immortal-station defect), so an unbound module is inert but
     * still locally damageable — the honest state until its wire id binds.
     */
    void sweepInert() {
        if (acceptedRows.isEmpty() && !refused) {
            return; // no station in this match — the lane costs one map check per frame
        }
        try {
            if (plugin.engine == null) {
                return;
            }
            inertSweeps++;
            for (ShipAPI ship : plugin.engine.getShips()) {
                if (ship == null || (!ship.isStation() && !ship.isStationModule())
                        || plugin.remoteBodyFullyTreated(ship)) {
                    continue; // treated and capped: nothing left for the sweep to do (per-frame cost)
                }
                RemoteEntityRef ref = entryFor(ship);
                if (plugin.neutralizeRemoteBody(ship, ref.entity) && refused
                        && !refusedSweepLogged) {
                    refusedSweepLogged = true;
                    PilotWatchLog.info("predict station_bind: refusal sweep NEUTRALIZED a station"
                            + " body (" + hullOf(ship) + ") — refusal reason: " + refusedWhy);
                }
            }
        } catch (Throwable ignore) {
            // presentation-side lane; never break the frame
        }
    }

    /**
     * The per-frame BIND drive. Placement is load-bearing: AFTER {@code RosterAppendLane.drain} (so
     * every RAPP-announced wire id is already a registry row and cannot be mistaken for a module)
     * and BEFORE {@code spawnRemoteEntities} (so an adopted base is bound to {@code re.ship}
     * before the spawn-on-sight gate would mint a SECOND station for the same wire id — the
     * NON-OWN mission-mint case; an OWN-SIDE row's base is legitimately minted BY
     * {@code spawnRemoteEntities} and adopted from its registry entry, so
     * "the spawn gate never mints a station" is NOT a law — do not add a station skip there).
     */
    void maintain(ServerSnapshotFrame snapshot) {
        if (refused || acceptedRows.isEmpty() || snapshot == null || plugin.engine == null) {
            return;
        }
        for (Map.Entry<Long, String> row : acceptedRows.entrySet()) {
            Cluster cluster = clusters.get(row.getKey());
            if (cluster == null) {
                cluster = new Cluster(row.getKey().longValue(), row.getValue(),
                        appendedRows.contains(row.getKey()));
                clusters.put(row.getKey(), cluster);
            }
            try {
                driveCluster(cluster, snapshot);
            } catch (Throwable t) {
                // A throw inside one cluster's bind is a contract break by definition: latch it
                // (inert + no binding) instead of retrying an unknown state every frame.
                latch(cluster, "drive threw: " + t);
            }
        }
    }

    /** One cluster, one frame: base adopt → (3) band scoping → neutralize → module bind/repair. */
    private void driveCluster(Cluster cluster, ServerSnapshotFrame snapshot) {
        PredictReconcileCombatPlugin.RemoteEntity re =
                plugin.remoteEntities.get(Long.valueOf(cluster.baseId));
        if (re == null) {
            return; // no registry row for the base (a shape without the id contract) — nothing to bind
        }
        // (3) The module id band is BASE-scoped: the entry's ship IS the band's anchor. A respawn
        // (respawnRemote nulls re.ship) or a post-MRST spawn-on-sight re-form hands us a DIFFERENT
        // body, and every claimed wire id then belongs to a cluster that no longer exists.
        if (cluster.base != null && re.ship != cluster.base) {
            dropBand(cluster, re.ship == null ? "base unbound (respawn/reset)" : "base re-minted");
        }
        if (cluster.base == null) {
            if (re.ship != null) {
                adoptBaseFromEntry(cluster, re);
            } else {
                adoptBaseFromWorld(cluster, re, snapshot);
            }
        }
        if (cluster.base == null) {
            return;
        }
        if (!plugin.engine.isEntityInPlay(cluster.base)) {
            dropBand(cluster, "base left play");
            return;
        }
        neutralizeCluster(cluster, re);
        if (cluster.latched) {
            return; // (6) a latched cluster keeps sweeping + neutralizing, never binds
        }
        bindModules(cluster, re, snapshot);
    }

    // ==================================================================================
    // Base adopt
    // ==================================================================================

    /** The post-MRST / spawn-on-sight case: the plugin already minted and treated the base. */
    private void adoptBaseFromEntry(Cluster cluster,
            PredictReconcileCombatPlugin.RemoteEntity re) {
        if (!plugin.engine.isEntityInPlay(re.ship)) {
            // NEVER re-adopt a corpse. The registry keeps re.ship pointing at the base after the
            // engine reaps the hulk, so adopting it would fail driveCluster's in-play gate on the
            // very next line and dropBand/adopt would alternate every render frame for the rest of
            // the round. A respawn mints a NEW in-play body and this path adopts it normally.
            if (!cluster.corpseSkipLogged) {
                cluster.corpseSkipLogged = true;
                PilotWatchLog.info("predict station_bind: base id=" + cluster.baseId + " registry"
                        + " body is NOT in play (reaped hulk) -> adopt SKIPPED; the cluster waits"
                        + " for a fresh body (respawn / spawn-on-sight re-form)");
            }
            return;
        }
        if (!claimedBases.add(re.ship)) {
            latch(cluster, "the spawn-on-sight base is already claimed by another station row");
            return;
        }
        cluster.base = re.ship;
        applyDeployCr(re.ship);
        basesAdopted++;
        PilotWatchLog.info("predict station_bind: base id=" + cluster.baseId + " ADOPTED from the"
                + " registry entry (spawn-on-sight re-form) hull=" + hullOf(re.ship)
                + " children=" + childCount(re.ship) + " (adopts=" + basesAdopted + ")"
                + " cr=" + re.ship.getCurrentCR());
    }

    /**
     * The mission-mint case: the engine deployed the cluster at combat init, so claim the local
     * body for the wire id. Gated on a LIVE authoritative pose — binding an id the wire does not
     * carry would hand it straight to the roster-absence death contract in
     * {@code maintainRemoteEntities}. A pose that is present but DEAD is case (5).
     */
    private void adoptBaseFromWorld(Cluster cluster, PredictReconcileCombatPlugin.RemoteEntity re,
            ServerSnapshotFrame snapshot) {
        ServerEntityPose pose = snapshot.entityById(cluster.baseId);
        if (pose == null) {
            // No-WIRE-pose check: an ACCEPTED row the wire never carries is the
            // failure the mission-side INFO skip cannot see. maintain() only runs with a live
            // snapshot, so these are FED frames without the base id — after the standard wait
            // that is "the server never fielded the fortress", not a pre-feed hold.
            if (!cluster.noPoseLogged && ++cluster.poseWaitFrames >= MODULE_WAIT_FRAMES) {
                cluster.noPoseLogged = true;
                PilotWatchLog.error("predict station_bind: ACCEPTED station row id="
                        + cluster.baseId + " variant=" + cluster.variantId + " has NO wire pose"
                        + " after " + cluster.poseWaitFrames + " fed frames — the server never"
                        + " fielded it (or its wire id differs); the base cannot adopt", null);
            }
            return; // not on the wire yet — retry next frame (the spawn-on-sight posture)
        }
        ShipAPI body = findUnclaimedStationBody(cluster, re);
        if (pose.hitpoints() <= 0f) {
            // (5) DEAD AT FIRST SIGHT. The plugin's own spawn-on-sight gate latches re.killed with
            // NO body — but here a body DOES exist (the engine minted it at combat init), so the
            // cluster must be neutralized and the base given the engine's real death. The one-shot
            // latches only after a SUCCESSFUL neutralize, so a failed attempt retries next frame.
            if (cluster.deadAtFirstSightDone || body == null) {
                return;
            }
            plugin.neutralizeRemoteBody(body, null); // no cap: this body never becomes a bound row
            for (ShipAPI child : childrenOf(body)) {
                plugin.neutralizeRemoteBody(child, null);
            }
            plugin.presenter.presentServerDeath(body, Float.NaN); // NaN = keep the local facing
            plugin.foeState.deployedEvict.evict(plugin.engine, body, "station-dead-at-first-sight"); //
            cluster.deadAtFirstSightDone = true;
            deadAtFirstSight++;
            PilotWatchLog.info("predict station_bind: base id=" + cluster.baseId + " DEAD AT FIRST"
                    + " SIGHT (wire hull=" + pose.hitpoints() + ") -> cluster neutralized and the"
                    + " local body given the server death; the entry stays unbound in the census");
            return;
        }
        if (body == null) {
            if (!cluster.noLocalBodyLogged && ++cluster.waitFrames >= MODULE_WAIT_FRAMES) {
                cluster.noLocalBodyLogged = true;
                PilotWatchLog.info("predict station_bind: base id=" + cluster.baseId + " is LIVE on"
                        + " the wire but no unclaimed local station body of variant "
                        + cluster.variantId + " exists after " + cluster.waitFrames + " frames"
                        + " -> spawn-on-sight fields it instead (adopt keeps retrying)");
            }
            return; // spawn-on-sight will mint one this same frame; adoptBaseFromEntry takes it
        }
        claimedBases.add(body);
        cluster.base = body;
        cluster.waitFrames = 0;
        re.ship = body;
        plugin.remoteUnbound--;
        basesAdopted++;
        plugin.rejoin.hardSnapToPose(body, pose); // first-sight kinematic adopt (the spawn-on-sight idiom)
        applyDeployCr(body);
        PilotWatchLog.info("predict station_bind: base id=" + cluster.baseId + " <- ADOPTED local"
                + " engine-minted station hull=" + hullOf(body) + " owner=" + body.getOwner()
                + " children=" + childCount(body) + " at (" + pose.locX() + "," + pose.locY() + ")"
                + " (adopts=" + basesAdopted + ", unbound=" + plugin.remoteUnbound + ")"
                + " cr=" + body.getCurrentCR());
    }

    /** Ascending birth order (engine list order) — the {@code adoptAndLink} claim discipline. */
    private ShipAPI findUnclaimedStationBody(Cluster cluster,
            PredictReconcileCombatPlugin.RemoteEntity re) {
        // Review fix: post-inversion, own-side and enemy-side station rows may
        // coexist — never claim a cross-side body by variant coincidence. (Owner read is safe:
        // the loop filters !isAlive() before this check, so no corpse-side poison.)
        int wantOwner = re.side == 0 ? plugin.sideAuthority() : 1 - plugin.sideAuthority();
        for (ShipAPI ship : plugin.engine.getShips()) {
            if (ship == null || !ship.isStation() || ship.isStationModule()
                    || claimedBases.contains(ship) || !ship.isAlive()) {
                continue;
            }
            if (ship == plugin.ownShip || ship == plugin.engine.getPlayerShip()) {
                continue; // never the seat (the flagship law)
            }
            if (ship.getOwner() != wantOwner) {
                continue; // cross-side claim refusal (the isCrossSpec precedent, side edition)
            }
            ShipVariantAPI variant = ship.getVariant();
            if (variant == null || !cluster.variantId.equals(variant.getHullVariantId())) {
                continue; // cross-spec claim refusal (the missile lane's isCrossSpec precedent)
            }
            return ship;
        }
        return null;
    }

    // ==================================================================================
    // (1) The ledgered neutralize
    // ==================================================================================

    /** Base + every current child through the ONE ledgered helper; a bound module carries its cap. */
    private void neutralizeCluster(Cluster cluster, PredictReconcileCombatPlugin.RemoteEntity re) {
        plugin.neutralizeRemoteBody(cluster.base, re);
        for (ShipAPI child : childrenOf(cluster.base)) {
            plugin.neutralizeRemoteBody(child, entryFor(child).entity);
        }
    }

    /** The registry row that owns this body, if any — the cap gate (never a cap without a row). */
    private RemoteEntityRef entryFor(ShipAPI ship) {
        for (PredictReconcileCombatPlugin.RemoteEntity candidate : plugin.remoteEntities.values()) {
            if (candidate.ship == ship) {
                return new RemoteEntityRef(candidate);
            }
        }
        return new RemoteEntityRef(null);
    }

    // ==================================================================================
    // Module band: assign → commit → bind/repair
    // ==================================================================================

    private void bindModules(Cluster cluster, PredictReconcileCombatPlugin.RemoteEntity re,
            ServerSnapshotFrame snapshot) {
        if (!ensureSlots(cluster)) {
            return; // ensureSlots latched already
        }
        List<ShipAPI> children = childrenOf(cluster.base);
        int declared = cluster.slots.size();
        if (children.size() > declared) {
            // (2) MORE children than the variant declares: the local cluster is not the cluster
            // the natural key was computed for. Latch — never guess.
            latch(cluster, "local children " + children.size() + " EXCEED the declared "
                    + declared + " module slots of " + cluster.variantId);
            return;
        }
        Map<String, Long> posed = assignWirePoses(cluster, snapshot);
        if (posed == null) {
            return; // assignWirePoses latched (natural-key violation)
        }
        if (!cluster.committed) {
            if (children.size() < declared || posed.isEmpty()) {
                // (2) BOUNDED WAIT — never latch, never commit on an incomplete picture. The
                // counter is the MODULE wait's own: adoptBaseFromWorld freezes waitFrames at the
                // cap when it logs its one-shot, so a shared counter could start this wait already
                // expired and >= (not ==) is what makes the verdict fire at all.
                cluster.moduleWaitFrames++;
                if (cluster.moduleWaitFrames >= MODULE_WAIT_FRAMES && !cluster.moduleWaitLogged) {
                    cluster.moduleWaitLogged = true;
                    if (posed.isEmpty() && cluster.appended && !children.isEmpty()) {
                        // The APPENDED band skips the
                        // descriptor's last-row assertion and trusts the server's contiguous
                        // root+1..root+N intern (PPICS.internWaveHull). Live local children with
                        // ZERO in-band poses after the wait = that premise is off for this mint —
                        // an ERROR, never a quiet no-bind.
                        PilotWatchLog.error("predict station_bind: base id=" + cluster.baseId
                                + " APPENDED band [" + cluster.bandLow + ".." + cluster.bandHigh
                                + "] matched 0 of " + declared + " declared slots after "
                                + cluster.moduleWaitFrames + " frames while " + children.size()
                                + " local children are live — the contiguous module-intern premise"
                                + " does not hold for this mint; the cluster stays inert",
                                null);
                        FallbackAnnounce.announceOnce("s6-appended-band-zero#" + cluster.baseId,
                                "appended station band matched 0 modules -> cluster inert");
                    } else if (posed.isEmpty()) {
                        PilotWatchLog.info("predict station_bind: base id=" + cluster.baseId
                                + " wait EXPIRED after " + cluster.moduleWaitFrames + " frames —"
                                + " the authority has no live modules (children=" + children.size()
                                + "/" + declared + ", posed=0); the cluster stays inert and"
                                + " uncommitted, and commits if modules ever appear");
                        // The fallback announce rides beside the latched one-shot info.
                        FallbackAnnounce.announceOnce("s6-module-wait-expired#" + cluster.baseId,
                                "module wait expired -> cluster inert/uncommitted");
                    } else {
                        latch(cluster, "CONTRACT BREAK: only " + children.size() + " of "
                                + declared + " declared modules exist locally after "
                                + cluster.moduleWaitFrames + " frames while the authority poses "
                                + posed.size() + " of them");
                    }
                }
                return;
            }
            cluster.committed = true;
            bandCommits++;
            int unclaimedSlots = declared - posed.size();
            PilotWatchLog.info("predict station_bind: base id=" + cluster.baseId + " module band"
                    + " COMMITTED children=" + children.size() + "/" + declared + " posed="
                    + posed.size() + " unclaimedSlots=" + unclaimedSlots + " tolerance="
                    + cluster.tolerance + " su (commits=" + bandCommits + ")");
            if (unclaimedSlots > 0) {
                // NOT a gate: posed < declared is the legitimate steady state when the authority's
                // own modules are already dead. It is also exactly what a broken slot table looks
                // like, so the band never commits green on a partial assignment again.
                PilotWatchLog.error("predict station_bind: base id=" + cluster.baseId + " committed"
                        + " with " + unclaimedSlots + " of " + declared + " declared slots UNPOSED"
                        + " — expected when those authority modules are already dead, a slot-table"
                        + " or tolerance defect otherwise (tolerance=" + cluster.tolerance + " su)",
                        null);
            }
        }
        for (Map.Entry<String, Long> assigned : posed.entrySet()) {
            bindOrRepairRow(cluster, re, assigned.getKey(), assigned.getValue().longValue(),
                    children);
            if (cluster.latched) {
                return;
            }
        }
    }

    /**
     * Assign every UNCLAIMED live wire pose to a declared slot, in the base's own rotating frame.
     * Returns slotId → wireId, or {@code null} after latching a natural-key violation (two wire
     * ids inside one slot's tolerance disc, which no faithful station can produce).
     */
    private Map<String, Long> assignWirePoses(Cluster cluster, ServerSnapshotFrame snapshot) {
        Map<String, Long> bySlot = new HashMap<String, Long>();
        ServerEntityPose basePose = snapshot.entityById(cluster.baseId);
        if (basePose == null || basePose.hitpoints() <= 0f) {
            return bySlot; // no authoritative anchor this frame — an empty assignment, not an error
        }
        double rad = Math.toRadians(-basePose.facing());
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        long ownId = plugin.session != null ? plugin.session.shipId() : -1L;
        for (ServerEntityPose pose : snapshot.entities()) {
            long wireId = pose.entityId();
            if (wireId == cluster.baseId || wireId == ownId || pose.hitpoints() <= 0f) {
                continue;
            }
            PredictReconcileCombatPlugin.RemoteEntity known =
                    plugin.remoteEntities.get(Long.valueOf(wireId));
            if (known != null && !cluster.slotByWireId.containsKey(Long.valueOf(wireId))) {
                continue; // a real roster row (descriptor or RAPP wave/growth), never a module
            }
            if (known == null && (wireId < cluster.bandLow || wireId > cluster.bandHigh)) {
                // (3) MODULE ID BAND. "No registry row yet" is NOT proof of modulehood: AWSS and
                // RAPP share one unordered datagram lane, so a station-born drone can be posed
                // frames before its append row drains. Assigning it would steal a slot PERMANENTLY
                // (the later append is swallowed by the registry dedupe) or latch the whole cluster
                // on a false natural-key collision. Only the band the server interns at t=0 for
                // THIS cluster may claim a slot; anything else waits for its own row.
                if (cluster.outOfBandLogged.add(Long.valueOf(wireId))) {
                    PilotWatchLog.info("predict station_bind: base id=" + cluster.baseId
                            + " SKIPPED unknown wire id " + wireId + " — outside the module id band"
                            + " [" + cluster.bandLow + ".." + cluster.bandHigh + "] (a station-born"
                            + " body whose roster append has not drained yet, not a module)");
                }
                continue;
            }
            float dx = pose.locX() - basePose.locX();
            float dy = pose.locY() - basePose.locY();
            double lx = dx * cos - dy * sin;
            double ly = dx * sin + dy * cos;
            Slot slot = nearestSlot(cluster, lx, ly);
            if (slot == null) {
                continue; // not on this station's cluster
            }
            Long prior = bySlot.put(slot.slotId, Long.valueOf(wireId));
            if (prior != null) {
                latch(cluster, "NATURAL-KEY VIOLATION: wire ids " + prior + " and " + wireId
                        + " both fall inside slot " + slot.slotId + "'s " + cluster.tolerance
                        + " su tolerance (base id=" + cluster.baseId + ")");
                return null;
            }
        }
        return bySlot;
    }

    /** The nearest declared slot within tolerance — at most one can qualify by construction. */
    private Slot nearestSlot(Cluster cluster, double lx, double ly) {
        Slot best = null;
        double bestSq = Double.MAX_VALUE;
        double tolSq = (double) cluster.tolerance * (double) cluster.tolerance;
        for (Slot slot : cluster.slots) {
            double ddx = lx - slot.x;
            double ddy = ly - slot.y;
            double sq = ddx * ddx + ddy * ddy;
            if (sq < bestSq) {
                bestSq = sq;
                best = slot;
            }
        }
        return best != null && bestSq <= tolSq ? best : null;
    }

    /**
     * One slot's row: mint it on first assignment, or REPAIR it when a kill/respawn unbound it.
     *
     * <p>(4) A repair re-verifies the natural key against the CURRENT children — never an ordinal
     * into the children list, which is not index-stable across a module detach. Exactly one live,
     * unclaimed, declared-variant child on the slot binds; anything else kills the row (and gives
     * back the {@code remoteUnbound} count {@code respawnRemote} added, so the census can still
     * reach COMPLETE).
     */
    private void bindOrRepairRow(Cluster cluster, PredictReconcileCombatPlugin.RemoteEntity re,
            String slotId, long wireId, List<ShipAPI> children) {
        Long prior = cluster.wireIdBySlot.get(slotId);
        if (prior != null && prior.longValue() != wireId) {
            latch(cluster, "NATURAL-KEY VIOLATION: slot " + slotId + " was bound to wire id "
                    + prior + " and the authority now poses id " + wireId + " on it");
            return;
        }
        PredictReconcileCombatPlugin.RemoteEntity row =
                plugin.remoteEntities.get(Long.valueOf(wireId));
        if (row != null && !row.stationModule) {
            latch(cluster, "NATURAL-KEY VIOLATION: wire id " + wireId + " sits on slot " + slotId
                    + " but already owns a non-module registry row");
            return;
        }
        String declaredVariant = declaredVariantFor(cluster, slotId);
        if (row == null) {
            ShipAPI child = childForSlot(cluster, children, slotId, declaredVariant);
            if (child == null) {
                if (cluster.childMissLogged.add(slotId)) {
                    if (deadChildOnSlot(cluster, children, slotId)) {
                        // The engine never re-creates a station child, so a module killed locally
                        // inside the uncapped pre-bind window can never bind this life while the
                        // authority keeps posing it: a real DIVERGENCE, not a "not yet".
                        PilotWatchLog.error("predict station_bind: base id=" + cluster.baseId
                                + " slot " + slotId + " (wire id " + wireId + ") carries a locally"
                                + " DEAD module while the authority still poses it — the cluster"
                                + " DIVERGED in the uncapped pre-bind window; the engine never"
                                + " re-creates a station child, so this slot cannot bind this life",
                                null);
                    } else {
                        PilotWatchLog.info("predict station_bind: base id=" + cluster.baseId
                                + " slot " + slotId + " (wire id " + wireId + ") has NO unique live"
                                + " child of variant " + declaredVariant
                                + " -> row deferred (retrying)");
                    }
                }
                return;
            }
            row = plugin.newStationModuleEntity(wireId, re.side);
            row.ship = child;
            plugin.remoteEntities.put(Long.valueOf(wireId), row);
            plugin.variantById.put(Long.valueOf(wireId), declaredVariant);
            cluster.wireIdBySlot.put(slotId, Long.valueOf(wireId));
            cluster.slotByWireId.put(Long.valueOf(wireId), slotId);
            cluster.boundChildren.add(child);
            moduleRowsBound++;
            // The cap installs HERE and only here: one ledgered treatment per body, and a cap is
            // only ever tied to a registry row that killRemote can clear (axis C).
            plugin.neutralizeRemoteBody(child, row);
            PilotWatchLog.info("predict station_bind: module id=" + wireId + " <- slot " + slotId
                    + " variant=" + declaredVariant + " hull=" + hullOf(child) + " (base id="
                    + cluster.baseId + ", bound=" + moduleRowsBound + ")");
            return;
        }
        if (row.ship == null && !row.killed) {
            ShipAPI child = childForSlot(cluster, children, slotId, declaredVariant);
            if (child == null) {
                row.killed = true;
                plugin.remoteUnbound--;
                moduleRowsKilled++;
                PilotWatchLog.info("predict station_bind: module id=" + wireId + " REPAIR FAILED on"
                        + " slot " + slotId + " (no unique live child of variant " + declaredVariant
                        + ") -> row killed (base id=" + cluster.baseId + ", killed="
                        + moduleRowsKilled + ")");
                return;
            }
            row.ship = child;
            plugin.remoteUnbound--;
            cluster.boundChildren.add(child);
            moduleRowsRepaired++;
            plugin.neutralizeRemoteBody(child, row);
            PilotWatchLog.info("predict station_bind: module id=" + wireId + " REPAIRED onto slot "
                    + slotId + " hull=" + hullOf(child) + " (base id=" + cluster.baseId
                    + ", repairs=" + moduleRowsRepaired + ")");
        }
    }

    /** Exactly-one live, unclaimed child on {@code slotId} whose variant matches the declaration. */
    private ShipAPI childForSlot(Cluster cluster, List<ShipAPI> children, String slotId,
            String declaredVariant) {
        ShipAPI match = null;
        int matches = 0;
        for (ShipAPI child : children) {
            if (child == null || !child.isAlive() || cluster.boundChildren.contains(child)) {
                continue;
            }
            if (!slotId.equals(slotIdOf(cluster, child))) {
                continue;
            }
            ShipVariantAPI variant = child.getVariant();
            String variantId = variant == null ? null : variant.getHullVariantId();
            if (declaredVariant != null && !declaredVariant.equals(variantId)) {
                continue; // the spec half of the natural key (addToFleet CLONES the variant, so
                          // identity never matches — compare the variant ID string)
            }
            match = child;
            matches++;
        }
        return matches == 1 ? match : null;
    }

    /** Is a body parked on {@code slotId} but locally DEAD? The pre-bind divergence signal. */
    private boolean deadChildOnSlot(Cluster cluster, List<ShipAPI> children, String slotId) {
        for (ShipAPI child : children) {
            if (child != null && !child.isAlive() && slotId.equals(slotIdOf(cluster, child))) {
                return true;
            }
        }
        return false;
    }

    /** A local child's slot: the engine's own {@code getStationSlot} id, else the geometry twin. */
    private String slotIdOf(Cluster cluster, ShipAPI child) {
        WeaponSlotAPI slot = child.getStationSlot();
        if (slot != null && slot.getId() != null) {
            return slot.getId();
        }
        ShipAPI base = cluster.base;
        if (base == null || base.getLocation() == null || child.getLocation() == null) {
            return null;
        }
        double rad = Math.toRadians(-base.getFacing());
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        float dx = child.getLocation().x - base.getLocation().x;
        float dy = child.getLocation().y - base.getLocation().y;
        Slot nearest = nearestSlot(cluster, dx * cos - dy * sin, dx * sin + dy * cos);
        return nearest == null ? null : nearest.slotId;
    }

    private String declaredVariantFor(Cluster cluster, String slotId) {
        for (Slot slot : cluster.slots) {
            if (slot.slotId.equals(slotId)) {
                return slot.variantId;
            }
        }
        return null;
    }

    // ==================================================================================
    // W2b module-mount un-adopt / re-adopt (ModuleMountLane's seam)
    // ==================================================================================

    /** {@link #unadoptForMount}'s receipt: the module row surgically removed from the band. */
    static final class UnadoptedModule {
        final long wireId;
        final PredictReconcileCombatPlugin.RemoteEntity row;
        final ShipAPI body;
        final String variantId;
        final int side;

        UnadoptedModule(long wireId, PredictReconcileCombatPlugin.RemoteEntity row,
                ShipAPI body, String variantId, int side) {
            this.wireId = wireId;
            this.row = row;
            this.body = body;
            this.variantId = variantId;
            this.side = side;
        }
    }

    /**
     * MOUNT half — resolve a seat-claimed module by its slot token and
     * surgically un-bind it from the band: cluster maps + registry row + variant row out;
     * {@code boundChildren} keeps the body CLAIMED (no natural-key pass may re-match a piloted
     * module). Body-level surgery (cap/AI/replay-driven/player pointer) is the caller's — this
     * method owns exactly the cluster/registry bookkeeping it minted at {@code bindOrRepairRow}.
     * Returns {@code null} while unresolvable (no committed cluster binds the token yet — the
     * admission-replay retry case) or when the bound body is dead (the mount raced a kill; the
     * lane's death-clear owns that path).
     */
    UnadoptedModule unadoptForMount(String slotToken) {
        for (Cluster cluster : clusters.values()) {
            Long wid = cluster.wireIdBySlot.get(slotToken);
            if (wid == null) {
                continue;
            }
            PredictReconcileCombatPlugin.RemoteEntity row = plugin.remoteEntities.get(wid);
            if (row == null || !row.stationModule || row.ship == null || !row.ship.isAlive()) {
                return null; // dead/broken slot — never mount a corpse; retry until death-clear
            }
            cluster.wireIdBySlot.remove(slotToken);
            cluster.slotByWireId.remove(wid);
            plugin.remoteEntities.remove(wid);
            String variantId = plugin.variantById.remove(wid);
            return new UnadoptedModule(wid.longValue(), row, row.ship, variantId, row.side);
        }
        return null;
    }

    /**
     * DISMOUNT half — re-bind the cluster row DIRECTLY under the module's
     * fresh above-band id (the fresh id falls outside the band gate, so {@code assignWirePoses}
     * can never re-claim it) and mint the fresh registry row. The caller re-ties the cap via the
     * {@code forgetRemoteBodyTreatment} + {@code neutralizeRemoteBody(body, freshRow)} idiom —
     * never here (one concern: this method owns maps and rows, never listeners). Fail-soft when
     * no cluster declares the slot (band dropped mid-round): the registry row still mints so the
     * fresh id's wire feed has a home; the cluster maps just cannot re-bind.
     */
    PredictReconcileCombatPlugin.RemoteEntity readoptAfterDismount(long freshId,
            String slotToken, ShipAPI body, String variantId, int side) {
        PredictReconcileCombatPlugin.RemoteEntity freshRow =
                plugin.newStationModuleEntity(freshId, side);
        freshRow.ship = body;
        plugin.remoteEntities.put(Long.valueOf(freshId), freshRow);
        if (variantId != null) {
            plugin.variantById.put(Long.valueOf(freshId), variantId);
        }
        for (Cluster cluster : clusters.values()) {
            if (declaredVariantFor(cluster, slotToken) == null
                    || !cluster.boundChildren.contains(body)) {
                continue;
            }
            Long prior = cluster.wireIdBySlot.get(slotToken);
            if (prior != null) {
                PilotWatchLog.error("predict station_bind: dismount re-adopt found slot "
                        + slotToken + " already bound to wire id " + prior
                        + " — cluster maps left untouched (fresh row " + freshId
                        + " still minted)", null);
                return freshRow;
            }
            cluster.wireIdBySlot.put(slotToken, Long.valueOf(freshId));
            cluster.slotByWireId.put(Long.valueOf(freshId), slotToken);
            PilotWatchLog.info("predict station_bind: module id=" + freshId + " RE-ADOPTED onto"
                    + " slot " + slotToken + " after dismount (base id=" + cluster.baseId + ")");
            return freshRow;
        }
        PilotWatchLog.error("predict station_bind: dismount re-adopt found NO cluster declaring"
                + " slot " + slotToken + " — fresh row " + freshId + " minted row-only", null);
        return freshRow;
    }

    /**
     * A DISMOUNT that reaches {@link ModuleMountLane} with NO
     * mounted record (the mount never resolved here: parked while a COMMIT stole the adopt;
     * or a death-clear/round race) still carries the truth that matters: slot
     * {@code slotToken} now streams under {@code freshId}, an out-of-band id the band gate will
     * never assign ({@code SKIPPED unknown wire id 91}) — without a re-key the slot's body
     * is orphaned all round. Re-key it from the census: the prior slot binding (any id, bound or
     * not) is surgically dropped, a NON-module row still holding the body (the stolen adopt) is
     * released hull-less through {@code respawnRemote} (the corpse lane keeps a live module),
     * and the body — the prior row's, else the base's live child on the slot —
     * re-binds under {@code freshId} via {@link #readoptAfterDismount} with its cap re-tied.
     * False (ERROR once) when no cluster declares the slot or no live body sits on it.
     */
    boolean readoptOrphanAfterDismount(long freshId, String slotToken) {
        if (freshId <= 0L || slotToken == null) {
            return false;
        }
        for (Cluster cluster : clusters.values()) {
            String declared = declaredVariantFor(cluster, slotToken);
            if (declared == null) {
                continue;
            }
            Long prior = cluster.wireIdBySlot.get(slotToken);
            if (prior != null && prior.longValue() == freshId) {
                return true; // already keyed fresh — nothing to heal
            }
            ShipAPI body = null;
            if (prior != null) {
                cluster.wireIdBySlot.remove(slotToken);
                cluster.slotByWireId.remove(prior);
                plugin.variantById.remove(prior);
                PredictReconcileCombatPlugin.RemoteEntity priorRow =
                        plugin.remoteEntities.remove(prior);
                if (priorRow != null) {
                    body = priorRow.ship;
                    if (priorRow.ship == null && !priorRow.killed) {
                        plugin.remoteUnbound = Math.max(0, plugin.remoteUnbound - 1);
                    }
                    priorRow.capActive = false;
                    priorRow.ship = null;
                }
            }
            if (body == null || !body.isAlive()) {
                body = null;
                List<ShipAPI> children = cluster.base == null ? null
                        : cluster.base.getChildModulesCopy();
                if (children != null) {
                    for (ShipAPI child : children) {
                        if (child != null && child.isAlive()
                                && slotToken.equals(slotIdOf(cluster, child))) {
                            body = child;
                            break;
                        }
                    }
                }
            }
            if (body == null) {
                PilotWatchLog.error("predict station_bind: orphan re-adopt of slot " + slotToken
                        + " (fresh id " + freshId + ") found NO live body on the slot — stays"
                        + " unbound this round (base id=" + cluster.baseId + ")", null);
                return false;
            }
            for (PredictReconcileCombatPlugin.RemoteEntity re
                    : new java.util.ArrayList<PredictReconcileCombatPlugin.RemoteEntity>(
                            plugin.remoteEntities.values())) {
                if (re.ship == body && !re.stationModule) {
                    PilotWatchLog.info("predict station_bind: orphan re-adopt — row "
                            + re.entityId + " held the slot " + slotToken
                            + " body (stolen adopt); released hull-less, module kept");
                    plugin.respawnRemote(re);
                }
            }
            PredictReconcileCombatPlugin.RemoteEntity baseRow =
                    plugin.remoteEntities.get(Long.valueOf(cluster.baseId));
            int side = baseRow != null ? baseRow.side : body.getOwner();
            cluster.boundChildren.add(body);
            PredictReconcileCombatPlugin.RemoteEntity freshRow =
                    readoptAfterDismount(freshId, slotToken, body, declared, side);
            freshRow.rekeyPoseHold = true;
            freshRow.rekeyHoldSinceTick = Long.MIN_VALUE;
            plugin.forgetRemoteBodyTreatment(body);
            plugin.neutralizeRemoteBody(body, freshRow);
            PilotWatchLog.info("predict station_bind: orphan re-adopt HEALED slot " + slotToken
                    + " -> fresh id " + freshId + " (prior id " + prior + ", hull=" + hullOf(body)
                    + ", base id=" + cluster.baseId + ")");
            return true;
        }
        PilotWatchLog.error("predict station_bind: orphan re-adopt found NO cluster declaring"
                + " slot " + slotToken + " (fresh id " + freshId + ")", null);
        return false;
    }

    // ==================================================================================
    // Declared slot table
    // ==================================================================================

    /**
     * Resolve the declared module slots ONCE per cluster from the base's own variant
     * ({@code getStationModules} slot id → module variant id) plus the derived tolerance and the
     * cluster's module id band. A missing slot, an unresolvable module variant or an empty module
     * map is a content boundary, not an empty cluster: latch.
     *
     * <p><b>The position is the module CENTER, not the weapon-slot node.</b>
     * {@code CombatEngine.updateStationModuleLocations} parks every child at
     * {@code slot.computePosition(base, moduleOffset)}, and that overload
     * rotates the offset by the SLOT's angle, NEGATES
     * it, adds the slot node, and only then rotates the sum by the base facing — while
     * {@code moduleOffset} is the MODULE hull spec's own {@code moduleAnchor}.
     * So the hull-local center the authority poses is
     * {@code node − R(slotAngle)·moduleAnchor}, which on station3 is 19-66 su out from the node
     * along the slot's facing: against the raw-node table 11 of 24 wire poses fell outside every
     * disc and WS 019 landed inside WS 008's (40.986 su against a 40.994 su tolerance). Anchors
     * are per module HULL, so the tolerance must be re-derived from the corrected centers too
     * (station3_Standard: min pairwise 76.909 su, WS 012↔024 / WS 014↔025 ⇒ 38.45 su).
     */
    private boolean ensureSlots(Cluster cluster) {
        if (cluster.slots != null) {
            return true;
        }
        ShipVariantAPI variant = cluster.base.getVariant();
        if (variant == null) {
            latch(cluster, "the local station body carries no variant");
            return false;
        }
        Map<String, String> modules = variant.getStationModules();
        if (modules == null || modules.isEmpty()) {
            latch(cluster, "variant " + cluster.variantId + " declares NO station modules (the"
                    + " content did not load — refusing an under-counted cluster)");
            return false;
        }
        ShipHullSpecAPI hull = variant.getHullSpec();
        List<Slot> slots = new ArrayList<Slot>(modules.size());
        int skippedNonModuleSlots = 0;
        for (Map.Entry<String, String> entry : modules.entrySet()) {
            String slotId = entry.getKey();
            WeaponSlotAPI ws = hull == null ? null : hull.getWeaponSlotAPI(slotId);
            if (ws == null || ws.getLocation() == null) {
                latch(cluster, "declared module slot '" + slotId + "' has no weapon-slot location"
                        + " on hull " + (hull == null ? "?" : hull.getHullId()));
                return false;
            }
            if (!ws.isStationModule()) {
                // deployStationAndRelatedIfNeeded only mints a child for a STATION_MODULE slot, so
                // counting a mistyped declaration would hold children < declared FOREVER (the
                // bounded wait would expire into a false contract-break latch every round).
                skippedNonModuleSlots++;
                continue;
            }
            ShipVariantAPI moduleVariant = moduleVariantFor(variant, slotId, entry.getValue());
            if (moduleVariant == null || moduleVariant.getHullSpec() == null) {
                latch(cluster, "declared module slot '" + slotId + "' names module variant '"
                        + entry.getValue() + "' which does not resolve to a hull spec — the module"
                        + " anchor is unreadable, and binding against an UNCORRECTED slot table"
                        + " misassigns wire ids (fail-closed)");
                return false;
            }
            // The engine's Ship guards the null anchor, leaving moduleOffset at (0,0) — so a hull with
            // no moduleAnchor legitimately centers ON its node; only an unreadable VARIANT latches.
            Vector2f anchor = moduleVariant.getHullSpec().getModuleAnchor();
            float ax = anchor == null ? 0f : anchor.x;
            float ay = anchor == null ? 0f : anchor.y;
            Vector2f loc = ws.getLocation();
            double rad = Math.toRadians(ws.getAngle());
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            double cx = loc.x - (ax * cos - ay * sin);
            double cy = loc.y - (ax * sin + ay * cos);
            slots.add(new Slot(slotId, entry.getValue(), (float) cx, (float) cy));
        }
        if (slots.isEmpty()) {
            latch(cluster, "variant " + cluster.variantId + " declares " + modules.size()
                    + " module slots but NONE of them is a STATION_MODULE weapon slot");
            return false;
        }
        cluster.slots = slots;
        cluster.tolerance = toleranceFor(slots);
        // (3) The module id band: the server interns this cluster's module ids first-seen at t=0
        // directly after the dense descriptor contract, and the station row is the LAST roster row
        // (so baseId == N). A shape that ever breaks that premise fails CLOSED — every module id
        // lands out of band and is skipped, loudly, instead of binding against a guessed band.
        // An APPENDED base's modules are interned at its mint, contiguously after
        // the root (PPICS.internWaveHull) — the same root+1..root+N band, no descriptor premise.
        cluster.bandLow = cluster.baseId + 1L;
        cluster.bandHigh = cluster.baseId + slots.size();
        PilotWatchLog.info("predict station_bind: base id=" + cluster.baseId + " declared slots="
                + slots.size() + " variant=" + cluster.variantId + " tolerance="
                + cluster.tolerance + " su (0.5 x min pairwise MODULE-CENTER separation) moduleIdBand=["
                + cluster.bandLow + ".." + cluster.bandHigh + "]"
                + (skippedNonModuleSlots > 0 ? " skippedNonModuleSlots=" + skippedNonModuleSlots : ""));
        if (!cluster.appended && cluster.baseId != maxDescriptorId) {
            PilotWatchLog.error("predict station_bind: base id=" + cluster.baseId + " is NOT the"
                    + " last descriptor row (max id=" + maxDescriptorId + ") — the module id band"
                    + " premise does not hold for this shape, so every module id will fall out of"
                    + " band and be SKIPPED (fail-closed; the cluster stays inert and unbound)",
                    null);
        }
        if (slots.size() < 2) {
            PilotWatchLog.error("predict station_bind: base id=" + cluster.baseId + " declares only"
                    + " " + slots.size() + " module slot — there is no pairwise separation to"
                    + " derive a disc from, so the tolerance is UNBOUNDED and the single slot"
                    + " claims any in-band wire pose (the id band is the whole guard here)", null);
        }
        return true;
    }

    /**
     * The module variant declared at {@code slotId}: the base variant's own resolved module first
     * ({@code getModuleVariant}), then the settings spec store by id (the
     * {@link MatchMissionBuilder#isStationVariant} idiom). {@code null} means "unresolvable", which
     * is a latch — never a silently zero anchor.
     */
    private static ShipVariantAPI moduleVariantFor(ShipVariantAPI base, String slotId,
            String variantId) {
        try {
            ShipVariantAPI resolved = base.getModuleVariant(slotId);
            if (resolved != null) {
                return resolved;
            }
        } catch (Throwable ignore) {
            // fall through to the spec store
        }
        try {
            return variantId == null ? null : Global.getSettings().getVariant(variantId);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * Half the minimum pairwise MODULE-CENTER separation — the disc radius inside which AT MOST
     * ONE declared slot can sit, which is what makes the nearest-slot assignment unambiguous. On
     * station3_Standard the corrected centers give 76.909 su (WS 012↔024 and WS 014↔025, a tie)
     * ⇒ 38.45 su, every module's own pose sits exactly ON its center (0 su in, the full radius of
     * margin) and no other center is nearer than 76.909 su, so no pose can reach a second disc.
     * The closest same-variant ALIAS pair sits 136.6 su apart (WS 021↔022), 3.5x the disc.
     *
     * <p>Below two slots there is NO pairwise separation to halve. Returning a small number there
     * would silently disable binding for such a hull (nothing ever lands in a 1 su disc), so the
     * fallback is UNBOUNDED — with one declared slot no assignment can be ambiguous by
     * construction, and {@link #assignWirePoses}' module id band is what keeps a foreign body out.
     * {@link #ensureSlots} says so loudly when it happens.
     */
    private static float toleranceFor(List<Slot> slots) {
        double minSq = Double.MAX_VALUE;
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                double dx = slots.get(i).x - slots.get(j).x;
                double dy = slots.get(i).y - slots.get(j).y;
                double sq = dx * dx + dy * dy;
                if (sq < minSq) {
                    minSq = sq;
                }
            }
        }
        return minSq == Double.MAX_VALUE ? Float.MAX_VALUE : (float) (Math.sqrt(minSq) * 0.5d);
    }

    // ==================================================================================
    // Latch / band drop / round reset
    // ==================================================================================

    /** (2)/(6) A contract break: stop binding this cluster, stay inert, say it once and loudly. */
    private void latch(Cluster cluster, String why) {
        if (cluster.latched) {
            return;
        }
        cluster.latched = true;
        latches++;
        PilotWatchLog.error("predict station_bind: LATCHED base id=" + cluster.baseId + " — " + why
                + " -> the cluster stops binding and stays neutralized (inert AI + hold-fire +"
                + " break-prob suppression); no module rows are minted from here on", null);
    }

    /**
     * (3) Drop the whole band: the wire ids were claimed against a base that is gone. Rows that
     * were counted UNBOUND ({@code ship == null && !killed}, i.e. a respawnRemote re-arm) give
     * their count back; a killed row already gave its count back when it was killed, and a bound
     * row was never counted.
     */
    private void dropBand(Cluster cluster, String why) {
        int dropped = 0;
        int givenBack = 0;
        for (Long wireId : new ArrayList<Long>(cluster.slotByWireId.keySet())) {
            PredictReconcileCombatPlugin.RemoteEntity row = plugin.remoteEntities.get(wireId);
            if (row != null && row.stationModule) {
                if (row.ship == null && !row.killed) {
                    plugin.remoteUnbound--;
                    givenBack++;
                }
                // The dropped row's body may still carry the RemoteDamageCap this band installed,
                // and no killRemote will ever reach the row again: neuter the cap (an uncleared cap
                // clamps every lethal hit — a locally IMMORTAL body) and forget the treatment
                // ledger entry, so a fresh band can install exactly ONE new cap on the same body.
                row.capActive = false;
                plugin.forgetRemoteBodyTreatment(row.ship);
                plugin.remoteEntities.remove(wireId);
                plugin.variantById.remove(wireId);
                dropped++;
            }
        }
        if (cluster.base != null) {
            claimedBases.remove(cluster.base);
        }
        cluster.base = null;
        cluster.slots = null;
        cluster.bandLow = Long.MAX_VALUE;
        cluster.bandHigh = Long.MIN_VALUE;
        cluster.committed = false;
        cluster.waitFrames = 0;
        cluster.poseWaitFrames = 0;
        cluster.noPoseLogged = false;
        cluster.moduleWaitFrames = 0;
        cluster.moduleWaitLogged = false;
        cluster.wireIdBySlot.clear();
        cluster.slotByWireId.clear();
        cluster.boundChildren.clear();
        cluster.childMissLogged.clear();
        cluster.outOfBandLogged.clear();
        cluster.deadAtFirstSightDone = false;
        cluster.noLocalBodyLogged = false;
        cluster.corpseSkipLogged = false;
        bandDrops++;
        PilotWatchLog.info("predict station_bind: band DROPPED base id=" + cluster.baseId + " — "
                + why + " (rows removed=" + dropped + " unbound given back=" + givenBack
                + ", drops=" + bandDrops + ") -> re-adopt from the fresh base");
    }

    /**
     * The MRST round boundary. The descriptor row SURVIVES a reset (spawn-on-sight re-fields the
     * base from the fresh round's wire), but the module id band and its {@code variantById} rows
     * are ROUND-LOCAL — the server re-interns wire ids from 1. Called beside the RAPP sweep and
     * BEFORE the {@code remoteUnbound} recount, so the recount sees a registry with no stale
     * module rows in it.
     */
    void resetRound() {
        if (acceptedRows.isEmpty() && clusters.isEmpty()) {
            return;
        }
        PilotWatchLog.info("predict station_bind match-reset totals: rows=" + acceptedRows.size()
                + " adopts=" + basesAdopted + " commits=" + bandCommits + " drops=" + bandDrops
                + " moduleRowsBound=" + moduleRowsBound + " repairs=" + moduleRowsRepaired
                + " moduleRowsKilled=" + moduleRowsKilled + " latches=" + latches
                + " deadAtFirstSight=" + deadAtFirstSight + " inertSweeps=" + inertSweeps
                + (refused ? " REFUSED(" + refusedWhy + ")" : ""));
        for (Cluster cluster : clusters.values()) {
            dropBand(cluster, "match reset");
            cluster.latched = false; // a fresh round re-earns its own verdict
        }
        clusters.clear();
        claimedBases.clear();
        refusedSweepLogged = false;
        // Appended rows are round-local like the registry entries they mirror
        // (the server re-interns from 1; a fresh round's /spawnfoe re-announces and re-accepts).
        if (!appendedRows.isEmpty()) {
            acceptedRows.keySet().removeAll(appendedRows);
            appendedRows.clear();
        }
    }

    // ==================================================================================
    // Small helpers + state
    // ==================================================================================

    private List<ShipAPI> childrenOf(ShipAPI base) {
        List<ShipAPI> children = base == null ? null : base.getChildModulesCopy();
        return children == null ? Collections.<ShipAPI>emptyList() : children;
    }

    /**
     * CR parity: both adopt seams take bodies that never saw the
     * descriptor's deployCr (engine mission mint / spawn-on-sight base = engine default 0.7,
     * station base 0.5 — while the server deploys every body at descriptor CR), and module
     * CHILDREN are separate client bodies the server's "modules copy member CR" pre-spawn set
     * has no client twin for. The stamp goes through the engine seam — a bare field stamp never
     * applied cr_effect (see {@link CrDeploySeam}).
     */
    private void applyDeployCr(ShipAPI base) {
        CrDeploySeam.applyDeployCr(base, plugin.descriptorDeployCr);
        for (ShipAPI child : childrenOf(base)) {
            CrDeploySeam.applyDeployCr(child, plugin.descriptorDeployCr);
        }
    }

    private int childCount(ShipAPI base) {
        return childrenOf(base).size();
    }

    private static String hullOf(ShipAPI ship) {
        try {
            return ship.getHullSpec() == null ? "?" : ship.getHullSpec().getHullId();
        } catch (Throwable ignore) {
            return "?";
        }
    }

    /** A nullable entry carrier — {@code null} means "no registry row", i.e. NO cap (item 1). */
    private static final class RemoteEntityRef {
        final PredictReconcileCombatPlugin.RemoteEntity entity;

        RemoteEntityRef(PredictReconcileCombatPlugin.RemoteEntity entity) {
            this.entity = entity;
        }
    }

    /** One declared module slot: the natural key's two halves plus its hull-local position. */
    private static final class Slot {
        final String slotId;
        final String variantId;
        final float x;
        final float y;

        Slot(String slotId, String variantId, float x, float y) {
            this.slotId = slotId;
            this.variantId = variantId;
            this.x = x;
            this.y = y;
        }
    }

    /** Per-base bind state. Everything here is ROUND-LOCAL ({@link #resetRound} clears it). */
    private static final class Cluster {
        final long baseId;
        final String variantId;
        /** A wire-appended base (no descriptor band premise to check). */
        final boolean appended;
        ShipAPI base;
        List<Slot> slots;
        float tolerance;
        /** The inclusive module id band (ensureSlots); empty until the slot table resolves. */
        long bandLow = Long.MAX_VALUE;
        long bandHigh = Long.MIN_VALUE;
        boolean committed;
        boolean latched;
        boolean deadAtFirstSightDone;
        boolean noLocalBodyLogged;
        /** One-shot for the no-WIRE-pose twin (the D1-companion ERROR). */
        boolean noPoseLogged;
        boolean corpseSkipLogged;
        boolean moduleWaitLogged;
        int waitFrames;
        /** Fed frames with NO wire pose for the base id (see {@code noPoseLogged}). */
        int poseWaitFrames;
        /** The MODULE wait's own counter — {@code waitFrames} is the base-adopt wait's (it freezes). */
        int moduleWaitFrames;
        final Map<String, Long> wireIdBySlot = new HashMap<String, Long>();
        final Map<Long, String> slotByWireId = new HashMap<Long, String>();
        final Set<ShipAPI> boundChildren =
                Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
        final Set<String> childMissLogged = new java.util.HashSet<String>();
        /** Unknown out-of-band wire ids already logged (drones repose every frame — throttle). */
        final Set<Long> outOfBandLogged = new java.util.HashSet<Long>();

        Cluster(long baseId, String variantId, boolean appended) {
            this.baseId = baseId;
            this.variantId = variantId;
            this.appended = appended;
        }
    }

    // RESIDUAL CLOSED: spawnRemoteEntities
    // mints a station base and its 24 modules AFTER this lane's drive in the frame, and used to
    // treat only the BASE at the spawn site — one engine tick of vanilla module AI before the
    // next frame's sweep. The own-side DEFEND fortress now rides that spawn path on EVERY
    // launch (not just the post-MRST re-form), so the spawn site now neutralizes the children in
    // the same call (PRCP spawn loop). sweepInert stays the fail-closed net behind it.
}
