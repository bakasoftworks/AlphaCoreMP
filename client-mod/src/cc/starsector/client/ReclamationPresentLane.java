package cc.starsector.client;

import java.awt.Color;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.impl.combat.threat.FragmentSwarmHullmod;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import com.fs.starfarer.api.impl.combat.threat.SwarmLauncherEffect;
import com.fs.starfarer.api.impl.combat.threat.ThreatShipReclamationScript;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

/**
 * The Threat RECLAMATION presentation lane: reclaimed hulks showed no dissolve FX, only the reclamation
 * swarm and then a burst of orange debris.
 * Knob {@code cc.predict.reclamationFx} =
 * {@code off|meter|apply} (default {@code apply}; {@code meter} = pairing check only: log
 * would-reclaim/unattributed at bind, write nothing). Zero wire bytes: everything below is derived
 * from rows the client already receives.
 *
 * <p><b>Root (re-verified first-hand).</b> Vanilla's whole dissolve lives in
 * {@code ThreatShipReclamationScript}: instantiated by {@code ThreatHullmod} one
 * second into hulk-hood with {@code delay = 3}; per frame it decelerates every
 * piece, fades the second half (alpha falls linearly to 0), plays
 * {@code reclamation_loop} on the first live piece, buries the pieces in 11 EMP-fringe
 * negative-nebula puffs per interval, spawns the reclamation swarms once the
 * clock passes the delay plus 2 s and ends with a SILENT
 * {@code removeEntity} + alpha 0 — no explosion. Our replica hulk carries
 * {@code ship_being_reclaimed} from birth ({@link ThreatHulkReclaimLaw}, G7) so no client ever runs
 * that script; the server runs it headless, its swarms arrive as growth-born replicas, and its
 * {@code removeEntity} reaches the client as a plain hulk-family PIECE_REMOVED edge, which
 * {@link FractureFamilyCoordinator#applyFractureEdge} presents with the generic fracture
 * explosion ({@code FRACTURE_EXPLOSION_COLOR} = the orange burst) — while
 * {@link HulkKeepAliveAdapter#keepAlive} pins {@code alphaMult} to 1 every sweep, so no fade could
 * ever show.
 *
 * <p><b>Fix = vanilla's presentation half, run LOCALLY, wire-ended.</b> The trigger is the FIRST
 * {@code attack_swarm_Reclamation} replica ({@code SwarmLauncherEffect.RECLAMATION_SWARM_VARIANT};
 * that variant is minted ONLY by the reclamation script, at the primary's location)
 * binding within {@link #PAIR_SLACK} + R of a Threat hulk (the omega
 * shape: a growth-born child beside a hulk IS the server's script frame, one latency late). At
 * that instant vanilla's clock reads {@code delay + 2} ({@link #SWARM_SPAWN_OFFSET}), so the local
 * clock starts there; {@code fadeOutTime} is the hull-size law read off the
 * primary. The pieces are the wire family's live pieces ({@link FractureFamilyCoordinator#familyPiecesOf};
 * fallback = vanilla's own same-fleet-member law). Alpha rides
 * {@code setExtraAlphaMult2} (the keep-alive pin owns {@code alphaMult}; both channels multiply
 * into the hulk's render alpha — the omega dissolve's proven channel). The wire REMOVE edge is the
 * ONLY remover: {@link #endedByWire} makes it silent (the alpha at the edge = the skew meter, as
 * omega). Never written here: deceleration (the hulk's motion is wire-driven,
 * HKPS keyframes + coast), the swarm spawn/member emergence, {@code removeEntity}.
 * Overrun (no REMOVE within {@code fadeOutTime + delay + }{@link #OVERRUN_HOLD}): restore VISIBLY
 * and log — never a hidden ghost (never-conceal).
 *
 * <p>The reclamation swarm replica's own cloud is dressed at the same bind with vanilla's
 * {@code launchSwarm} params (orange flash fringe, flash every
 * frame, no core, ×2 offset) — the cloud seen in play was the generic attack-swarm cloud.
 *
 * <p>NEW-LANE LAW collaborator: PRCP keeps touchpoints only — the bind relay after
 * {@code neutralizeRemoteBody} at the growth spawn site, an advance relay beside the construction
 * lane's, a reset relay, the meter line; {@link FractureFamilyCoordinator} keeps the REMOVE-edge
 * question + the family read. Render-thread only. Fail-safe per reclaim per frame: any throw
 * restores that family visible and drops it, logged once. Census: {@link CcReplicaEffectGate}
 * under {@link #TAG} — the teardown {@code wsg census} line shows {@code reclaim=frames:N,
 * doorShut:0,visual:M}. {@code off} = the orange burst, byte-for-byte.
 */
final class ReclamationPresentLane {

    /** Census tag on the {@link CcReplicaEffectGate} line. */
    static final String TAG = "reclaim";

    /** Resolved {@code cc.predict.reclamationFx} mode (the shared {@code off|meter|apply} vocabulary). */
    static final String MODE = PredictReconcileCombatPlugin.PredictLane.parseMode(
            "cc.predict.reclamationFx", System.getProperty("cc.predict.reclamationFx", "apply"));
    /** {@code meter} or {@code apply}: the pairing runs and is logged. */
    static final boolean METER = !"off".equals(MODE);
    /** {@code apply} only: the dissolve is presented and the REMOVE edge goes silent. */
    static final boolean APPLY = "apply".equals(MODE);

    /** The script's start delay. */
    static final float SCRIPT_DELAY = 3f;
    /** The swarms spawn once the script clock passes the delay plus this offset: the sighting's clock value. */
    static final float SWARM_SPAWN_OFFSET = 2f;
    /** Pairing slack beyond the hulk's collision radius: one latency of fighter flight + wire
     *  quantization (the construction lane's 300 su). */
    static final float PAIR_SLACK = 300f;
    /** Grace past vanilla's own end before an unremoved family is restored visible. */
    static final float OVERRUN_HOLD = 3f;
    /** Hull tag of the fabricator — vanilla never reclaims one. */
    static final String TAG_THREAT_FABRICATOR = "threat_fabricator";
    /** The hullmod whose builtin cloud the reclamation replica carries. */
    static final String FRAGMENT_SWARM_HULLMOD = "fragment_swarm";
    /** The reclamation cloud's flash fringe. */
    static final Color RECLAMATION_FLASH_FRINGE = new Color(255, 70, 30, 50);
    /** The dock reach beyond the fabricator's collision radius. */
    static final float DOCK_RANGE = 200f;
    /** Dock-test slack for the replica's last wire pose against vanilla's own. */
    static final float DOCK_SLACK = 150f;

    private static final float PARTICLE_INTERVAL_MIN = 0.075f;
    private static final float PARTICLE_INTERVAL_MAX = 0.125f;
    private static final int LOG_MAX = 16;

    /** One family mid-dissolve — the vanilla script's per-instance state, presentation half. */
    private static final class Reclaim {
        final ShipAPI primary;
        final ArrayList<ShipAPI> pieces;
        final float fadeOutTime;
        /** The particle interval — shared across pieces and advanced per piece, exactly as vanilla. */
        final IntervalUtil interval = new IntervalUtil(PARTICLE_INTERVAL_MIN, PARTICLE_INTERVAL_MAX);
        /** Vanilla's {@code elapsed}, started at the sighting's clock value. */
        float elapsed = SCRIPT_DELAY + SWARM_SPAWN_OFFSET;
        float lastAlpha = 1f;
        /** Vanilla's {@code swarms} — this family's reclamation swarm REPLICAS (the first at begin,
         *  siblings as they bind); the member ramp feeds their clouds. */
        final ArrayList<ShipAPI> swarms = new ArrayList<ShipAPI>();
        /** Vanilla's {@code interval2} — the member-ramp clock, advanced at twice the frame clock. */
        final IntervalUtil spawnInterval = new IntervalUtil(PARTICLE_INTERVAL_MIN, PARTICLE_INTERVAL_MAX);

        Reclaim(ShipAPI primary, ArrayList<ShipAPI> pieces, float fadeOutTime) {
            this.primary = primary;
            this.pieces = pieces;
            this.fadeOutTime = fadeOutTime;
            this.interval.forceIntervalElapsed(); //
        }
    }

    private final ArrayList<Reclaim> reclaims = new ArrayList<Reclaim>();
    /** Every enrolled piece → its reclaim (identity; the REMOVE-edge lookup). */
    private final IdentityHashMap<ShipAPI, Reclaim> byPiece = new IdentityHashMap<ShipAPI, Reclaim>();
    /** Reclamation replicas whose cloud was dressed (identity; the ×2 offset must land once). */
    private final IdentityHashMap<ShipAPI, Boolean> dressed = new IdentityHashMap<ShipAPI, Boolean>();

    private int begun;
    private int meterCandidates;
    private int siblings;
    private int unattributed;
    private int endsWire;
    private int endsGone;
    private int overruns;
    private int cloudsDressed;
    private int docksMerged;
    private int burstCleared;   // Members the hullmod burst before dressCloud, taken back
    private int rampAdds;       // Members trickled in by rampMembers
    private int throwsSeen;
    private int logLines;

    /**
     * Bind relay — call ONCE per growth-born replica right after {@code neutralizeRemoteBody}.
     * Declines silently for everything that is not a reclamation swarm replica. Returns
     * {@code true} when a dissolve began on this call.
     */
    boolean onReplicaBound(ShipAPI ship, CombatEngineAPI engine, FractureFamilyCoordinator fractures) {
        if (!METER || ship == null || engine == null) {
            return false;
        }
        try {
            if (!isReclamationSwarmReplica(ship)) {
                return false;
            }
            if (APPLY) {
                dressCloud(ship);
            }
            ShipAPI primary = findPrimary(ship, engine);
            if (primary == null) {
                unattributed++;
                log("UNATTRIBUTED reclamation swarm " + ident(ship) + " — no Threat hulk within R+"
                        + PAIR_SLACK + " su (mode=" + MODE + ")");
                return false;
            }
            if (byPiece.containsKey(primary)) {
                siblings++; // a later swarm of the same reclamation — the family is already dissolving
                Reclaim family = byPiece.get(primary);
                if (APPLY && family != null && !family.swarms.contains(ship)) {
                    family.swarms.add(ship); // The ramp feeds every swarm of the family
                }
                return false;
            }
            if (!APPLY) {
                meterCandidates++;
                log("METER would-reclaim " + hullId(primary) + " " + ident(primary) + " from swarm "
                        + ident(ship) + " (mode=meter, nothing written)");
                return false;
            }
            return begin(primary, ship, engine, fractures);
        } catch (Throwable t) {
            throwsSeen++;
            log("bind THREW " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    /** Tick — per live frame: the vanilla {@code advance} presentation half per family. */
    void advance(CombatEngineAPI engine, float amount) {
        if (!APPLY || engine == null || reclaims.isEmpty() || engine.isPaused()) {
            return; // the dissolve freezes while paused
        }
        for (Iterator<Reclaim> it = reclaims.iterator(); it.hasNext();) {
            Reclaim r = it.next();
            try {
                if (advanceReclaim(r, engine, amount)) {
                    it.remove();
                    forget(r);
                }
            } catch (Throwable t) {
                throwsSeen++;
                it.remove();
                forget(r);
                restoreVisible(r);
                log("advance THREW on " + hullId(r.primary) + " " + t.getClass().getSimpleName()
                        + ": " + t.getMessage() + " -> restored visible");
            }
        }
    }

    /**
     * The REMOVE-edge question ({@link FractureFamilyCoordinator#applyFractureEdge}): is this piece
     * mid-dissolve? {@code true} = the wire edge ends vanilla's script for it — leave silently, no
     * explosion. The last piece's edge drops the family.
     */
    boolean endedByWire(ShipAPI piece) {
        if (!APPLY || piece == null) {
            return false;
        }
        Reclaim r = byPiece.remove(piece);
        if (r == null) {
            return false;
        }
        endsWire++;
        r.pieces.remove(piece);
        float skew = r.elapsed - (SCRIPT_DELAY + r.fadeOutTime);
        log("END(wire) " + hullId(r.primary) + " piece " + ident(piece) + " alpha=" + r.lastAlpha
                + " skew=" + skew + "s (local clock minus vanilla end; <0 = the REMOVE came early)"
                + " pieces_left=" + r.pieces.size());
        if (r.pieces.isEmpty()) {
            reclaims.remove(r);
        }
        return true;
    }

    /** Round wrap: every dissolve dies with the round (fail-soft restore). */
    void resetRound() {
        for (Reclaim r : reclaims) {
            restoreVisible(r);
        }
        reclaims.clear();
        byPiece.clear();
        dressed.clear();
    }

    /** Meter fragment (pinned key names; rides the spawn-gate meter line). */
    String meterTokens() {
        return " predict_reclaim_mode=" + MODE
                + " predict_reclaim_begun=" + begun
                + " predict_reclaim_meter_candidates=" + meterCandidates
                + " predict_reclaim_siblings=" + siblings
                + " predict_reclaim_unattributed=" + unattributed
                + " predict_reclaim_ends_wire=" + endsWire
                + " predict_reclaim_ends_gone=" + endsGone
                + " predict_reclaim_overruns=" + overruns
                + " predict_reclaim_clouds_dressed=" + cloudsDressed
                + " predict_reclaim_docks_merged=" + docksMerged
                + " predict_reclaim_burst_cleared=" + burstCleared
                + " predict_reclaim_ramp_adds=" + rampAdds
                + " predict_reclaim_throws=" + throwsSeen;
    }

    // ------------------------------------------------------------------ dock-stream

    /**
     * REMOVE-edge relay for a reclamation swarm REPLICA (the fighter, not the hulk). Vanilla's dock
     * works so: within the fabricator's R + 200 the swarm turns its orange
     * flash off, springs every member into the fabricator's cloud, then self-kills — the wire
     * delivers only that kill. Without this the replica's local 50-member cloud simply ends at the
     * REMOVE ("a vanish, not a merge"). Returns {@code true} when the merge was presented; the
     * caller then removes the body silently (no fighter-death FX).
     */
    boolean onReplicaRemoved(ShipAPI ship, CombatEngineAPI engine) {
        if (!APPLY || ship == null || engine == null) {
            return false;
        }
        try {
            if (!isReclamationSwarmReplica(ship)) {
                return false;
            }
            ShipAPI fab = findFabricator(ship, engine);
            if (fab == null) {
                return false; // died elsewhere — the normal fighter death presents
            }
            RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(ship);
            if (swarm == null) {
                return false;
            }
            RoilingSwarmEffect fabSwarm = RoilingSwarmEffect.getSwarmFor(fab);
            if (fabSwarm == null) {
                ShipVariantAPI v = fab.getVariant();
                if (v != null && v.hasHullMod(FRAGMENT_SWARM_HULLMOD)) {
                    fabSwarm = FragmentSwarmHullmod.createSwarmFor(fab); // lazy-mint, as dressCloud
                }
            }
            if (fabSwarm == null) {
                return false;
            }
            RoilingSwarmEffect.RoilingSwarmParams sp = swarm.getParams();
            sp.flashFrequency = 0f; //
            sp.flashProbability = 0f; //
            int moved = swarm.getNumActiveMembers();
            swarm.transferMembersTo(fabSwarm, moved); //
            docksMerged++;
            CcReplicaEffectGate.replicaFrame(TAG);
            CcReplicaEffectGate.visualMinted(TAG);
            log("DOCK " + ident(ship) + " -> " + hullId(fab) + " members=" + moved
                    + " (vanilla ThreatSwarmAI dock, local; the wire REMOVE ends the body silently)");
            return true;
        } catch (Throwable t) {
            throwsSeen++;
            log("dock THREW " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    /** The nearest live same-owner fabricator within R + 200 (+ slack). */
    private static ShipAPI findFabricator(ShipAPI swarm, CombatEngineAPI engine) {
        int owner = swarm.getOwner();
        Vector2f at = swarm.getLocation();
        ShipAPI best = null;
        float bestDist = Float.MAX_VALUE;
        for (ShipAPI s : engine.getShips()) {
            if (s == null || s == swarm || s.getOwner() != owner || s.isHulk() || !s.isAlive()
                    || s.isFighter()) {
                continue;
            }
            ShipHullSpecAPI spec = s.getHullSpec();
            if (spec == null || !spec.hasTag(TAG_THREAT_FABRICATOR)) {
                continue;
            }
            float d = Misc.getDistance(s.getLocation(), at);
            if (d <= s.getCollisionRadius() + DOCK_RANGE + DOCK_SLACK && d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ pure laws

    /** Vanilla's law verbatim: the dissolve length by hull size, plus a flat 3 s. */
    static float fadeOutTimeFor(ShipAPI.HullSize size) {
        float t;
        if (size == null) {
            t = 7f;
        } else {
            switch (size) {
                case FRIGATE: t = 7f; break;
                case DESTROYER: t = 9f; break;
                case CRUISER: t = 12f; break;
                case CAPITAL_SHIP: t = 15f; break;
                default: t = 7f;
            }
        }
        return t + 3f;
    }

    /** The alpha multiplier of a hull being reclaimed at script
     *  clock {@code elapsed} (seconds), written from the functional spec.
     *  The fade begins once {@link #SCRIPT_DELAY} has passed and runs {@code fadeOutTime} seconds; with
     *  {@code done} = the fraction of it already run, held to [0, 1], the hull stays fully opaque (1)
     *  while {@code done <= 0.5} and then drops linearly to 0 at {@code done = 1}: {@code 2 × (1 − done)}.
     *  Hence 1 before the delay and 0 from the fade's end on. {@code fadeOutTime} comes from
     *  {@link #fadeOutTimeFor} (always ≥ 10 s), so there is no zero guard; float math only, never throws. */
    static float alphaFor(float elapsed, float fadeOutTime) {
        float done = Math.min(1f, Math.max(0f, (elapsed - SCRIPT_DELAY) / fadeOutTime));
        return done <= 0.5f ? 1f : 2f * (1f - done);
    }

    /**
     * Is this variant the reclamation swarm? Same shape as the construction lane: a member-first
     * minted replica may carry a per-ship clone id, so an id PREFIX and the original id count too.
     */
    static boolean isReclamationSwarmVariant(String hullVariantId, String originalVariantId) {
        String want = SwarmLauncherEffect.RECLAMATION_SWARM_VARIANT;
        if (hullVariantId != null && (hullVariantId.equals(want) || hullVariantId.startsWith(want))) {
            return true;
        }
        return originalVariantId != null && originalVariantId.equals(want);
    }

    /** A dead Threat hull vanilla would reclaim: hulk, {@code threat_hullmod}, not a fighter, not a fabricator. */
    static boolean isReclaimableHulk(ShipAPI s) {
        if (s == null || !s.isHulk() || s.isFighter() || s.isStation()) {
            return false;
        }
        ShipVariantAPI v = s.getVariant();
        if (v == null || !v.hasHullMod(ThreatHulkReclaimLaw.THREAT_HULLMOD_ID)) {
            return false;
        }
        ShipHullSpecAPI spec = s.getHullSpec();
        Set<String> tags = spec != null ? spec.getTags() : null;
        return tags == null || !tags.contains(TAG_THREAT_FABRICATOR);
    }

    // ------------------------------------------------------------------ enrolment

    private static boolean isReclamationSwarmReplica(ShipAPI s) {
        if (!s.isFighter() || !s.isAlive() || s.isHulk()) {
            return false;
        }
        if (!CcReplicaEffectGate.isTreatedReplica(s)) {
            return false; // serverless window / untreated body → vanilla owns everything
        }
        ShipVariantAPI v = s.getVariant();
        if (v != null && isReclamationSwarmVariant(v.getHullVariantId(), v.getOriginalVariant())) {
            return true;
        }
        return s.getWing() != null
                && SwarmLauncherEffect.RECLAMATION_SWARM_WING.equals(s.getWing().getWingId());
    }

    /** The nearest reclaimable Threat hulk within its own R + {@link #PAIR_SLACK} of the swarm. */
    private static ShipAPI findPrimary(ShipAPI swarm, CombatEngineAPI engine) {
        ShipAPI best = null;
        float bestDist = Float.MAX_VALUE;
        Vector2f at = swarm.getLocation();
        for (ShipAPI s : engine.getShips()) {
            if (!isReclaimableHulk(s)) {
                continue;
            }
            float d = Misc.getDistance(s.getLocation(), at);
            if (d <= s.getCollisionRadius() + PAIR_SLACK && d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    private boolean begin(ShipAPI primary, ShipAPI swarm, CombatEngineAPI engine,
            FractureFamilyCoordinator fractures) {
        ArrayList<ShipAPI> pieces = fractures != null ? fractures.familyPiecesOf(primary) : null;
        String source = "family";
        if (pieces == null || pieces.isEmpty()) {
            // vanilla's own piece law (a hulk that is not a wire family, or a family read miss)
            pieces = new ArrayList<ShipAPI>();
            source = "fleet-member";
            for (ShipAPI curr : engine.getShips()) {
                if (curr != null && curr.getFleetMember() != null
                        && curr.getFleetMember() == primary.getFleetMember()) {
                    pieces.add(curr);
                }
            }
        }
        if (!pieces.contains(primary)) {
            pieces.add(primary);
        }
        // A piece already dissolving in another family is that family's (never two owners).
        for (Iterator<ShipAPI> it = pieces.iterator(); it.hasNext();) {
            ShipAPI p = it.next();
            if (p == null || (p != primary && byPiece.containsKey(p))) {
                it.remove();
            }
        }
        Reclaim r = new Reclaim(primary, pieces, fadeOutTimeFor(primary.getHullSize()));
        if (swarm != null) {
            r.swarms.add(swarm);
        }
        reclaims.add(r);
        for (ShipAPI p : pieces) {
            byPiece.put(p, r);
        }
        begun++;
        CcReplicaEffectGate.replicaFrame(TAG);
        CcReplicaEffectGate.visualMinted(TAG);
        log("BEGIN " + hullId(primary) + " " + ident(primary) + " pieces=" + pieces.size() + " (" + source
                + ") fadeOut=" + r.fadeOutTime + "s clock=" + r.elapsed
                + "s (vanilla ThreatShipReclamationScript presentation half, local; wire REMOVE ends it)");
        return true;
    }

    /** Vanilla's {@code launchSwarm} params — the reclamation cloud's look (idempotent per replica). */
    private void dressCloud(ShipAPI fighter) {
        if (dressed.containsKey(fighter)) {
            return;
        }
        dressed.put(fighter, Boolean.TRUE);
        RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(fighter);
        if (swarm == null) {
            ShipVariantAPI v = fighter.getVariant();
            if (v == null || !v.hasHullMod(FRAGMENT_SWARM_HULLMOD)) {
                return; // no builtin → no cloud to dress
            }
            swarm = FragmentSwarmHullmod.createSwarmFor(fighter);
        }
        if (swarm == null) {
            return;
        }
        RoilingSwarmEffect.RoilingSwarmParams sp = swarm.getParams();
        sp.flashFrequency = 5f; //
        sp.flashProbability = 1f; //
        sp.flashFringeColor = RECLAMATION_FLASH_FRINGE; //
        sp.flashCoreRadiusMult = 0f; //
        sp.springStretchMult = 1f; //
        sp.maxOffset = sp.maxOffset * ThreatShipReclamationScript.RECLAMATION_SWARM_RADIUS_MULT; //
        // Swarms emerged all at once: vanilla's
        // launchSwarm starts the cloud EMPTY (initialMembers 0, maintain 50) and the script
        // trickles members off the dissolving pieces (spawnSwarms, mirrored in
        // rampMembers); the replica's builtin (FragmentSwarmHullmod.createSwarmFor) says
        // initialMembers 50 and RoilingSwarmEffect.advance bursts them on its first tick.
        // Set vanilla's numbers BEFORE that tick; if the hullmod's advance already won the race
        // (members on screen), take the burst back — vanilla never shows it.
        sp.initialMembers = 0; //
        sp.baseMembersToMaintain = 50; //
        int burst = swarm.getMembers().size();
        if (burst > 0) {
            swarm.getMembers().clear(); // the live list; the ramp re-fills it from the pieces
            burstCleared += burst;
        }
        cloudsDressed++;
    }

    /** The member-ramp interval runs at this multiple of the frame clock. */
    private static final float RAMP_CLOCK_RATE = 2f;
    /** A ramped member is born within this fraction of its piece's collision radius. */
    private static final float MEMBER_SPAWN_RADII = 0.5f;
    /** A ramped member fades in from nothing over this long (seconds). */
    private static final float MEMBER_FADE_IN_SEC = 0.3f;

    /** The member ramp, rewritten from the functional spec.
     *  The family's second interval ({@code spawnInterval}, 0.075-0.125 s)
     *  advances at twice the frame clock; on each elapse every live swarm replica of the family whose
     *  cloud holds no more active members than its maintain count gets ONE member, placed by the engine's
     *  {@code Misc.getPointWithinRadius} within half the collision radius of a uniformly picked live
     *  piece and faded in from nothing over 0.3 s ({@code ramp_adds}). The caller runs it only while
     *  {@link #rampOpen} holds. */
    private void rampMembers(Reclaim r, CombatEngineAPI engine, float amount) {
        r.spawnInterval.advance(amount * RAMP_CLOCK_RATE);
        if (!r.spawnInterval.intervalElapsed()) {
            return;
        }
        ArrayList<ShipAPI> live = livePieces(r, engine);
        if (live.isEmpty()) {
            return;
        }
        for (ShipAPI swarmShip : r.swarms) {
            if (swarmShip == null || !swarmShip.isAlive() || !engine.isEntityInPlay(swarmShip)) {
                continue;
            }
            RoilingSwarmEffect cloud = RoilingSwarmEffect.getSwarmFor(swarmShip);
            if (cloud == null || cloud.getNumActiveMembers() > cloud.getNumMembersToMaintain()) {
                continue;
            }
            ShipAPI from = live.get(Misc.random.nextInt(live.size()));
            RoilingSwarmEffect.SwarmMember member = cloud.addMember();
            if (member == null) {
                continue;
            }
            // a fresh vector: the member's location never aliases the piece's own
            member.loc = Misc.getPointWithinRadius(from.getLocation(), from.getCollisionRadius() * MEMBER_SPAWN_RADII);
            if (member.fader != null) {
                member.fader.setDurationIn(MEMBER_FADE_IN_SEC);
                member.fader.forceOut();
                member.fader.fadeIn();
            }
            rampAdds++;
        }
    }

    // ------------------------------------------------------------------ per-frame

    /** The dissolve's sound loop, played on the first live piece every frame. */
    private static final String RECLAMATION_LOOP_ID = "reclamation_loop";
    /** The member ramp stops once this little of the dissolve remains (seconds). */
    static final float RAMP_GATE_SEC = 4f;

    /** One family's frame of the dissolve, rewritten from the
     *  functional spec. The clock advances by dt; with no live piece left
     *  the family is done ({@code ends_gone}); past {@code 3 + F + }{@link #OVERRUN_HOLD} s with no wire
     *  REMOVE every piece is restored visible and logged ({@code overruns}); otherwise the alpha law
     *  ({@link #alphaFor}) rides each live piece's {@code setExtraAlphaMult2}, {@code reclamation_loop}
     *  plays on the first live piece, every live piece takes its turn on the shared burst interval
     *  ({@link #spawnParticles}), and the member ramp runs while {@link #rampOpen} holds (more than
     *  {@link #RAMP_GATE_SEC} s remain). Deceleration and removal are never written here (the motion is
     *  wire-driven; the wire REMOVE edge is the remover). Returns {@code true} when the family is done here. */
    private boolean advanceReclaim(Reclaim r, CombatEngineAPI engine, float amount) {
        r.elapsed += amount;
        ArrayList<ShipAPI> live = livePieces(r, engine);
        if (live.isEmpty()) {
            endsGone++;
            return true;
        }
        if (r.elapsed > SCRIPT_DELAY + r.fadeOutTime + OVERRUN_HOLD) {
            restoreVisible(r);
            overruns++;
            log("OVERRUN " + hullId(r.primary) + " " + ident(r.primary) + " clock=" + r.elapsed + "s fadeOut="
                    + r.fadeOutTime + "s pieces=" + live.size() + " (no wire REMOVE) -> restored visible");
            return true;
        }
        float alpha = alphaFor(r.elapsed, r.fadeOutTime);
        r.lastAlpha = alpha;
        for (ShipAPI p : live) {
            p.setExtraAlphaMult2(alpha);
        }
        ShipAPI first = live.get(0);
        Global.getSoundPlayer().playLoop(RECLAMATION_LOOP_ID, first, 1f, 1f, first.getLocation(), first.getVelocity());
        for (ShipAPI p : live) {
            spawnParticles(r, p, engine, amount);
        }
        if (rampOpen(r.elapsed, r.fadeOutTime)) {
            rampMembers(r, engine, amount);
        }
        return false;
    }

    /** The time left in the dissolve, {@code F − (t − 3)} seconds
     *  (negative past its end); {@code elapsed} = the local clock t (≥ 5 s from the sighting). */
    static float remainingFor(float elapsed, float fadeOutTime) {
        return fadeOutTime - (elapsed - SCRIPT_DELAY);
    }

    /** The member-ramp gate — open only while MORE than
     *  {@link #RAMP_GATE_SEC} s of the dissolve remain (members stop arriving through its last 4 s). */
    static boolean rampOpen(float elapsed, float fadeOutTime) {
        return remainingFor(elapsed, fadeOutTime) > RAMP_GATE_SEC;
    }

    /** The family's pieces still in the engine, in enrolment order
     *  (a piece the wire removed already left {@code r.pieces} via {@link #endedByWire}). */
    private static ArrayList<ShipAPI> livePieces(Reclaim r, CombatEngineAPI engine) {
        ArrayList<ShipAPI> live = new ArrayList<ShipAPI>(r.pieces.size());
        for (ShipAPI p : r.pieces) {
            if (p != null && engine.isEntityInPlay(p)) {
                live.add(p);
            }
        }
        return live;
    }

    /** One live piece's turn on the family's ONE shared burst
     *  interval ({@code Reclaim.interval}, 0.075-0.125 s, forced elapsed at enrolment so the first turn
     *  bursts), rewritten from the functional spec. Every turn advances it
     *  by the frame's dt ({@link #pieceTurn}), so k live pieces burst k times as often as one; a due turn
     *  lays the {@link DarkPuffBurst.Use#RECLAMATION} burst on this piece, its puffs living 1 s longer
     *  once under 1 s of the dissolve remains. */
    private static void spawnParticles(Reclaim r, ShipAPI ship, CombatEngineAPI engine, float amount) {
        if (!pieceTurn(r.interval, amount)) {
            return;
        }
        float extra = DarkPuffBurst.extraLifeSec(DarkPuffBurst.Use.RECLAMATION,
                remainingFor(r.elapsed, r.fadeOutTime));
        DarkPuffBurst.burst(DarkPuffBurst.Use.RECLAMATION, engine, ship, extra);
    }

    /** One turn on a shared interval — advance it by
     *  {@code amount} (frame seconds, ≥ 0) and report whether it elapsed on this turn. */
    static boolean pieceTurn(IntervalUtil shared, float amount) {
        shared.advance(amount);
        return shared.intervalElapsed();
    }

    // ------------------------------------------------------------------ housekeeping

    private void forget(Reclaim r) {
        for (ShipAPI p : r.pieces) {
            if (byPiece.get(p) == r) {
                byPiece.remove(p);
            }
        }
    }

    /** The honest exit: full brightness back on every piece (each write fail-soft). */
    private static void restoreVisible(Reclaim r) {
        for (ShipAPI p : r.pieces) {
            try {
                if (p != null) {
                    p.setExtraAlphaMult2(1f);
                }
            } catch (Throwable ignore) {
                // fail-soft: presentation only
            }
        }
    }

    private static String hullId(ShipAPI s) {
        try {
            return s.getHullSpec() != null ? s.getHullSpec().getHullId() : "?";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String ident(ShipAPI s) {
        return "@" + Integer.toHexString(System.identityHashCode(s));
    }

    private void log(String msg) {
        if (logLines >= LOG_MAX) {
            return;
        }
        logLines++;
        PilotWatchLog.info("predict reclaim: " + msg + (logLines == LOG_MAX ? " (log cap reached)" : ""));
    }
}
