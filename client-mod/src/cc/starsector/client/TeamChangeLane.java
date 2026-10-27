package cc.starsector.client;

import cc.starsector.client.net.ServerEntityPose;
import cc.starsector.client.net.ServerSnapshotFrame;
import com.fs.starfarer.api.combat.ShipAPI;
import java.util.ArrayList;
import java.util.List;

/**
 * For {@code /switchteams}: the per-frame team-PERSPECTIVE pass, run by
 * {@link PredictReconcileCombatPlugin} BEFORE each frame's RAPP fold / station adopt /
 * spawn-on-sight so every registry side those read is already live.
 * <ol>
 * <li>The OWN team = the own row's wire {@code fleetSide} (server-settable; the
 * descriptor only seeds {@code ownDescSide} at init) — a change re-keys it.</li>
 * <li>EVERY remote row's relative side is re-derived from ITS live {@code fleetSide} against the
 * own team ({@link TeamPerspectiveLaw#relSide}), and a bound live body whose side moved is
 * re-owned in place ({@link RemoteTeamFlip} — the server's proven flip checklist over the public
 * API).</li>
 * </ol>
 * The whole sweep lands in ONE frame, so the engine never sees a half-flipped field. One pass
 * serves all four cases: the own seat's {@code instant} flip, its {@code respawn} (the fresh own
 * row lands on the other side the same frame {@code respawnOwn} fields it — this runs first),
 * another client's switched seat, and a recorded reel (the observer runs the same plugin).
 *
 * <p>Frame residency: the steady (no-switch) state costs ONE primitive walk of
 * {@code snapshot.entities()} that stamps a side signature (own team + every combat row's
 * {@code id x fleetSide}); the boxing + registry-lookup re-side walk runs ONLY when that signature
 * moves or a bounded re-own retry is due — no engine read unless a side
 * actually moved. Fail-soft: a re-owner throw logs and the registry side still flips (the body
 * keeps its old owner, visible in the log) — never a render-thread throw; a still-mis-owned body is
 * re-attempted every ~{@code REOWN_RETRY_TICKS} ticks (a mis-owned body is a standing desync, never
 * silently abandoned). {@code sideAuthority()} is untouched: the own hull is local owner 0 always.
 *
 */
final class TeamChangeLane {

    private final PredictReconcileCombatPlugin plugin;
    /** Every live body {@link RemoteTeamFlip} re-owned this session. The
     * public API can EVICT a hull from the old side's deployed set but never re-add it to the new
     * one (the server's join, {@code SeatTeamEnginePort.joinNewSideDeployedSet}, is an engine-internal
     * registration — B-denied here), so a flipped replica sits in NEITHER local set: vanilla's
     * side scripts skip it and so would {@link SideTotalsOverrideLane}'s per-ship walks — the
     * flight's seat-1 view of the switched Tempest kept the OLD side's coord bonus.
     * The lane walks this list beside each manager's deployed copy (owner-filtered there). A flipped
     * station's child modules are added here too: {@link RemoteTeamFlip} re-owns
     * them via its part walk, but a module is evicted from the old side's deployed set and never
     * re-added, so — exactly like the base hull — it reaches {@link SideTotalsOverrideLane}'s
     * coord/EW per-ship walk ONLY through this list. Pruned each pass: dead or out-of-play bodies
     * leave (a respawned seat is a NEW body that deploys through the manager like any spawn). Empty
     * in every session that never saw a switch. */
    private final List<ShipAPI> flippedBodies = new ArrayList<ShipAPI>();

    /** Bounded re-owner retry cadence in sim ticks: a bound body whose flip
     *  threw / did not take is re-attempted at most this often while it stays mis-owned — never per
     *  frame, never abandoned (a mis-owned body is a standing desync). */
    private static final long REOWN_RETRY_TICKS = 60L; //
    /** Last frame's side signature (own team + every combat row's {@code id x fleetSide}) — a cheap
     *  version stamp; unchanged AND no retry due ⇒ skip the boxing + registry-lookup re-side walk. */
    private long lastSideSig = Long.MIN_VALUE; //
    private int lastCombatCount = -1; //
    /** Bound bodies still mis-owned after the last pass (the bounded-retry target); 0 = none. */
    private int pendingReowns; //
    /** Bodies whose last {@link RemoteTeamFlip#flip} THREW or did not fully take.
     *  {@code flip} sets the hull pair at step 2 of 5, so the owner test at the top of the walk
     *  cannot see that the wings / fleet member / target sweep never ran — without this list the
     *  bounded retry would {@code continue} past the very body it was scheduled for. Identity
     *  membership, exactly like {@link #flippedBodies}; empty in every session whose flips all
     *  completed, pruned with it. */
    private final List<ShipAPI> reownRetry = new ArrayList<ShipAPI>(); //
    /** Sim tick of the last heavy re-side pass (the retry-cadence clock; unused while pendingReowns==0). */
    private long lastPassTick;

    TeamChangeLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** The live flipped bodies (see the field); render thread only, never copied. */
    List<ShipAPI> flippedBodies() {
        return flippedBodies;
    }

    /** {@code ownId} = this session's own wire id (the WELCOME {@code boundShipId}). */
    void advance(ServerSnapshotFrame snapshot, long ownId) {
        for (int i = flippedBodies.size() - 1; i >= 0; i--) {
            ShipAPI b = flippedBodies.get(i);
            if (!b.isAlive() || (plugin.engine != null && !plugin.engine.isEntityInPlay(b))) {
                flippedBodies.remove(i);
                reownRetry.remove(b); // The retry list never outlives the body
            }
        }
        List<ServerEntityPose> rows = snapshot.entities();
        long tick = snapshot.simTick();
        // ONE primitive walk — the own team's live side AND a cheap side signature
        // (id x fleetSide over combat rows), NO boxing / NO registry lookup. It gates the heavy pass
        // below: fleetSide only moves on a /switchteams, so the no-switch steady state stops here.
        int liveOwn = ServerEntityPose.UNKNOWN_FLEET_SIDE;
        long sideSig = 17L;
        int combatCount = 0;
        for (int i = 0, n = rows.size(); i < n; i++) { //
            ServerEntityPose p = rows.get(i);
            int abs = p.fleetSide();
            if (p.entityId() == ownId) {
                liveOwn = abs;
            }
            if (TeamPerspectiveLaw.isCombatSide(abs)) {
                sideSig = sideSig * 1099511628211L + p.entityId() * 1099511628211L + abs;
                combatCount++;
            }
        }
        sideSig = sideSig * 31L + liveOwn;
        boolean sideChanged = sideSig != lastSideSig || combatCount != lastCombatCount;
        boolean retryDue = pendingReowns > 0 && tick - lastPassTick >= REOWN_RETRY_TICKS;
        lastSideSig = sideSig;
        lastCombatCount = combatCount;
        if (!sideChanged && !retryDue) {
            return; // No side moved and nothing to re-own — skip the heavy walk
        }
        lastPassTick = tick;
        int changed = TeamPerspectiveLaw.ownTeamChangeOrNeg(liveOwn, plugin.ownDescSide);
        if (changed >= 0) {
            PilotWatchLog.info("predict team-change: OWN team abs " + plugin.ownDescSide + " -> "
                    + changed + " tick=" + tick
                    + " (the own row's live fleetSide; re-siding the registry)");
            plugin.ownDescSide = changed;
        }
        int resided = 0;
        int reowned = 0;
        int pending = 0;
        for (int i = 0, n = rows.size(); i < n; i++) {
            ServerEntityPose p = rows.get(i);
            int abs = p.fleetSide();
            if (!TeamPerspectiveLaw.isCombatSide(abs) || p.entityId() == ownId) {
                continue;
            }
            PredictReconcileCombatPlugin.RemoteEntity re =
                    plugin.remoteEntities.get(Long.valueOf(p.entityId()));
            if (re == null) {
                continue;
            }
            int rel = TeamPerspectiveLaw.relSide(abs, plugin.ownDescSide);
            if (rel != re.side) {
                re.side = rel;
                resided++;
            }
            ShipAPI body = re.ship;
            if (body == null || re.killed || plugin.engine == null || !body.isAlive()) {
                continue; // unbound / dead: the next mint reads the live side (corpse owner never read)
            }
            int wantOwner = rel == 0 ? plugin.sideAuthority() : 1 - plugin.sideAuthority();
            if (body.getOwner() == wantOwner && !reownRetry.contains(body)) {
                continue; // already on the wanted side (a module re-owned via its base's part walk)
            }
            // A mis-owned OR half-flipped bound body — a fresh side
            // move, a flip that threw after the hull pair took (RemoteTeamFlip step 2 of 5), or one
            // that did not take at all. Listing it BEFORE the attempt is the guarantee:
            // SideTotalsOverrideLane's coord/EW per-ship walks reach a flipped hull and its modules
            // ONLY through flippedBodies, so a throw inside flip() must not also cost the body its
            // bonus lane (the old order listed it on the success path only).
            addFlippedWithModules(body); // fail-soft internally; never throws into this pass
            boolean threw = false; //
            try { //
                String receipt = RemoteTeamFlip.flip(plugin.engine, body, wantOwner, plugin.ownShip);
                reowned++;
                PilotWatchLog.info("predict team-change: entity " + re.entityId + " rel " + (1 - rel)
                        + " -> " + rel + " owner -> " + wantOwner + " " + receipt);
            } catch (Throwable t) {
                threw = true;
                PilotWatchLog.error("predict team-change: entity " + re.entityId + " re-owner FAILED"
                        + " (registry side flipped; the body keeps owner " + body.getOwner()
                        + "; bounded retry in ~" + REOWN_RETRY_TICKS + " ticks)", t);
            }
            if (TeamPerspectiveLaw.reownPending(threw, body.getOwner(), wantOwner)) {
                pending++; // mis-owned OR half-flipped after the attempt -> the bounded retry re-tries
                if (!reownRetry.contains(body)) {
                    reownRetry.add(body);
                }
            } else {
                reownRetry.remove(body);
            }
        }
        pendingReowns = pending;
        if (changed >= 0 || resided > 0) {
            PilotWatchLog.info("predict team-change: tick=" + tick + " ownAbs="
                    + plugin.ownDescSide + " resided=" + resided + " reowned=" + reowned
                    + " pending=" + pending + " remotes=" + plugin.remoteEntities.size());
        }
    }

    /** Add the flipped hull AND its child modules (recursive) to
     *  {@link #flippedBodies}. {@link RemoteTeamFlip#flip} re-owns a station's modules through its
     *  part walk, but a module is evicted from the old side's deployed set and never re-added,
     *  so — like the base hull — it reaches {@link SideTotalsOverrideLane}'s coord/EW
     *  per-ship walk ONLY via this list; {@link SideTotalsOverrideLane#isFlippedOnSide} filters by
     *  owner and skips fighters, so a module lands on the correct side. Fighters/drones are
     *  deliberately NOT added (filtered there anyway, and they would churn the list on death). */
    private void addFlippedWithModules(ShipAPI hull) { //
        if (hull == null || flippedBodies.contains(hull)) {
            return;
        }
        flippedBodies.add(hull);
        List<ShipAPI> modules;
        try { // Called BEFORE the flip try now — keep the no-throw promise here
            modules = hull.getChildModulesCopy();
        } catch (Throwable ignore) {
            return; // the hull itself is listed; an unreadable module tree is never a render throw
        }
        if (modules != null) {
            for (int i = 0, n = modules.size(); i < n; i++) {
                addFlippedWithModules(modules.get(i));
            }
        }
    }
}
