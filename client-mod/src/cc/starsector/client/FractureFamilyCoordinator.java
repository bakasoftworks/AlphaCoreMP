package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.hullmods.ShardSpawner;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The HULK-FRACTURE FAMILY lane COORDINATOR: the in-plugin
 * orchestration/state of the {@code HKFR} hulk-split sync lane, extracted VERBATIM from
 * {@link PredictReconcileCombatPlugin} (the deferred-refactors PRCP orchestration seam; method
 * bodies byte-identical modulo {@code plugin.} receivers — the {@link FireLaneCoordinator}
 * extraction idiom). This class owns the lane STATE (net-thread intake deque, family/park maps,
 * the despawn-tick source map, every lane counter) and the drivers the plugin calls:
 * net-thread {@link #onHulkFractureEvent} + {@link #onHulkPoseEvent} + {@link #noteDespawnTick},
 * render-thread {@link #mintFractureFamily} / {@link #maintainFractureFamilies} /
 * {@link #suppressLocalBreakProb}, and the round-wrap {@link #resetForMatchReset}.
 * Threading contract unchanged by the move: render-thread only, except {@link #fractureIntake},
 * {@link #poseIntake} and {@link #despawnTickByEntity} (net-thread writes under their own
 * monitors — the cross-thread members). The {@code HKPS} corpse-pose keyframe lane
 * lands INSIDE this coordinator: {@link #onHulkPoseEvent} intake →
 * {@link #maintainPoseKeyframes} one-shot exact adoption, knob {@code cc.predict.hulkPose}.
 * So does the {@code HKCG} cut-geometry replay (s-F(1)):
 * {@link #onHulkCutGeometryEvent} intake → per-cut latch → the PIECE_ADDED leg replays the
 * server's exact cut through {@link HulkCutGeometryAdapter}, knob
 * {@code cc.predict.hulkCutGeometry}.
 */
final class FractureFamilyCoordinator {

    /**
     * The owning plugin — the supplier of the mutable cross-lane state the moved bodies read
     * ({@code engine} rebinds per combat; {@code session}/{@code ownDespawnTick}/
     * {@code lastSnapshotSimTick} feed the mint's death-tick resolution).
     */
    private final PredictReconcileCombatPlugin plugin;

    FractureFamilyCoordinator(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** The engine-wide post-death overkill veto —
     * eats HULL damage iff the target is a hulk NOT in one of our wire families, so local hits
     * on an un-owned corpse never accumulate the overkill that drives the engine's forced-split
     * + small-corpse explode-remove lanes (a {@code true} return zeroes both the clamped hull
     * damage and the raw overkill accumulator).
     * WIRE pieces pass: vanilla keys the per-hit presentation — impact-sound tiers,
     * per-hit debris — entirely to the APPLIED-damage result,
     * so the blanket veto made hulk hits "wimpy"; their break
     * machine is instead held closed by {@link HulkOverkillPin} ({@code setOverkill(-1e8)}, the
     * engine's own suppressor idiom) — local damage may PRESENT, membership stays server-owned
     * (the membership law every hulk lane keeps). Knob-off or a disabled pin reverts to the blanket veto
     * (today's behavior = the fail-soft direction). Armor/decals are applied before the hull
     * gate and stay untouched; hulks render no HP bar, so nothing round-relevant forks (the
     * only deltas are presentation + cosmetic damage-dealt stats). Corpse
     * fracture and piece removal arrive ONLY as authoritative {@code HKFR} edges. ONE
     * engine-level registration covers every ship incl. replicas ({@code CombatListenerUtil}
     * OR-folds engine-level listeners before per-ship ones) — the mod's first engine-wide
     * listener. The authoritative kill blow itself always passes:
     * {@code PredictReconcileCombatPlugin}'s {@code presentServerDeath} fires while the ship is
     * still alive ({@code isHulk()==false}). */
    static final class HulkOverkillVeto
            implements com.fs.starfarer.api.combat.listeners.HullDamageAboutToBeTakenListener {
        /** The lane home — consulted per hulk hit to scope the veto to NON-wire hulks
         * (identity scan; the damage listener runs on the same thread as the render-thread
         * family state). */
        private final FractureFamilyCoordinator fractures;

        HulkOverkillVeto(FractureFamilyCoordinator fractures) {
            this.fractures = fractures;
        }

        @Override
        public boolean notifyAboutToTakeHullDamage(Object param, ShipAPI ship, Vector2f point,
                float damageAmount) {
            if (ship == null || !ship.isHulk()) {
                return false; // live ships never vetoed — the authoritative kill blow passes
            }
            if (!HULK_OVERKILL_PIN || HulkOverkillPin.isDisabled()) {
                return true; // blanket veto — the pre-overkill-pin behavior (rollback / fail-soft)
            }
            return !fractures.isWirePiece(ship);
        }
    }

    /** One server-authoritative corpse family: the local pieces standing in for the
     * server's remnant family, wire-identified by its deathTick (schema v2 — the derived
     * familySeq ordinal desynced on any unobserved death). Minted at the {@code
     * presentServerDeath} edge carrying the mint-time death tick; the first parked queue that
     * binds (same rootId, nearest {@code |mintTick − wireDeathTick|} ≤ 150) LATCHES the family to
     * its exact wire tick — no double-claim. Seeded with the corpse ref; pieces returned by our
     * own {@code splitShip()} cuts join. Render-thread-only state. */
    private static final class FractureFamily {
        final long rootEntityId;
        final long mintTick;
        long latchedWireTick = -1L;
        /** Last simTick a break SFX played for this family (the ADD break-SFX law: 120-tick per-family
         * debounce — the engine's H machine splits over interval ticks, one sound per break). */
        long lastBreakSfxTick = -120L;
        /** Client cutSeq mirror: +1 per PIECE_ADDED TERMINALLY consumed from this
         * family's queue (applied, accepted cut failure, or dropped — the server increments per
         * EMITTED ADD, first-sight ADDs included); the ordinal a pending ADD latches under is
         * the value BEFORE its own increment. FIFO lane ⇒ ordinals line up; any desync just
         * means a cold latch ⇒ local cut (fail-soft). */
        int appliedCutSeq;
        /** Ghost fix: accepted ADD-cut failures leave this family persistently
         * one short of the server's count, so the already-applied REMOVE guard's bare count
         * equality coincides on the NEXT server REMOVE and drops it — the server-removed piece
         * survives locally, keep-alive-pinned. The guard exemption is equality ∧ debt==0.
         * Debt must NOT decrement on a REMOVE apply (the nuance: applying under debt leaves
         * local one short AGAIN — the deficit persists for the family's lifetime). */
        int cutFailureDebt;
        final ArrayList<ShipAPI> pieces = new ArrayList<ShipAPI>();
        FractureFamily(long rootEntityId, long mintTick) {
            this.rootEntityId = rootEntityId;
            this.mintTick = mintTick;
        }
    }

    /** One {@code HKFR} edge queued behind its family bind (or the head latched for its one
     * null-cut retry). */
    private static final class ParkedFractureEdge {
        final cc.starsector.client.net.ClientHulkFractureRecord record;
        final long parkedNanos;
        boolean cutRetried;
        ParkedFractureEdge(cc.starsector.client.net.ClientHulkFractureRecord record,
                long parkedNanos) {
            this.record = record;
            this.parkedNanos = parkedNanos;
        }
    }

    /** net-thread intake of decoded {@code HKFR} fracture edges → render-thread apply.
     * The {@code edgeEventQueue} net→render idiom (plain deque + synchronized — loader-B law, no
     * new j.u.c; the SNMR copy-on-write idiom does not fit a two-writer drain queue). Bounded:
     * edges are rare (a few per corpse per match); overflow drops oldest + counter, never throws
     * into the net loop. */
    private final ArrayDeque<cc.starsector.client.net.ClientHulkFractureRecord> fractureIntake =
            new ArrayDeque<cc.starsector.client.net.ClientHulkFractureRecord>();
    private static final int FRACTURE_INTAKE_MAX = 256;
    /** ~2 s park before an edge whose family never bound is dropped — the cross-lane
     * death/fracture arrival-race buffer (EDSP rides the tick composition, HKFR the CTRL spool).
     * A late-join edge for a never-presented corpse correctly dies here. */
    private static final long FRACTURE_PARK_EXPIRY_NANOS = 2_000_000_000L;
    /** Family↔wire bind tolerance: the mint tick trails the wire deathTick by ticks
     * (EDSP 9668 vs first cut 9682 measured); min same-rootId family separation ≈300 (the
     * respawn cooldown), so ±150 can never claim the wrong family. */
    private static final long FRACTURE_BIND_TOLERANCE_TICKS = 150L;
    private static final int FRACTURE_LOG_MAX = 20;
    private static final Color FRACTURE_EXPLOSION_COLOR = new Color(255, 140, 60, 255);
    /** The vanilla {@code ExplosionParticleSystem} terminal's color — the kind-3
     * vaporize replay uses the engine's own explosion look, not the kind-2 mini-fireball. */
    private static final Color VAPORIZE_EXPLOSION_COLOR = new Color(255, 165, 100, 255);
    /** Vanilla plays the break SFX at a MID-LIFE overkill break; a kill-time split
     * (≤20 ticks post-death observed) rides the client's native death SFX — edges past
     * this window sound, earlier ones stay silent. */
    private static final long BREAK_SFX_MIN_TICKS_PAST_DEATH = 60L;
    private static final long BREAK_SFX_DEBOUNCE_TICKS = 120L;
    private static final int FRACTURE_APPLY_OK = 0;
    private static final int FRACTURE_APPLY_RETRY = 1;
    private static final int FRACTURE_APPLY_DROP = 2;

    /** Render-thread family state: rootId → this round's live corpse families for that wire id
     * (usually one; a respawn-cooldown remount can front two — mintTick separation ≈300). */
    private final java.util.HashMap<Long, ArrayList<FractureFamily>> fractureFamilies =
            new java.util.HashMap<Long, ArrayList<FractureFamily>>();
    /** Wire-id → EDSP despawn simTick, fed for EVERY consumed despawn in
     * {@code PredictReconcileCombatPlugin#onDespawnEvent} via {@link #noteDespawnTick}
     * (net-thread put, render-thread remove-take at mint under the same
     * lock — the fractureIntake idiom, loader-B-clean). The mint's death-tick source for
     * remote/foe corpses; own deaths prefer the exact
     * {@code PredictReconcileCombatPlugin#ownDespawnTick}. A DEPARTED
     * own removal mints NOTHING (no corpse — {@code killOwn} returns before the mint). */
    private final java.util.HashMap<Long, Long> despawnTickByEntity =
            new java.util.HashMap<Long, Long>();
    /** Per-wire-family FIFO of edges not yet applied (family unbound, or head latched for its
     * one null-cut retry) — keyed {@code rootId + ":" + deathTick} (the EXACT wire identity);
     * order within a family is the wire order. */
    private final java.util.HashMap<String, ArrayDeque<ParkedFractureEdge>> parkedFractureEdges =
            new java.util.HashMap<String, ArrayDeque<ParkedFractureEdge>>();
    private long fractureIntakeDropped;
    private long fractureAddsApplied;

    /** Sticky per-rootId ROOT_DETACHED latch (render-thread only) — fed at
     * edge intake in {@link #maintainFractureEdges}, asserted per frame in
     * {@link #assertModuleDetach}. Sticky by design: the engine re-glues a slotted module to
     * its parent every frame while {@code getStationSlot()!=null}, and the edge can arrive
     * before the local module death (client lag) — the release must keep asserting until the
     * body reads hulk, and must survive any late SABL bind/repair. Entries never leave inside
     * a round (a handful of station modules at most). */
    private final java.util.HashSet<Long> detachedRoots = new java.util.HashSet<Long>();
    /** Meters: kind-4 edges consumed at intake / slot releases actually applied. */
    private long moduleDetachEdges;
    private long moduleDetachApplied;
    private long fractureRemovesApplied;
    private long fractureRemovesAlreadyApplied;
    private long fractureRemovesUnderDebt;
    private long fractureRemovesViolent;
    private long fractureBreakSfx;
    private long fractureCutFailures;
    private long fractureRankMisses;
    private long fractureParkExpired;
    private long fractureCountMismatches;
    private long fractureApplyFailures;
    private int fractureLogLines;

    /** The {@code HKPS} corpse-pose keyframe lane, compiled APPLY
     * (E3 hardwire; the {@code cc.predict.hulkPose} apply|off
     * knob-resolved shape is in git): the pose adopt is the F4 fix itself; hulk drift is
     * deterministic coast, so one exact adoption holds between keyframes. No {@code meter}
     * tier — a keyframe is already the measurement; the server rollback lever stays
     * {@code hulk.poseEveryTicks=0}. */
    static final String HULK_POSE_MODE = "apply";
    static final boolean HULK_POSE_APPLY = true;

    /** The corpse COAST knob, {@code cc.predict.hulkPoseCoast} = {@code on|off}
     * (default {@code on}: between HKPS keyframes every keyframe-adopted wire piece is
     * kinematically SERVER-owned — {@link #assertPoseCoast} re-asserts the last adopted
     * keyframe's ballistic pose (pos/facing/vel/angVel) every render frame, so client-LOCAL
     * collision impulses on corpse pieces are wiped within one frame and the 2 Hz adoption
     * becomes a no-op ({@link #HULK_YIELD} amends the wipe: a real collision response is
     * ADOPTED into the coast instead — the measured server yields the same way). Measured basis: server truth is near-pure coast (stable-pair
     * residual p99≈0.01u, 9 genuine impulses in 11.3 min / 32 pieces) — the felt rubberband was
     * the one-shot 4-component snap fighting local collision physics, NOT the update rate. The
     * live ship's own engine-computed bounce off a piece is untouched (its impulse is computed
     * with real masses before the piece's response is wiped — the risk check); a
     * non-yielding piece IS the measured server behavior. The coast is
     * TICK-ANCHORED — presented on the AWSS lane's presented-server-tick clock, not integrated
     * in game time (which starves at fps&lt;30 and produced the 1277 sawtooth). {@code off} =
     * one-shot adoption only (the pre-coast behavior, the rollback lever — it also drops
     * the tick anchor). Compiled ON (E3 hardwire; the
     * {@code cc.predict.hulkPoseCoast} knob-resolved shape is in git). */
    static final boolean HULK_POSE_COAST = true;

    /** The corpse YIELD knob, {@code cc.predict.hulkYield} = {@code on|off}
     * (default {@code on}: inside {@link #assertPoseCoast}, a real local collision RESPONSE —
     * engine post-physics piece velocity stepping off the asserted coast by
     * >{@link #YIELD_TOL_US} (or angVel by >{@link #YIELD_ANG_TOL_DEG_S}) — is ADOPTED into
     * the coast (pos/facing/vel/angVel re-seed) instead of wiped. Measured basis (the yield census):
     * server hulks DO yield to ship contact — all 30 impulses >5 u/s sat at
     * contact range 107–311u (6.8–188 u/s) while 1883 far-field pairs coasted EXACTLY
     * (velDelta max 0.00) — so both sides run the same contact physics and the 2 Hz keyframe
     * corrects only their small disagreement; a sustained lean re-seeds frame after frame
     * (the census's impulse trains). {@code off} = the jar-window-#8 non-yielding wall back
     * (the rollback lever). Consulted only under {@code hulkPoseCoast=on}. Compiled ON
     * (E3 hardwire; the {@code cc.predict.hulkYield}
     * knob-resolved shape is in git). */
    static final boolean HULK_YIELD = true;

    /** The contact-GLIDE knob, {@code cc.predict.hulkContactGlide} =
     * {@code on|off} (default {@code on}: a post-CONTACT correction adopt — the keyframe
     * velocity stepping off the presented velocity, a yield-seeded coast, or an in-flight
     * glide — presents as a ~{@link #CONTACT_GLIDE_SECONDS}-s decaying offset instead of a
     * hard set; the ram-blip was exactly that hard correction landing up to 500 ms after
     * the local engine's yield guess. Teleport-class (≥{@link #POSE_LARGE_SNAP_UNITS}) and
     * sub-visible (≤{@link #CONTACT_GLIDE_MIN_U}) corrections stay hard. {@code off} = every
     * adopt hard-sets (the hard-set behavior back, the rollback lever). Consulted only under
     * {@code hulkPoseCoast=on} — the glide is presented by the coast assert. Compiled ON
     * (E3 hardwire; the {@code cc.predict.hulkContactGlide}
     * knob-resolved shape is in git). */
    static final boolean HULK_CONTACT_GLIDE = true;

    /** The hulk KEEP-ALIVE knob, {@code cc.predict.hulkKeepAlive} =
     * {@code on|off} (default {@code on}: the keep-alive IS the family-churn fix — the
     * engine's ~30 s off-camera hulk fade removes pieces the authoritative server owns;
     * {@code off} = vanilla fade back, the rollback lever). Compiled ON (E3 hardwire;
     * the {@code cc.predict.hulkKeepAlive} knob-resolved shape
     * is in git). */
    static final boolean HULK_KEEP_ALIVE = true;

    /** The hulk ENGINE-QUIET knob,
     * {@code cc.predict.hulkEngineQuiet} = {@code on|off} (default {@code on}: replica
     * corpses/pieces get the vanilla dead-corpse engine contract — permanently disabled via
     * {@link HulkEngineQuiet}, so local engine-area hits cannot flame out a hulk;
     * {@code off} = vanilla-quirk live piece controllers back, the rollback lever). Compiled
     * ON (E3 hardwire; the {@code cc.predict.hulkEngineQuiet}
     * knob-resolved shape is in git). */
    static final boolean HULK_ENGINE_QUIET = true;

    /** The hulk OVERKILL-PIN knob (hulk hit-FX),
     * {@code cc.predict.hulkOverkillPin} = {@code on|off} (default {@code on}: wire pieces are
     * UN-vetoed and their overkill accumulator pinned to the engine's own {@code -1e8}
     * suppressor idiom ({@link HulkOverkillPin}) — local hits regain vanilla's
     * applied-damage-keyed per-hit sound/debris while the break machine stays closed;
     * {@code off} = the blanket veto back, the rollback lever — the "wimpy" hits
     * return). Compiled ON (E3 hardwire; the
     * {@code cc.predict.hulkOverkillPin} knob-resolved shape is in git). */
    static final boolean HULK_OVERKILL_PIN = true;

    /** The {@code HKCG} CUT-GEOMETRY knob,
     * {@code cc.predict.hulkCutGeometry} = {@code on|off} (default {@code on}: a warm-latched
     * PIECE_ADDED replays the SERVER's exact cut — the four carried result rings rebuild the
     * two tesselator carve-feed bags and drive the engine's own public two-arg
     * {@code splitShip(bag1, bag2)}, so hulks split into the SAME shapes on both windows; any miss
     * falls back to today's local RNG cut; {@code off} = intake ignored, the rollback lever).
     * Compiled ON (E3 hardwire, ADJ-F co-adopt with the server
     * twin {@code cc.net.hulkCutGeometry} — twins decide together; the
     * {@code cc.predict.hulkCutGeometry} knob-resolved shape is in git). */
    static final boolean HULK_CUT_GEOMETRY = true;

    /** net-thread intake of decoded {@code HKPS} corpse-pose keyframes →
     * render-thread one-shot exact adoption (the {@link #fractureIntake} idiom — bounded,
     * overflow drops oldest + counter, never throws into the net loop). */
    private final ArrayDeque<cc.starsector.client.net.ClientHulkPoseRecord> poseIntake =
            new ArrayDeque<cc.starsector.client.net.ClientHulkPoseRecord>();
    private static final int POSE_INTAKE_MAX = 256;
    /** A pose adoption moving a piece farther than this (u) counts {@link #poseLargeSnaps}
     * — the popping-visibility meter (the named fallback, dead-reckoned glide, builds only if
     * the re-watch shows visible popping). */
    private static final float POSE_LARGE_SNAP_UNITS = 50f;

    /** One piece's server-owned ballistic state — seeded at each keyframe
     * adoption, asserted every render frame by {@link #assertPoseCoast}. A piece
     * with no entry (pre-first-keyframe, or post-edge until the next cadence keyframe) stays
     * engine-local — fail-soft, ≤0.5 s. {@code x/y/facing} = the keyframe BASE
     * pose, immutable between adopts under the tick anchor ({@code anchorTick} = the keyframe's
     * server simTick); {@link Long#MIN_VALUE} = engine-seeded by a yield — no keyframe base,
     * so the assert falls back to the pre-1277 game-time integral (which then DOES mutate
     * {@code x/y/facing}) until the next adopt re-anchors. */
    private static final class PoseCoast {
        float x, y, facing, velX, velY, angVel;
        long anchorTick = Long.MIN_VALUE;
        // post-contact correction glide — a presentation-only offset from
        // the led adopt target back to the pre-adopt presented pose, decayed linearly to zero
        // over CONTACT_GLIDE_SECONDS on the presented-tick clock (freezes with the stream,
        // like the coast itself); glideStartTick MIN_VALUE = no glide in flight.
        float glideOffX, glideOffY, glideOffFacing;
        long glideStartTick = Long.MIN_VALUE;
    }

    /** Identity-keyed per piece (render-thread only). Entries leave with their piece: the
     * prune loops, REMOVE applies, family close-out, edge-apply family clears, match reset. */
    private final java.util.IdentityHashMap<ShipAPI, PoseCoast> poseCoast =
            new java.util.IdentityHashMap<ShipAPI, PoseCoast>();
    private long poseCoastAssertFailures;

    /** Yield threshold sizing: contact impulses measured ≥6.8 u/s against a ~0 coast
     * noise floor (one 2–5 u/s pair in 2120) — any 1–5 u/s works, ~2 taken; detect on
     * RESPONSE (velocity deviation), never proximity (two census pairs sat <100u with
     * velDelta 0). */
    private static final float YIELD_TOL_US = 2f;
    /** Angular belt: every angVel outlier >5°/s was one of the same contact events
     * (drift noise p99 3.2°/s per 30-tick pair, ≈0 per frame under re-assert) — catches a
     * glancing spin-dominant contact the linear trigger might miss. */
    private static final float YIELD_ANG_TOL_DEG_S = 5f;
    /** Yield meters: adoption count + the largest linear deviation adopted (u/s) — the
     * re-watch grade pins yields>0 at contact scenes, 0 far-field. */
    private long poseCoastYields;
    private float poseCoastYieldMaxUs;
    /** Law meters: per-piece-frame counts of tick-anchored asserts vs game-time
     * fallback asserts (yield windows + no-presented-tick). Grade expectation under apply:
     * tick ≈ all, gametime ≈ small (yield windows only) — gametime dominating means the
     * anchor never arms and the 1277 starvation class is still live. */
    private long poseCoastTickAsserts;
    private long poseCoastGameTimeAsserts;
    /** Meters: pose writes skipped because the piece is still SLOTTED to
     * its parent station — the engine's native per-frame slot re-glue owns that pose (vanilla
     * "stuck to the spinning station"); HKPS asserting its linear coast on top was the
     * dead-big-module jiggle (two-writer fight). Grade expectation: >0 at a fortress with dead
     * big modules, jiggle gone; small (natively-detaching) modules unaffected. */
    private long poseSlottedSkips;

    /** contact-glide sizing: ~one server follow-burst gap (the design target
     * "~100–150 ms") so successive burst corrections chain into one smooth curve; the vel
     * signature threshold reuses the yield census sizing (real contact impulses measured
     * ≥6.8 u/s against a ~0 coast noise floor); corrections ≤{@link #CONTACT_GLIDE_MIN_U}
     * are invisible — hard-set them rather than dilute the glide meter. */
    private static final float CONTACT_GLIDE_SECONDS = 0.125f;
    private static final float CONTACT_GLIDE_VEL_US = 2f;
    private static final float CONTACT_GLIDE_MIN_U = 0.5f;
    /** Meter: contact-correction adopts presented via glide (vs hard-set). */
    private long poseContactGlides;

    // per-apply adoption-delta histograms — the amplitude the eye sees at each
    // 2 Hz adoption (the position-only >50u pose_large_snap meter was blind to the felt
    // artifact: sub-50u position snaps + unmetered velocity/facing snaps). Counted on EVERY
    // apply regardless of the coast knob; with coast ON the deltas grade the fix directly
    // (expected ≈ the server-coast residual, p99≈0.01u). Buckets:
    // pos(u) <0.1/<1/<5/<15/<50/≥50; vel(u/s) <1/<5/<20/≥20; facing(deg) <0.5/<2/<10/≥10.
    private final long[] poseDeltaPosHist = new long[6];
    private final long[] poseDeltaVelHist = new long[4];
    private final long[] poseDeltaFacingHist = new long[4];

    private long poseIntakeDropped;
    private long poseApplied;
    private long poseDroppedUnbound;
    private long poseCountMismatches;
    private long poseRankMisses;
    private long poseLargeSnaps;

    // Per-adoption telemetry — capped, ALWAYS-ON (no knob; the
    // diagnosis verdict: static RE exhausted, this line decides the damped-periodic hulk-teleport
    // question). A line fires only on an adoption the eye could see (posU>5 or velUs>5); the
    // oscillation discriminators ride the lines themselves (paired A↔B deltas at successive
    // keyframes ⇒ rank-tie swap; minDist ⇒ contact-band; near-bit ⇒ on-camera).
    private static final int POSE_ADOPT_LOG_MAX = 150;
    private long poseAdoptBig;      // qualifying adoptions (posU>5 || velUs>5) — uncapped
    private int poseAdoptLogLines;  // lines actually emitted (≤ POSE_ADOPT_LOG_MAX)
    private final HulkAdoptRing poseAdoptRing = new HulkAdoptRing(); // Every big adopt, flushed at F9 + teardown

    /** keep-alive counters: {@code keepalive_disabled} = pin calls that could not pin
     * (adapter disabled — the tripwire; ~0 when healthy), {@code fade_heals} = mid-fade
     * pieces healed back to full visibility (>0 = the fade DID start on a wire piece and the
     * heal caught it). */
    private long keepAliveDisabled;
    private long fadeHeals;
    /** engine-quiet counter: corpses/pieces whose engines were pinned to the vanilla
     * dead-corpse contract at creation (root mint + ADD cut). */
    private long engineQuiets;
    /** overkill-pin counter: creation-edge pins only (root mint + ADD cut both halves) —
     * the per-sweep belt re-pins are deliberately uncounted. */
    private long overkillPins;

    /** net-thread intake of decoded {@code HKCG} cut-geometry records →
     * render-thread latch (the {@link #fractureIntake} idiom — bounded, overflow drops oldest
     * + counter, never throws into the net loop). Knob-off ignores at intake. */
    private final ArrayDeque<cc.starsector.client.net.ClientHulkCutGeometryRecord>
            cutGeometryIntake =
                    new ArrayDeque<cc.starsector.client.net.ClientHulkCutGeometryRecord>();
    private static final int CUT_GEOMETRY_INTAKE_MAX = 256;

    /** One cut's latched geometry set — the four {@code pieceSlot × ringKind} records the
     * server fans BEFORE the paired {@code HKFR} PIECE_ADDED on the same strict-FIFO ctrl lane
     * (parent-exact / parent-visual / clone-exact / clone-visual). Warm = all four
     * present; a partial latch stays cold and the ADD falls back (capture-skip cuts fan NO
     * records — a cold latch on an ADD is normal, not a counter). */
    private static final class CutGeometryLatch {
        final cc.starsector.client.net.ClientHulkCutGeometryRecord[] records =
                new cc.starsector.client.net.ClientHulkCutGeometryRecord[4];
        final long latchedNanos;
        CutGeometryLatch(long latchedNanos) {
            this.latchedNanos = latchedNanos;
        }
        boolean warm() {
            return records[0] != null && records[1] != null
                    && records[2] != null && records[3] != null;
        }
    }

    /** Latched cut geometry keyed {@code rootId:deathTick:cutSeq} (the exact HKFR/HKPS family
     * identity + the per-family ADD ordinal). Render-thread only. */
    private final java.util.HashMap<String, CutGeometryLatch> cutGeometryLatches =
            new java.util.HashMap<String, CutGeometryLatch>();
    private static final int CUT_GEOMETRY_LATCH_MAX = 64;
    /** ~10 s latch TTL — generous vs the ~2 s edge park: a latch whose ADD never
     * terminally consumed it (family never bound, edge expired) must not pin memory. */
    private static final long CUT_GEOMETRY_LATCH_EXPIRY_NANOS = 10_000_000_000L;
    /** Parent-pick tolerance (u, per bbox axis): the carried union bbox equals the
     * local parent's own pre-split bbox up to wire quantization (≤1/128 u per coord) + engine
     * float dust — 2 u is orders looser than the error and orders tighter than any two
     * distinct pieces' dimensions. */
    private static final float CUT_GEOMETRY_BBOX_TOL = 2f;

    /** HKCG counters: {@code hkcg_applied} = server-exact cuts replayed through the
     * engine's two-arg carve; {@code hkcg_fallback} = warm latch whose apply missed (adapter
     * disabled / engine null/throw) ⇒ local RNG cut; {@code hkcg_latch_expired} = TTL/overflow
     * evictions; {@code hkcg_parent_mismatch} = warm latch whose carried bbox matched no local
     * piece ⇒ local cut. */
    private long hkcgApplied;
    private long hkcgFallback;
    private long hkcgLatchExpired;
    private long hkcgParentMismatch;
    private long cutGeometryIntakeDropped;

    // ---- the omega HULK-DISSOLVE mirror (client-only) ----

    /** The vanilla {@code ShardSpawner} fade-out mirror.
     * Knob {@code cc.predict.omegaDissolve} = {@code on|off} (default {@code on});
     * {@code off} = today's path byte-for-byte (every omega REMOVE takes the fireball) = the A/B
     * lever. Property idiom copied from {@link RecallFxLane}'s {@code cc.predict.recallFx}. */
    static final boolean OMEGA_DISSOLVE =
            parseOmegaDissolveKnob(System.getProperty("cc.predict.omegaDissolve", "on"));
    /** Vanilla fade length ({@code ShardSpawner.SPAWN_TIME} = the fade-out plugin's duration). */
    private static final float OMEGA_FADE_SEC = 4.0f;
    /** REMOVE-late tolerance past the fade end before the honest VISIBLE restore (never-conceal
     * law: a dissolving body whose wire REMOVE never comes is restored, never a hidden ghost). */
    private static final float OMEGA_HOLD_SEC = 1.5f;
    /** Attribution radius (× parent R): a split child spawns at {@code parent.loc + 0.1R}
     * and binds AT its first wire pose. */
    private static final float OMEGA_ATTRIBUTION_RADII = 1.5f;
    /** Vanilla jitter colour ({@code ShardSpawner.JITTER_COLOR}). */
    private static final Color OMEGA_JITTER_COLOR = new Color(100, 100, 255, 50);
    /** The hullmod id every omega hull carries as a hull-spec BUILTIN ({@code SpawnHullmodGate}
     * suppresses it at the VARIANT level only — the spec-level read survives). */
    private static final String OMEGA_SHARD_SPAWNER_ID = "shard_spawner";

    /** One running parent fade — the client half of vanilla's fade-out plugin state
     * ({@code elapsed}, {@code interval}) plus the collision class to restore on an
     * overrun or a failed write. */
    private static final class OmegaDissolve {
        float elapsed;
        final CollisionClass restoreClass;
        final IntervalUtil puffInterval = new IntervalUtil(0.075f, 0.125f);
        OmegaDissolve(CollisionClass restoreClass) {
            this.restoreClass = restoreClass;
        }
    }

    /** The dissolving omega parents (identity-keyed; each STAYS in its family's
     * {@code pieces} — keep-alive pin, overkill veto, keyframes/coast, counts all untouched).
     * Every entry ends in the wire REMOVE edge ({@link #applyFractureEdge} REMOVE branch, the
     * ONLY remover) or an explicit visible restore ({@link #advanceOmegaDissolves}). */
    private final IdentityHashMap<ShipAPI, OmegaDissolve> dissolving =
            new IdentityHashMap<ShipAPI, OmegaDissolve>();
    private long omegaBegun;
    private long omegaEnded;
    private long omegaOverruns;
    private long omegaUnattributed;
    private long omegaUndressed;
    /** REMOVE edges a reclamation dissolve made silent. */
    private long reclaimEnded;

    // ---- the omega shard FADE-IN half (client-only) ----

    /** Vanilla's own modifier id, so the replica's hull-damage-taken
     *  tstat reads exactly like the server body's. */
    private static final String SHARD_INVULN_MOD_ID = "ShardSpawnerInvuln";

    /** One running shard fade-in — the client half of vanilla's {@code ShardFadeInPlugin} state
     *  ({@code elapsed}, the captured {@code collisionClass}) plus the wire-truth
     *  baselines. Pooled: a slot is free while {@code ship == null} and is reused by the next
     *  begin, so a running window never allocates. */
    private static final class ShardFadeIn {
        ShipAPI ship;
        PredictReconcileCombatPlugin.RemoteEntity row;
        CollisionClass restoreClass;
        float elapsed;
        boolean lifted;
        float hullBaseline;
        float armorBaseline;
        long armorTick;

        void arm(ShipAPI ship, PredictReconcileCombatPlugin.RemoteEntity row, CollisionClass restoreClass) {
            this.ship = ship;
            this.row = row;
            this.restoreClass = restoreClass;
            elapsed = 0f;
            lifted = false;
            hullBaseline = Float.NaN;
            armorBaseline = Float.NaN;
            armorTick = Long.MIN_VALUE;
        }

        void free() {
            ship = null;
            row = null;
            restoreClass = null;
        }
    }

    /** The fade-in pool (grows to the high-water mark of concurrent shards, ~12 per capital split;
     *  never shrinks, cleared slot-wise at the match reset). */
    private final ArrayList<ShardFadeIn> fadeIns = new ArrayList<ShardFadeIn>();
    private int fadeInsActive;
    private long omegaFadeInBegun;
    private long omegaFadeInLifted;
    private long omegaFadeInEarlyEnd;
    private long omegaFadeInWireEnd; // the early_end share the wire's own drop ended (vs REMOVE / kill)
    private long omegaFadeInSibling;
    private long omegaFadeInThrows;
    /** The shard-WING hull ids, resolved ONCE from vanilla's
     *  own {@code ShardSpawner.variantData} {@code _wing} entries ({@link #isShardWingFighter};
     *  {@code null} = not read yet; EMPTY for this instance's life after a table-read throw — fail-closed,
     *  the omega SHIP branch never reads it). */
    private Set<String> shardWingHulls;
    private long omegaFadeInNotShard; // attributed appended fighters that are neither omega nor shard-wing hulls

    /** A COPY of the live pieces of the wire family {@code piece} belongs to
     * (empty = not one of ours) — the reclamation lane's piece law (vanilla's
     * reclamation-script rule, on the wire's truth). Identity scan like {@link #isWirePiece}. */
    ArrayList<ShipAPI> familyPiecesOf(ShipAPI piece) {
        ArrayList<ShipAPI> out = new ArrayList<ShipAPI>();
        if (piece == null) {
            return out;
        }
        for (ArrayList<FractureFamily> list : fractureFamilies.values()) {
            for (int i = 0; i < list.size(); i++) {
                ArrayList<ShipAPI> pieces = list.get(i).pieces;
                for (int j = 0; j < pieces.size(); j++) {
                    if (pieces.get(j) == piece) {
                        out.addAll(pieces);
                        return out;
                    }
                }
            }
        }
        return out;
    }

    private static boolean parseOmegaDissolveKnob(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(v)) {
            return true;
        }
        if ("off".equals(v)) {
            return false;
        }
        throw new IllegalArgumentException("cc.predict.omegaDissolve must be on|off, got: " + raw);
    }

    /** net-thread intake of one {@code HKFR} fracture edge (fan-only; no catch-up). */
    void onHulkFractureEvent(cc.starsector.client.net.ClientHulkFractureRecord record) {
        synchronized (fractureIntake) {
            if (fractureIntake.size() >= FRACTURE_INTAKE_MAX) {
                fractureIntake.pollFirst();
                fractureIntakeDropped++;
            }
            fractureIntake.addLast(record);
        }
    }

    /** net-thread intake of one {@code HKPS} corpse-pose keyframe (fan-only; no
     * catch-up — the periodic cadence self-heals). Knob-off ignores at intake: counters stay
     * untouched, nothing queues (the lesser-overload count-ignore lives in the session). */
    void onHulkPoseEvent(cc.starsector.client.net.ClientHulkPoseRecord record) {
        if (!HULK_POSE_APPLY) {
            return;
        }
        synchronized (poseIntake) {
            if (poseIntake.size() >= POSE_INTAKE_MAX) {
                poseIntake.pollFirst();
                poseIntakeDropped++;
            }
            poseIntake.addLast(record);
        }
    }

    /** net-thread intake of one {@code HKCG} cut-geometry record (fan-only;
     * no catch-up — a missed cut simply falls back to the local RNG cut at its ADD). Knob-off
     * ignores at intake: counters stay untouched, nothing queues (the {@link #onHulkPoseEvent}
     * idiom; the lesser-overload count-ignore lives in the session). */
    void onHulkCutGeometryEvent(cc.starsector.client.net.ClientHulkCutGeometryRecord record) {
        if (!HULK_CUT_GEOMETRY) {
            return;
        }
        synchronized (cutGeometryIntake) {
            if (cutGeometryIntake.size() >= CUT_GEOMETRY_INTAKE_MAX) {
                cutGeometryIntake.pollFirst();
                cutGeometryIntakeDropped++;
            }
            cutGeometryIntake.addLast(record);
        }
    }

    /** Every consumed despawn feeds the fracture mint's death-tick source
     * (net-thread put; render-thread remove-take in {@link #mintFractureFamily}, same lock). */
    void noteDespawnTick(long entityId, long simTick) {
        synchronized (despawnTickByEntity) {
            despawnTickByEntity.put(Long.valueOf(entityId), Long.valueOf(simTick));
        }
    }

    /** Capped {@code predict hulk-split:} log line (edges are rare, but a pathological stream
     * must not spam the watch log). */
    private void logFracture(String line) {
        if (fractureLogLines < FRACTURE_LOG_MAX) {
            fractureLogLines++;
            PilotWatchLog.info("predict hulk-split: " + line
                    + (fractureLogLines == FRACTURE_LOG_MAX ? " (log cap reached)" : ""));
        }
    }

    private static String fractureFamilyKey(long rootEntityId, long deathTick) {
        return rootEntityId + ":" + deathTick;
    }

    /** The observability payment: ONE un-capped cumulative counters line at the round wrap +
     * each family close-out (the capped per-edge lines can be silent while counters still move —
     * exactly what once left a run's triage blind). On
     * {@code where == "teardown"} it also flushes the hulk-pose adopt ring
     * ({@link #dumpAdoptRing}) — the fix for adopt rows lost past the live cap. */
    void logFractureTotals(String where) { // package-private: PRCP teardown flushes it
        PilotWatchLog.info("predict hulk-split totals: [" + where + "] adds=" + fractureAddsApplied
                + " removes=" + fractureRemovesApplied
                + " removes_already_applied=" + fractureRemovesAlreadyApplied
                + " removes_under_debt=" + fractureRemovesUnderDebt
                + " removes_violent=" + fractureRemovesViolent
                + " break_sfx=" + fractureBreakSfx + " cut_failures=" + fractureCutFailures
                + " rank_misses=" + fractureRankMisses + " park_expired=" + fractureParkExpired
                + " count_mismatches=" + fractureCountMismatches
                + " apply_failures=" + fractureApplyFailures
                + " intake_dropped=" + fractureIntakeDropped
                + " pose_applied=" + poseApplied
                + " pose_dropped_unbound=" + poseDroppedUnbound
                + " pose_count_mismatch=" + poseCountMismatches
                + " pose_rank_miss=" + poseRankMisses
                + " pose_large_snap=" + poseLargeSnaps
                + " pose_intake_dropped=" + poseIntakeDropped
                + " keepalive_disabled=" + keepAliveDisabled
                + " fade_heals=" + fadeHeals
                + " engine_quiets=" + engineQuiets
                + " overkill_pins=" + overkillPins
                + " hkcg_applied=" + hkcgApplied
                + " hkcg_fallback=" + hkcgFallback
                + " hkcg_latch_expired=" + hkcgLatchExpired
                + " hkcg_parent_mismatch=" + hkcgParentMismatch
                + " hkcg_intake_dropped=" + cutGeometryIntakeDropped
                + " module_detach_edges=" + moduleDetachEdges
                + " module_detach=" + moduleDetachApplied
                + " omega_begun=" + omegaBegun + " omega_ended=" + omegaEnded
                + " omega_overruns=" + omegaOverruns + " omega_unattributed=" + omegaUnattributed
                + " reclaim_ended=" + reclaimEnded
                + " omega_undressed=" + omegaUndressed // fade-in meters
                + " omega_fadein_begun=" + omegaFadeInBegun + " omega_fadein_lifted=" + omegaFadeInLifted
                + " omega_fadein_early_end=" + omegaFadeInEarlyEnd
                + " omega_fadein_wire_end=" + omegaFadeInWireEnd
                + " omega_fadein_sibling=" + omegaFadeInSibling
                + " omega_fadein_not_shard=" + omegaFadeInNotShard
                + " omega_fadein_throws=" + omegaFadeInThrows);
        // The adoption-delta histograms ride every totals emit (round wrap +
        // family close-outs) — the fix's re-watch grades on these lines directly.
        PilotWatchLog.info("predict hulk-pose delta-hist: [" + where + "]"
                + " pos_u=" + histString(poseDeltaPosHist)
                + " vel_us=" + histString(poseDeltaVelHist)
                + " facing_deg=" + histString(poseDeltaFacingHist)
                + " applies=" + poseApplied
                + " coast=" + (HULK_POSE_COAST ? "on" : "off")
                + " coast_assert_failures=" + poseCoastAssertFailures
                + " yield=" + (HULK_YIELD ? "on" : "off")
                + " coast_yields=" + poseCoastYields
                + " coast_yield_max_us=" + String.format(java.util.Locale.ROOT, "%.1f",
                        poseCoastYieldMaxUs)
                // Law meters — tick-anchored vs game-time-fallback asserts.
                + " coast_tick_asserts=" + poseCoastTickAsserts
                + " coast_gametime_asserts=" + poseCoastGameTimeAsserts
                // still-slotted pieces left to the native slot re-glue.
                + " slotted_skips=" + poseSlottedSkips
                // LARGE adoptions (posU>5 || velUs>5) + lines emitted (≤ cap).
                + " adopt_big=" + poseAdoptBig
                + " adopt_lines=" + poseAdoptLogLines
                // contact-correction adopts presented via glide.
                + " contact_glides=" + poseContactGlides);
        // Teardown flushes the adopt ring so a run
        // whose live adopt cap ran out still ends with its newest big adopts on record.
        if ("teardown".equals(where)) {
            dumpAdoptRing("teardown", 0L, plugin.lastSnapshotSimTick);
        }
    }

    /** One capped {@code predict hulk-pose adopt:} line per LARGE adoption
     * (posU&gt;5 or velUs&gt;5): snapshot tick + family identity (root/death/rank) + the deltas +
     * minDist to the nearest live (non-hulk) ship + the engine-cull near-bit
     * ({@code isNearViewport(loc, r*1.25+50)} — the engine's own hulk-cull predicate, the
     * {@link #presentVaporize} viewport idiom). Fail-soft: a discriminator read that throws
     * logs {@code n/a}, never costs the adoption (already landed).
     *
     * <p>EVERY big adopt is now also recorded into
     * {@link #poseAdoptRing} (flushed at an owner F9 marker and at teardown via
     * {@link #dumpAdoptRing}) — on one recorded flight the 150-line cap ran out at tick ~11219 and F9 #3
     * at tick 47154 had no adopt row. The capped live line is byte-identical to before. Cost: the
     * discriminator read now runs BEFORE the cap check, i.e. O(live ships) per BIG adopt (~360 per
     * flight), render-thread, no lock (applyPoseKeyframe, the F9 hook and teardown all run
     * on the render thread). */
    private void logPoseAdopt(cc.starsector.client.net.ClientHulkPoseRecord record,
            ShipAPI piece, float posU, float velUs, float dFacing, boolean glided) {
        if (posU <= 5f && velUs <= 5f) {
            return;
        }
        poseAdoptBig++;
        String minDist = "n/a";
        String nearBit = "n/a";
        float minDistF = Float.NaN;
        int nearI = -1;
        try {
            Vector2f loc = piece.getLocation();
            float best = Float.MAX_VALUE;
            for (ShipAPI s : plugin.engine.getShips()) {
                if (s == null || s == piece || s.isHulk() || !s.isAlive()) {
                    continue; // live ships only — the contact-band discriminator's population
                }
                Vector2f sl = s.getLocation();
                float d = (float) Math.hypot(sl.x - loc.x, sl.y - loc.y);
                if (d < best) {
                    best = d;
                }
            }
            if (best < Float.MAX_VALUE) {
                minDist = String.format(java.util.Locale.ROOT, "%.0f", best);
                minDistF = best;
            }
            com.fs.starfarer.api.combat.ViewportAPI viewport = plugin.engine.getViewport();
            if (viewport != null) {
                nearI = viewport.isNearViewport(loc,
                        piece.getCollisionRadius() * 1.25f + 50f) ? 1 : 0;
                nearBit = nearI == 1 ? "1" : "0";
            }
        } catch (Throwable ignore) {
            // discriminators are best-effort; the adoption itself already landed
        }
        long dtick = plugin.lastSnapshotSimTick == Long.MIN_VALUE ? Long.MIN_VALUE
                : plugin.lastSnapshotSimTick - record.simTick();
        poseAdoptRing.record(plugin.lastSnapshotSimTick, record.rootEntityId(), record.deathTick(),
                record.sizeRank(), posU, velUs, dFacing, minDistF, nearI, dtick, glided);
        if (poseAdoptLogLines >= POSE_ADOPT_LOG_MAX) {
            return;
        }
        poseAdoptLogLines++;
        PilotWatchLog.info("predict hulk-pose adopt: tick=" + plugin.lastSnapshotSimTick
                + " root=" + record.rootEntityId() + " death=" + record.deathTick()
                + " rank=" + record.sizeRank()
                + " dpos=" + String.format(java.util.Locale.ROOT, "%.1f", posU)
                + " dvel=" + String.format(java.util.Locale.ROOT, "%.1f", velUs)
                + " dfacing=" + String.format(java.util.Locale.ROOT, "%.1f", dFacing)
                + " minDist=" + minDist + " near=" + nearBit
                // presented-vs-keyframe tick skew — the residual dpos driver
                // under the tick anchor (expected |dtick| ≤ a few ticks of lane skew).
                + " dtick=" + (plugin.lastSnapshotSimTick == Long.MIN_VALUE ? "n/a"
                        : Long.toString(plugin.lastSnapshotSimTick - record.simTick()))
                // Appended token: presented via contact glide vs hard-set.
                + " glide=" + (glided ? "1" : "0")
                + (poseAdoptLogLines == POSE_ADOPT_LOG_MAX ? " (adopt log cap reached)" : ""));
    }

    /** Flush the hulk-pose adopt ring as
     * {@code predict hulk-pose adopt-ring [where] marker #N} rows (the stutter-ring marker idiom
     * of {@link ClientWorstFramesLedger}). Called by the PRCP F9 owner-marker hook and by
     * {@link #logFractureTotals} at teardown; render thread. WHY: a late F9 (a recorded flight's third F9
     * at tick 47154, live cap spent at ~11219) must still carry its adopt rows. Never throws: a
     * failure logs once through the capped {@link #logFracture}. */
    void dumpAdoptRing(String where, long markerNo, long simTick) {
        try {
            for (String row : poseAdoptRing.dumpRows(where, markerNo, simTick)) {
                PilotWatchLog.info(row);
            }
        } catch (Throwable t) {
            logFracture("adopt-ring dump FAILED " + t.getClass().getSimpleName() + ": "
                    + t.getMessage());
        }
    }

    /** Bucket one adoption's deltas (bucket edges at the histogram fields). */
    private void recordPoseDeltas(float posU, float velUs, float facingDeg) {
        poseDeltaPosHist[posU < 0.1f ? 0 : posU < 1f ? 1 : posU < 5f ? 2
                : posU < 15f ? 3 : posU < 50f ? 4 : 5]++;
        poseDeltaVelHist[velUs < 1f ? 0 : velUs < 5f ? 1 : velUs < 20f ? 2 : 3]++;
        poseDeltaFacingHist[facingDeg < 0.5f ? 0 : facingDeg < 2f ? 1
                : facingDeg < 10f ? 2 : 3]++;
    }

    private static String histString(long[] hist) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hist.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(hist[i]);
        }
        return sb.toString();
    }

    /** Suppress the local at-death fracture ROLL on a bound/replica ship via
     * vanilla's own idiom ({@code ShardSpawner}: {@code getBreakProb().modifyMult(id, 0f)}).
     * The server's untouched engine keeps its authoritative roll; whole-vs-split arrives as
     * {@code HKFR} edges. Keyed mult = idempotent re-apply. Fail-soft on the bind path. */
    void suppressLocalBreakProb(ShipAPI ship) {
        if (ship == null) {
            return;
        }
        try {
            ship.getMutableStats().getBreakProb().modifyMult("cc_hulk_split_sync", 0f);
        } catch (Throwable ignore) {
            // stats are presentation-side here; a failure must never break the bind path
            // 1290 (81) X1: suppress failed -> this replica may LOCALLY split (desync-adjacent).
            FallbackAnnounce.announceOnce("x1-break-prob-suppress-threw", "suppress threw "
                    + ignore.getClass().getSimpleName() + " -> replica may local-split");
        }
    }

    /** Mint the corpse family at a server-decided death edge, carrying the death tick
     * that is its wire identity (schema v2 — the derived familySeq ordinal desynced on any
     * unobserved death). Tick source, in order: the exact own EDSP tick when this is the own
     * corpse; the {@link #despawnTickByEntity} remove-take (fed by every consumed EDSP); the last
     * applied snapshot tick (≈ death + a few ticks — still inside the ±150 bind window). Edges
     * that raced ahead of the death (cross-lane) are already parked under their wire tick and
     * bind on the next {@link #maintainFractureFamilies} sweep. Idempotent per corpse ref
     * (registry shapes can route the same ship through both {@code killFoe} and
     * {@code killRemote}). Fail-soft: family tracking is presentation-side and must never break
     * the kill path. */
    void mintFractureFamily(long rootEntityId, ShipAPI corpse) {
        if (rootEntityId < 1L || corpse == null) {
            return;
        }
        try {
            for (ArrayList<FractureFamily> minted : fractureFamilies.values()) {
                for (FractureFamily existing : minted) {
                    if (existing.pieces.contains(corpse)) {
                        return; // already minted for this corpse (double-edge guard)
                    }
                }
            }
            Long rootKey = Long.valueOf(rootEntityId);
            Long despawnTick;
            synchronized (despawnTickByEntity) {
                despawnTick = despawnTickByEntity.remove(rootKey);
            }
            long mintTick;
            String tickSrc;
            if (plugin.session != null && rootEntityId == plugin.session.shipId()
                    && plugin.ownDespawnTick >= 0L) {
                mintTick = plugin.ownDespawnTick; // the exact own EDSP tick (killOwn consumes it after)
                tickSrc = "own-exact";
            } else if (despawnTick != null) {
                mintTick = despawnTick.longValue();
                tickSrc = "despawn-map";
            } else {
                mintTick = Math.max(0L, plugin.lastSnapshotSimTick); // MIN_VALUE-safe fallback
                tickSrc = "snapshot-fallback";
            }
            FractureFamily family = new FractureFamily(rootEntityId, mintTick);
            family.pieces.add(corpse);
            // Engine-quiet: pin the fresh corpse to the vanilla dead-engine contract — the
            // death blast disables engines only incidentally; make it deterministic.
            if (HULK_ENGINE_QUIET && HulkEngineQuiet.quiet(corpse)) {
                engineQuiets++;
            }
            // Overkill pin: hold the overkill break machine closed (the engine's own -1e8
            // idiom) so the un-vetoed per-hit damage can present without ever splitting.
            if (HULK_OVERKILL_PIN && HulkOverkillPin.pin(corpse)) {
                overkillPins++;
            }
            ArrayList<FractureFamily> list = fractureFamilies.get(rootKey);
            if (list == null) {
                list = new ArrayList<FractureFamily>();
                fractureFamilies.put(rootKey, list);
            }
            list.add(family);
            logFracture("family minted rootId=" + rootEntityId + " mintTick=" + mintTick
                    + " src=" + tickSrc);
        } catch (Throwable ignore) {
            // presentation-side family tracking; the server-decided kill must land regardless
            // 1290 (81) X2: mint threw -> this corpse's lifetime/edges run unmanaged.
            FallbackAnnounce.announceOnce("x2-fracture-mint-threw#" + rootEntityId,
                    "mintFractureFamily threw " + ignore.getClass().getSimpleName());
        }
    }

    /** The render-thread fracture sweep (once per {@code advance} under event replay): drain the
     * net-thread intake into per-wire-family FIFOs (keyed the exact wire deathTick), bind each
     * queue to its local family ({@link #bindFractureFamily} — nearest-tick, latch-on-first-bind),
     * apply every edge whose family is bound, run the one-shot null-cut retry, and expire
     * edges whose family never bound (~2 s park, logged capped). A family whose local pieces all
     * faded stays a counted no-op (fail-soft — fracture is presentation, never
     * round-relevant). */
    void maintainFractureFamilies(float amount) {
        // keep-alive FIRST — every wire-family piece is pinned against the engine's
        // ~30 s off-camera hulk fade before this sweep's edges/keyframes rank the family
        // (the membership law extended to EXISTENCE: pieces the server owns leave only
        // on wire edges, never by the local camera's fade).
        keepAliveWireFamilies();
        // latch-warm law: HKCG records drain into their latches BEFORE the same sweep's
        // edges apply — the server spools all four records of a cut ahead of its paired ADD on
        // the one in-order CTRL lane, so a captured cut's latch is warm when its ADD lands.
        maintainCutGeometryLatches();
        maintainFractureEdges();
        // Detach release right after the edge drain — the latch this sweep fed acts
        // on this same sweep (the server forces same-tick pose keyframes at the emit, so the
        // freed body adopts its drift pose on the same HKPS cadence).
        assertModuleDetach();
        // Keyframes drain AFTER the same sweep's edges — the server spools same-tick edges
        // before keyframes on the one in-order CTRL lane, so a keyframe's pieceCount/rank always
        // describes the post-edge family (rank interpretation race-free).
        maintainPoseKeyframes();
        // Coast assert LAST — a keyframe adopted this sweep re-seeds its piece's
        // ballistic state above, so the same-frame assert is exact (no double-write skew).
        assertPoseCoast(amount);
        // The omega dissolve LAST — its NONE re-assert must land
        // after any same-sweep keyframe/coast collision write.
        advanceOmegaDissolves(amount);
        // The shard fade-in beside it, under the same ordering law.
        advanceShardFadeIns(amount);
    }

    /** Part of the vanilla {@code ShardSpawner} fade-out mirror.
     * Spec-level omega test: {@code shard_spawner} rides every omega hull's
     * {@code getHullSpec().getBuiltInMods()} (survives {@code SpawnHullmodGate}'s VARIANT-level
     * suppression; excludes bay-born fighters). Never throws. */
    static boolean isOmegaHull(ShipAPI ship) {
        try {
            return ship != null && ship.getHullSpec() != null
                    && ship.getHullSpec().getBuiltInMods() != null
                    && ship.getHullSpec().getBuiltInMods().contains(OMEGA_SHARD_SPAWNER_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Is {@code s}'s hull one vanilla's shard
     * split can spawn as a WING ({@code ShardSpawner} spawns a {@code _wing} entry via
     * {@code spawnShipOrWing}, so the child's hull is the wing variant's hull — {@code aspect} on
     * vanilla)? The table is read ONCE, lazily: {@link ShardWingTable#wingIds} over
     * {@code ShardSpawner.variantData}, each wing resolved through {@code getFighterWingSpec} (null
     * on an unknown id — one bad entry skips and counts, it never clears the set); a THROW = an
     * EMPTY set for this instance's life (counted {@code omega_fadein_throws}). One un-capped init
     * row. The caller supplies the {@code isFighter()} term (A2). */
    private boolean isShardWingFighter(ShipAPI s) {
        if (shardWingHulls == null) {
            long t0 = System.nanoTime();
            Set<String> ids = new HashSet<String>();
            int wings = 0;
            int nulls = 0;
            try {
                for (String w : ShardWingTable.wingIds(ShardSpawner.variantData)) {
                    wings++;
                    FighterWingSpecAPI spec = Global.getSettings().getFighterWingSpec(w);
                    if (spec == null || spec.getVariant() == null || spec.getVariant().getHullSpec() == null) {
                        nulls++;
                        omegaFadeInThrows++;
                        continue;
                    }
                    ids.add(spec.getVariant().getHullSpec().getHullId());
                }
            } catch (Throwable t) {
                ids.clear();
                omegaFadeInThrows++;
            }
            shardWingHulls = ids;
            PilotWatchLog.info("predict hulk-split: shard-wing table hulls=" + ids + " wings=" + wings
                    + " nulls=" + nulls + " ns=" + (System.nanoTime() - t0));
        }
        return s.getHullSpec() != null && shardWingHulls.contains(s.getHullSpec().getHullId());
    }

    /** Part of the vanilla {@code ShardSpawner} fade-out mirror.
     * The TRIGGER, called from the PRCP RAPP bind path for {@code re.appended}
     * bodies only: vanilla adds the children's fade-in AND the parent's fade-out plugin on the SAME
     * frame and each child spawns at {@code parent.loc + 0.1R},
     * so a growth-born omega child binding inside an omega hulk's radius IS the server's split
     * frame (one wire latency late, like every presentation). The wire REMOVE edge is vanilla's
     * {@code removeEntity} at fade END — inherently 4 s late, never the trigger.
     * {@code re.appended} excludes a wave-start omega deployment near an old omega hulk. The
     * NEAREST omega hulk piece within {@link #OMEGA_ATTRIBUTION_RADII}·R: not yet dissolving →
     * BEGIN; already dissolving → a later sibling of the same split (quiet); none in range →
     * the {@code UNATTRIBUTED} row (omega / shard-wing children only); an in-range fighter of any
     * other hull → {@code NOT_SHARD}. Never throws (bind-path caller). */
    void noteAppendedBirth(ShipAPI child) {
        boolean omegaHull = isOmegaHull(child);
        if (!OMEGA_DISSOLVE || !(omegaHull || child.isFighter())) { // Shard -> Aspect WINGS never triggered (undressed=10, begun=2)
            return;
        }
        try {
            // The SHIP branch never reads the
            // shard-wing table; only a non-omega FIGHTER asks whether its hull is a shard wing.
            boolean shardWing = !omegaHull && child.isFighter() && isShardWingFighter(child);
            ShipAPI best = null;
            FractureFamily bestFamily = null;
            float bestDist = Float.MAX_VALUE;
            for (ArrayList<FractureFamily> list : fractureFamilies.values()) {
                for (int i = 0; i < list.size(); i++) {
                    FractureFamily family = list.get(i);
                    ArrayList<ShipAPI> pieces = family.pieces;
                    for (int j = 0; j < pieces.size(); j++) {
                        ShipAPI piece = pieces.get(j);
                        if (piece == null || !piece.isHulk() || !isOmegaHull(piece)) {
                            continue;
                        }
                        float dist = Misc.getDistance(piece.getLocation(), child.getLocation());
                        if (dist <= piece.getCollisionRadius() * OMEGA_ATTRIBUTION_RADII
                                && dist < bestDist) {
                            best = piece;
                            bestFamily = family;
                            bestDist = dist;
                        }
                    }
                }
            }
            if (best == null) {
                // Only an omega/shard-wing child is
                // an UNATTRIBUTED split child; a bay fighter far from any omega hulk is not (one recorded
                // flight spent the never-reset FRACTURE_LOG_MAX cap on 143 bay launches).
                if (omegaHull || shardWing) {
                    omegaUnattributed++;
                    logFracture("omega child UNATTRIBUTED hull=" + child.getHullSpec().getHullId());
                }
                return;
            }
            // NOT_SHARD — an attributed fighter that is neither
            // an omega hull nor a shard-wing hull (a bay fighter / drone / threat wing near an omega
            // hulk) is neither dressed nor allowed to start the parent's dissolve early (the H-4 class).
            if (!omegaHull && !shardWing) {
                omegaFadeInNotShard++;
                logFracture("omega child NOT_SHARD hull=" + child.getHullSpec().getHullId());
                return;
            }
            // EVERY attributed child fades in, the later
            // siblings included (vanilla adds one fade-in plugin per shard on the split frame).
            boolean sibling = dissolving.containsKey(best);
            beginShardFadeIn(child, sibling);
            if (sibling) {
                return; // a later sibling of the same split — the parent is already dissolving
            }
            dissolving.put(best, new OmegaDissolve(best.getCollisionClass()));
            omegaBegun++;
            logFracture("omega dissolve BEGIN rootId=" + bestFamily.rootEntityId
                    + " parent=" + best.getHullSpec().getHullId()
                    + " child=" + child.getHullSpec().getHullId()
                    + " dist=" + (int) bestDist + " deathTick=" + bestFamily.latchedWireTick);
        } catch (Throwable t) {
            // presentation only — the bind path must never fail on it
            omegaFadeInThrows++; // Fold: counted, never silent
        }
    }

    /** Part of the vanilla {@code ShardSpawner} fade-out mirror.
     * LAST step of {@link #maintainFractureFamilies}; {@code amount} =
     * the fed frame's seconds like vanilla's per-frame elapsed accumulation — a feed gap freezes
     * the fade with everything else (honest). Fade-out half ONLY: alpha via
     * {@code setExtraAlphaMult2} (the keep-alive pin owns {@code alphaMult}/{@code hulkFader};
     * the engine multiplies both channels
     * into the hulk's render alpha, and hulk expiry keys on fader brightness, never alpha),
     * {@code CollisionClass.NONE} past 50 % (re-asserted every sweep), vanilla jitter
     * + rift-dark negative-nebula puffs until 75 %. {@code pushShipsAway} /
     * {@code setHitpoints} / {@code removeEntity} are skipped — the wire REMOVE edge is the ONLY
     * remover. Overrun (no REMOVE within fade + hold): restore VISIBLY — never a hidden ghost.
     * Every engine write sits in try/catch Throwable → restore + drop the entry. */
    private void advanceOmegaDissolves(float amount) {
        if (dissolving.isEmpty()) {
            return;
        }
        if (plugin.engine == null || plugin.engine.isPaused()) {
            return; // the fade freezes while paused, like vanilla
        }
        Iterator<Map.Entry<ShipAPI, OmegaDissolve>> it = dissolving.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ShipAPI, OmegaDissolve> entry = it.next();
            ShipAPI ship = entry.getKey();
            OmegaDissolve od = entry.getValue();
            boolean dropped = false;
            try {
                if (!plugin.engine.isEntityInPlay(ship)) {
                    // left by another path (MRST sweep / F11 mid-fade) — nothing left to restore
                    it.remove();
                    dropped = true;
                    continue;
                }
                od.elapsed += amount;
                if (od.elapsed > OMEGA_FADE_SEC + OMEGA_HOLD_SEC) {
                    it.remove();
                    dropped = true;
                    restoreDissolveVisible(ship, od);
                    omegaOverruns++;
                    logFracture("omega dissolve OVERRUN restored elapsed=" + od.elapsed);
                    continue;
                }
                float progress = Math.min(1f, od.elapsed / OMEGA_FADE_SEC); //
                ship.setExtraAlphaMult2(1f - progress); // the extra channel — pin-safe
                if (progress > 0.5f) {
                    ship.setCollisionClass(CollisionClass.NONE); //
                }
                float jitterLevel = progress < 0.5f ? progress * 2f : (1f - progress) * 2f; //
                int alpha = Math.min(255, OMEGA_JITTER_COLOR.getAlpha() + (int) (100f * progress)); //
                ship.setJitter(this, Misc.setAlpha(OMEGA_JITTER_COLOR, alpha), jitterLevel, 35, 0f,
                        progress * 100f); // range 35, bonus 100·progress
                od.puffInterval.advance(amount);
                if (od.puffInterval.intervalElapsed() && od.elapsed < OMEGA_FADE_SEC * 0.75f) {
                    spawnDissolvePuffs(ship); //
                }
            } catch (Throwable t) {
                if (!dropped) {
                    try {
                        it.remove();
                    } catch (Throwable ignored) {
                        // iterator already advanced past it
                    }
                }
                restoreDissolveVisible(ship, od);
            }
        }
    }

    /** The honest exit — full brightness + the captured collision class back (each
     * write fail-soft; presentation only). */
    private static void restoreDissolveVisible(ShipAPI ship, OmegaDissolve od) {
        try {
            ship.setExtraAlphaMult2(1f);
        } catch (Throwable ignored) {
            // fail-soft
        }
        try {
            if (od.restoreClass != null) {
                ship.setCollisionClass(od.restoreClass);
            }
        } catch (Throwable ignored) {
            // fail-soft
        }
    }

    /** BEGIN one shard child's fade-in — the two
     * local-body verbs of vanilla's {@code ShardFadeInPlugin} (namely
     * {@code CollisionClass.NONE} + hull-damage-taken ×0 under vanilla's own modifier id) plus its
     * progress-0 alpha, on the bind frame (one wire latency after the server's split frame, like the
     * fade-out). The collision class to restore is captured first (SHIP, or FIGHTER
     * for a wing member). Verdict = {@link ShardFadeInLaw#beginVerdict}: a bay-born fighter
     * ({@code appendCarrierWireId != 0}) is never a shard. The SIM verbs vanilla also runs (command
     * blocks, hold-fire, velocity copy) are skipped: the server already ran them
     * and the replica's pose + weapons are wire/replay-driven. Called from {@link #noteAppendedBirth}
     * (the bind path) only; never throws — a failed write restores visibly, drops, and counts. */
    private void beginShardFadeIn(ShipAPI child, boolean parentDissolving) {
        ShardFadeIn slot = null;
        try {
            PredictReconcileCombatPlugin.RemoteEntity row = null;
            for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
                if (re.ship == child) {
                    row = re; // bound by the plugin before this call
                    break;
                }
            }
            int verdict = ShardFadeInLaw.beginVerdict(true, row != null ? row.appendCarrierWireId : 0L,
                    parentDissolving);
            if (verdict != ShardFadeInLaw.BEGIN && verdict != ShardFadeInLaw.BEGIN_SIBLING) {
                return;
            }
            for (int i = 0; i < fadeIns.size(); i++) {
                ShardFadeIn f = fadeIns.get(i);
                if (f.ship == child) {
                    return; // already fading (one window per body)
                }
                if (slot == null && f.ship == null) {
                    slot = f;
                }
            }
            if (slot == null) {
                slot = new ShardFadeIn();
                fadeIns.add(slot);
            }
            slot.arm(child, row, child.getCollisionClass());
            fadeInsActive++;
            omegaFadeInBegun++;
            if (verdict == ShardFadeInLaw.BEGIN_SIBLING) {
                omegaFadeInSibling++;
            }
            child.setCollisionClass(CollisionClass.NONE); //
            child.getMutableStats().getHullDamageTakenMult().modifyMult(SHARD_INVULN_MOD_ID, 0f); //
            child.setAlphaMult(ShardFadeInLaw.alpha(0f)); // at progress 0
        } catch (Throwable t) {
            omegaFadeInThrows++;
            if (slot != null && slot.ship == child) {
                CollisionClass restore = slot.restoreClass;
                releaseFadeIn(slot);
                restoreFadeInVisible(child, restore);
            }
        }
    }

    /** Advance every running shard fade-in —
     * called LAST in {@link #maintainFractureFamilies} right after {@link #advanceOmegaDissolves},
     * so its NONE re-assert lands after any same-sweep keyframe/coast collision write. Per entry per
     * fed frame (frozen while paused, like vanilla): {@code elapsed += amount}; while
     * {@link ShardFadeInLaw#shielded} re-assert NONE + ×0, past 75 % restore the captured
     * class + unmodify; alpha = progress on vanilla's own {@code setAlphaMult} channel
     * (no other writer owns a LIVE shard replica's alphaMult: the keep-alive pin and the
     * engine's hulk fader write it on hulks only, {@code ConstructionPresentLane} on Threat builds
     * only) + the vanilla jitter; past 4 s alpha 1 + drop. Wire truth
     * outranks the timer, before the lift: the body leaving play (the wire REMOVE / MRST / F11 — the
     * fade-out's own exit read), the row's wire kill latch, or {@link #wireSaysTangible} ends the
     * window at once, restored visibly. Every engine write sits in try/catch: restore, drop, count. */
    private void advanceShardFadeIns(float amount) {
        if (fadeInsActive == 0) {
            return;
        }
        if (plugin.engine == null || plugin.engine.isPaused()) {
            return; // the fade freezes while paused, like vanilla
        }
        float step = amount > 0f ? amount : 0f; // a NaN / negative feed never moves the clock
        long tick = plugin.lastSnapshotSimTick;
        for (int i = 0; i < fadeIns.size(); i++) {
            ShardFadeIn f = fadeIns.get(i);
            ShipAPI ship = f.ship;
            if (ship == null) {
                continue;
            }
            CollisionClass restore = f.restoreClass;
            try {
                if (!plugin.engine.isEntityInPlay(ship)) {
                    endFadeInEarly(f); // the body left play — nothing left to restore
                    continue;
                }
                if ((f.row != null && f.row.killed) || !ship.isAlive()) {
                    endFadeInEarly(f); // the wire kill: the corpse's alpha is the keep-alive pin's now
                    restoreFadeInVisible(ship, restore);
                    continue;
                }
                f.elapsed += step;
                float progress = ShardFadeInLaw.progress(f.elapsed);
                if (!f.lifted) {
                    if (wireSaysTangible(f, tick)) {
                        omegaFadeInWireEnd++;
                        endFadeInEarly(f);
                        restoreFadeInVisible(ship, restore);
                        continue;
                    }
                    if (ShardFadeInLaw.shielded(progress)) {
                        ship.setCollisionClass(CollisionClass.NONE); //
                        ship.getMutableStats().getHullDamageTakenMult().modifyMult(SHARD_INVULN_MOD_ID, 0f); //
                    } else {
                        if (restore != null) {
                            ship.setCollisionClass(restore); //
                        }
                        ship.getMutableStats().getHullDamageTakenMult().unmodifyMult(SHARD_INVULN_MOD_ID); //
                        f.lifted = true;
                        omegaFadeInLifted++;
                    }
                }
                if (ShardFadeInLaw.done(f.elapsed)) {
                    releaseFadeIn(f);
                    restoreFadeInVisible(ship, restore); //
                    continue;
                }
                ship.setAlphaMult(ShardFadeInLaw.alpha(progress)); //
                ship.setJitter(f, OMEGA_JITTER_COLOR, ShardFadeInLaw.jitterLevel(progress),
                        ShardFadeInLaw.JITTER_COPIES, 0f, ShardFadeInLaw.jitterRangeBonus(progress)); //
            } catch (Throwable t) {
                omegaFadeInThrows++;
                if (f.ship == ship) {
                    releaseFadeIn(f);
                }
                restoreFadeInVisible(ship, restore);
            }
        }
    }

    /** The wire-truth read — true once the server body is
     * provably tangible. Reuses what the lanes already decoded, never a snapshot scan: the hull
     * lane's latest wire hull ({@code HullLedger.prevWire}, fed by {@code HullLedger.onWire} on every
     * fresh snapshot in every knob mode) each frame, and — once per fresh wire tick — the child's
     * armor-grid sum, which the armor reconcile lanes (AWSD whole-grid fold / v11 per-cell) pull to
     * the wire while NONE keeps local shots off it. Baselines are NaN-aware running maxima; the
     * verdict is {@link ShardFadeInLaw#wireDropEnds}. Cost: two field reads per frame + one grid sum
     * (the shard's cells, the returned array is the engine's own — no copy) per fresh tick. */
    private boolean wireSaysTangible(ShardFadeIn f, long tick) {
        FoeHullReconcileLane.HullLedger ledger = f.row != null ? f.row.ledger : null;
        float wireHull = ledger != null ? ledger.prevWire : Float.NaN;
        float armorNow = Float.NaN;
        float maxInCell = Float.NaN;
        if (tick != f.armorTick) {
            f.armorTick = tick;
            com.fs.starfarer.api.combat.ArmorGridAPI grid = f.ship.getArmorGrid();
            float[][] cells = grid != null ? grid.getGrid() : null;
            if (cells != null) {
                float sum = 0f;
                for (float[] col : cells) {
                    for (float v : col) {
                        sum += v;
                    }
                }
                armorNow = sum;
                maxInCell = grid.getMaxArmorInCell();
            }
        }
        boolean ends = ShardFadeInLaw.wireDropEnds(f.hullBaseline, wireHull, f.armorBaseline,
                armorNow, maxInCell);
        f.hullBaseline = ShardFadeInLaw.raiseBaseline(f.hullBaseline, wireHull);
        f.armorBaseline = ShardFadeInLaw.raiseBaseline(f.armorBaseline, armorNow);
        return ends;
    }

    /** Free an entry the wire ended; counts {@code omega_fadein_early_end} when it pre-empted the lift. */
    private void endFadeInEarly(ShardFadeIn f) {
        if (!f.lifted) {
            omegaFadeInEarlyEnd++;
        }
        releaseFadeIn(f);
    }

    private void releaseFadeIn(ShardFadeIn f) {
        f.free();
        fadeInsActive--;
    }

    /** The honest exit — full alpha, the captured collision
     * class back, the ×0 modifier gone, like vanilla's own exit. Each write fail-soft and counted. */
    private void restoreFadeInVisible(ShipAPI ship, CollisionClass restore) {
        try {
            ship.setAlphaMult(1f);
        } catch (Throwable t) {
            omegaFadeInThrows++;
        }
        try {
            if (restore != null) {
                ship.setCollisionClass(restore);
            }
        } catch (Throwable t) {
            omegaFadeInThrows++;
        }
        try {
            ship.getMutableStats().getHullDamageTakenMult().unmodifyMult(SHARD_INVULN_MOD_ID);
        } catch (Throwable t) {
            omegaFadeInThrows++;
        }
    }

    /** One puff burst on a fading omega parent — the
     * {@link DarkPuffBurst.Use#FRACTURE} row (3 rift-dark negative-nebula puffs; a faster puff lives
     * shorter), rewritten from the functional spec. The cadence is the
     * caller's ({@link #advanceOmegaDissolves}: an interval, only during the first 75 % of the fade).
     * Cosmetic and unseeded — free-running locally on the engine's shared random. */
    private void spawnDissolvePuffs(ShipAPI ship) {
        if (plugin.engine != null) {
            DarkPuffBurst.burst(DarkPuffBurst.Use.FRACTURE, plugin.engine, ship, 0f); // the FRACTURE row's e = 0
        }
    }

    /** Per-frame ballistic re-assert (wipes client-LOCAL collision impulses
     * within one frame), TICK-ANCHORED: each piece is presented at its
     * keyframe base extrapolated to the AWSS lane's presented server tick — pos = kf.pos +
     * kf.vel × (presentedTick − kf.simTick)/tickRate — never integrated in game time. Game
     * time starves at fps&lt;30 (the engine caps a render frame's dt at MAX_STEP = 1/30 s while
     * the server sim runs wall-exact), so the old {@code amount} integral lost (1 − fps/30) of
     * every coast window and the 2 Hz adopt snapped the piece forward by v×deficit — the
     * cadence-locked sawtooth. The presented-tick clock is the engine-native clock the whole
     * predict lane presents on (prefer-engine-native-seams); hulks now share it, so they move
     * in lockstep with the AWSS replicas at any fps. Local pause is moot ({@code advance()}
     * early-returns paused — the clock freezes with the stream). Yield
     * ({@link #HULK_YIELD}): a REAL collision response — the engine's post-physics velocity
     * stepping off the coast past the census-sized thresholds — re-seeds the coast from the
     * engine; an engine-seeded coast has no keyframe base, so it keeps the
     * old game-time integral until the next adopt re-anchors it. With the contact glide, a
     * contact-correction adopt parks a decaying presentation offset on the coast — the
     * tick-anchored branch adds it to the presented pose until it runs out (~125 ms); a yield
     * clears it (the engine seizes presentation). Never throws (counted; presentation-only). */
    private void assertPoseCoast(float amount) {
        if (!HULK_POSE_COAST || poseCoast.isEmpty()) {
            return;
        }
        long presentedTick = plugin.lastSnapshotSimTick;
        float tickRate = ServerTickRate.ticksPerSecond();
        for (ArrayList<FractureFamily> list : fractureFamilies.values()) {
            for (FractureFamily family : list) {
                for (ShipAPI piece : family.pieces) {
                    PoseCoast coast = poseCoast.get(piece);
                    if (coast == null) {
                        continue; // pre-first-keyframe / post-edge: engine-local (fail-soft)
                    }
                    try {
                        if (!plugin.engine.isEntityInPlay(piece)) {
                            continue; // the prune loops own removal; skip, never mutate
                        }
                        if (piece.getStationSlot() != null) {
                            // Still SLOTTED — the engine's native
                            // per-frame slot re-glue (CombatEngine.updateStationModuleLocations,
                            // getStationSlot()!=null-gated) owns this pose; the coast's linear
                            // tangent fighting that circular re-glue was the jiggle. Drop the
                            // coast (not just skip) so a stale anchor can't teleport the piece
                            // the frame a real detach releases the slot — the next 2 Hz
                            // keyframe re-seeds it, engine-local presents the gap (fail-soft).
                            poseCoast.remove(piece);
                            poseSlottedSkips++;
                            continue;
                        }
                        if (HULK_YIELD) {
                            // Velocity is constant under coast, so the engine's
                            // post-physics velocity deviating from the coast's IS the local
                            // collision impulse of this frame's advance — adopt it.
                            float dvx = piece.getVelocity().x - coast.velX;
                            float dvy = piece.getVelocity().y - coast.velY;
                            float devSq = dvx * dvx + dvy * dvy;
                            if (devSq > YIELD_TOL_US * YIELD_TOL_US
                                    || Math.abs(piece.getAngularVelocity() - coast.angVel)
                                            > YIELD_ANG_TOL_DEG_S) {
                                coast.x = piece.getLocation().x;
                                coast.y = piece.getLocation().y;
                                coast.facing = ((piece.getFacing() % 360f) + 360f) % 360f;
                                coast.velX = piece.getVelocity().x;
                                coast.velY = piece.getVelocity().y;
                                coast.angVel = piece.getAngularVelocity();
                                coast.anchorTick = Long.MIN_VALUE; // no kf base
                                coast.glideStartTick = Long.MIN_VALUE; // Engine seizes pose
                                poseCoastYields++;
                                float dev = (float) Math.sqrt(devSq);
                                if (dev > poseCoastYieldMaxUs) {
                                    poseCoastYieldMaxUs = dev;
                                }
                                continue; // the engine state IS the coast this frame
                            }
                        }
                        if (coast.anchorTick != Long.MIN_VALUE
                                && presentedTick != Long.MIN_VALUE) {
                            // Base extrapolated to the presented tick. dt can run
                            // briefly negative on CTRL-ahead lane skew — backward
                            // extrapolation is equally exact on a ballistic piece, no clamp.
                            float dt = (presentedTick - coast.anchorTick) / tickRate;
                            float px = coast.x + coast.velX * dt;
                            float py = coast.y + coast.velY * dt;
                            float pf = coast.facing + coast.angVel * dt;
                            if (coast.glideStartTick != Long.MIN_VALUE) {
                                // The adopt-time contact offset decays linearly to
                                // zero over the glide window on this same presented-tick
                                // clock (freezes with the stream, like the coast).
                                float k = 1f - (presentedTick - coast.glideStartTick)
                                        / (CONTACT_GLIDE_SECONDS * tickRate);
                                if (k > 1f) {
                                    k = 1f;
                                }
                                if (k > 0f) {
                                    px += coast.glideOffX * k;
                                    py += coast.glideOffY * k;
                                    pf += coast.glideOffFacing * k;
                                } else {
                                    coast.glideStartTick = Long.MIN_VALUE;
                                }
                            }
                            piece.getLocation().set(px, py);
                            piece.setFacing(((pf % 360f) + 360f) % 360f);
                            poseCoastTickAsserts++;
                        } else {
                            // Engine-seeded by a yield (or presented tick not yet armed): the
                            // pre-1277 game-time integral until the next adopt re-anchors.
                            coast.x += coast.velX * amount;
                            coast.y += coast.velY * amount;
                            coast.facing =
                                    ((coast.facing + coast.angVel * amount) % 360f + 360f)
                                            % 360f;
                            piece.getLocation().set(coast.x, coast.y);
                            piece.setFacing(coast.facing);
                            poseCoastGameTimeAsserts++;
                        }
                        piece.getVelocity().set(coast.velX, coast.velY);
                        piece.setAngularVelocity(coast.angVel);
                    } catch (Throwable t) {
                        poseCoastAssertFailures++;
                    }
                }
            }
        }
    }

    /** An applied fracture edge changes the family's server-side shape AND
     * kinematics (split impulses / removal), so the survivors' coast states are stale — drop
     * them and let the local engine's own edge kinematics present until the next cadence
     * keyframe re-adopts (≤0.5 s; the same engine cut runs both sides, so the local impulse
     * approximates the server's). */
    private void clearFamilyCoast(FractureFamily family) {
        for (ShipAPI piece : family.pieces) {
            poseCoast.remove(piece);
        }
    }

    /** per-sweep KEEP-ALIVE on every piece of every minted family (latched or
     * not) — pins the private {@code Ship.sinceVisible} fade clock and heals a mid-fade piece
     * ({@link HulkKeepAliveAdapter}), so the local engine can never fade-remove a piece the
     * authoritative server owns (ONE faded piece = whole-record keyframe drops for the
     * survivors — the defect). The map-border cull stays VANILLA both sides (owner
     * directive 07-16 — the server's own culler fans ordinary REMOVE edges); the prune loops
     * stay as the belt. A disabled adapter = today's fade behavior + the
     * {@code keepalive_disabled} tripwire, never a throw ({@code keepAlive} never throws).
     * The same sweep re-pins each piece's overkill accumulator (belt-and-braces,
     * {@link HulkOverkillPin}). */
    private void keepAliveWireFamilies() {
        if (!HULK_KEEP_ALIVE && !HULK_OVERKILL_PIN) {
            return;
        }
        for (ArrayList<FractureFamily> list : fractureFamilies.values()) {
            for (FractureFamily family : list) {
                for (ShipAPI piece : family.pieces) {
                    if (piece == null) {
                        continue;
                    }
                    if (HULK_KEEP_ALIVE) {
                        int outcome = HulkKeepAliveAdapter.keepAlive(piece);
                        if (outcome == HulkKeepAliveAdapter.OUTCOME_HEALED) {
                            fadeHeals++;
                        } else if (outcome == HulkKeepAliveAdapter.OUTCOME_DISABLED) {
                            keepAliveDisabled++;
                        }
                    }
                    // Belt-and-braces: re-pin each sweep — accumulation restarts from -1e8, so the
                    // engine's break check would need >1e8 hull damage in ONE frame to
                    // ever cross (uncounted: overkill_pins= is creation-edge pins only).
                    if (HULK_OVERKILL_PIN) {
                        HulkOverkillPin.pin(piece);
                    }
                }
            }
        }
    }

    /** Is this ship one of OUR wire-family pieces (any minted family, latched or not)?
     * Identity scan, no per-call collection copies — called per HULK hit from the engine-wide
     * damage listener, which runs on the same thread as this render-thread family state. */
    boolean isWirePiece(ShipAPI ship) {
        if (ship == null) {
            return false;
        }
        for (ArrayList<FractureFamily> list : fractureFamilies.values()) {
            for (int i = 0; i < list.size(); i++) {
                ArrayList<ShipAPI> pieces = list.get(i).pieces;
                for (int j = 0; j < pieces.size(); j++) {
                    if (pieces.get(j) == ship) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String cutGeometryLatchKey(long rootEntityId, long deathTick, int cutSeq) {
        return rootEntityId + ":" + deathTick + ":" + cutSeq;
    }

    /** Drain the net-thread {@code HKCG} intake into per-cut latches (keyed
     * {@code rootId:deathTick:cutSeq}, slot = {@code pieceSlot×2 + ringKind}; duplicate slots
     * keep the first record) and expire stale latches (~10 s TTL; map overflow evicts the
     * oldest) — both evictions count {@code hkcg_latch_expired}. Runs BEFORE the edge sweep so
     * a captured cut's latch is warm at its ADD (the latch-warm law). */
    private void maintainCutGeometryLatches() {
        ArrayList<cc.starsector.client.net.ClientHulkCutGeometryRecord> drained = null;
        synchronized (cutGeometryIntake) {
            if (!cutGeometryIntake.isEmpty()) {
                drained = new ArrayList<cc.starsector.client.net.ClientHulkCutGeometryRecord>(
                        cutGeometryIntake);
                cutGeometryIntake.clear();
            }
        }
        long now = System.nanoTime();
        if (drained != null) {
            for (cc.starsector.client.net.ClientHulkCutGeometryRecord record : drained) {
                String key = cutGeometryLatchKey(record.rootEntityId(), record.deathTick(),
                        record.cutSeq());
                CutGeometryLatch latch = cutGeometryLatches.get(key);
                if (latch == null) {
                    if (cutGeometryLatches.size() >= CUT_GEOMETRY_LATCH_MAX) {
                        String oldestKey = null;
                        long oldestNanos = Long.MAX_VALUE;
                        for (java.util.Map.Entry<String, CutGeometryLatch> entry
                                : cutGeometryLatches.entrySet()) {
                            if (entry.getValue().latchedNanos < oldestNanos) {
                                oldestNanos = entry.getValue().latchedNanos;
                                oldestKey = entry.getKey();
                            }
                        }
                        cutGeometryLatches.remove(oldestKey);
                        hkcgLatchExpired++;
                    }
                    latch = new CutGeometryLatch(now);
                    cutGeometryLatches.put(key, latch);
                }
                int slot = record.pieceSlot() * 2 + record.ringKind();
                if (latch.records[slot] == null) {
                    latch.records[slot] = record;
                }
            }
        }
        if (cutGeometryLatches.isEmpty()) {
            return;
        }
        java.util.Iterator<java.util.Map.Entry<String, CutGeometryLatch>> it =
                cutGeometryLatches.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().latchedNanos > CUT_GEOMETRY_LATCH_EXPIRY_NANOS) {
                it.remove();
                hkcgLatchExpired++;
            }
        }
    }

    private void maintainFractureEdges() {
        ArrayList<cc.starsector.client.net.ClientHulkFractureRecord> drained = null;
        synchronized (fractureIntake) {
            if (!fractureIntake.isEmpty()) {
                drained = new ArrayList<cc.starsector.client.net.ClientHulkFractureRecord>(
                        fractureIntake);
                fractureIntake.clear();
            }
        }
        long now = System.nanoTime();
        if (drained != null) {
            for (cc.starsector.client.net.ClientHulkFractureRecord record : drained) {
                if (record.isRootDetached()) {
                    // family-level latch — membership untouched; never parked as a
                    // family edge (the apply switch reads any non-ADD kind as a REMOVE).
                    moduleDetachEdges++;
                    if (detachedRoots.add(Long.valueOf(record.rootEntityId()))) {
                        logFracture("ROOT_DETACHED latched rootId=" + record.rootEntityId()
                                + " deathTick=" + record.deathTick());
                    }
                    continue;
                }
                String key = fractureFamilyKey(record.rootEntityId(), record.deathTick());
                ArrayDeque<ParkedFractureEdge> queue = parkedFractureEdges.get(key);
                if (queue == null) {
                    queue = new ArrayDeque<ParkedFractureEdge>();
                    parkedFractureEdges.put(key, queue);
                }
                queue.addLast(new ParkedFractureEdge(record, now));
            }
        }
        if (parkedFractureEdges.isEmpty()) {
            return;
        }
        java.util.Iterator<java.util.Map.Entry<String, ArrayDeque<ParkedFractureEdge>>> it =
                parkedFractureEdges.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, ArrayDeque<ParkedFractureEdge>> entry = it.next();
            ArrayDeque<ParkedFractureEdge> queue = entry.getValue();
            FractureFamily family = queue.isEmpty() ? null
                    : bindFractureFamily(queue.peekFirst().record);
            if (family != null) {
                while (!queue.isEmpty()) {
                    ParkedFractureEdge head = queue.peekFirst();
                    int outcome = applyFractureEdge(family, head.record);
                    if (outcome == FRACTURE_APPLY_RETRY) {
                        if (!head.cutRetried) {
                            head.cutRetried = true; // one retry next frame (the null-cut law)
                            break;
                        }
                        fractureCutFailures++;
                        family.cutFailureDebt++;
                        logFracture("ADD cut failed twice rootId=" + family.rootEntityId
                                + " deathTick=" + family.latchedWireTick
                                + " debt=" + family.cutFailureDebt
                                + " -> divergence accepted");
                        // Fallback X3: divergence-accepted must survive the
                        // capped fracture log into the verdict FALLBACK section.
                        FallbackAnnounce.announceOnce("x3-fracture-cut-failed#"
                                + family.rootEntityId + ":" + family.latchedWireTick,
                                "ADD cut failed twice -> permanent shape divergence");
                    }
                    if (head.record.isPieceAdded()) {
                        // The ADD's latch entry + cutSeq ordinal are consumed at
                        // TERMINAL outcome only — a retried head keeps both for its second
                        // attempt (the server consumed one ordinal per EMITTED ADD).
                        cutGeometryLatches.remove(cutGeometryLatchKey(
                                head.record.rootEntityId(), head.record.deathTick(),
                                family.appliedCutSeq));
                        family.appliedCutSeq++;
                    }
                    queue.pollFirst();
                }
                if (family.pieces.isEmpty()) {
                    closeOutFractureFamily(family); // closed out (vanish cascade tail)
                }
            } else {
                while (!queue.isEmpty()
                        && now - queue.peekFirst().parkedNanos > FRACTURE_PARK_EXPIRY_NANOS) {
                    ParkedFractureEdge expired = queue.pollFirst();
                    fractureParkExpired++;
                    logFracture("park EXPIRED unbound key=" + entry.getKey() + " age_ms="
                            + ((now - expired.parkedNanos) / 1_000_000L));
                }
            }
            if (queue.isEmpty()) {
                it.remove();
            }
        }
    }

    /** per-frame slot-release assert for latched ROOT_DETACHED roots. The
     * engine re-glues a slotted module to its parent station every frame (re-glue is
     * {@code getStationSlot()!=null}-gated), so a one-shot release at the edge is not enough
     * when the edge outruns the local death — assert until it lands, then keep watching (a
     * re-slot from any future lane gets re-released next frame). NEVER un-slots a LIVE
     * module (isHulk gate — a live module's slot is load-bearing). Lookup rides the
     * SABL-bound registry row — no family-piece identity assumption. Fail-soft per root. */
    private void assertModuleDetach() {
        if (detachedRoots.isEmpty()) {
            return;
        }
        for (Long rootId : detachedRoots) {
            try {
                PredictReconcileCombatPlugin.RemoteEntity row = plugin.remoteEntities.get(rootId);
                ShipAPI body = row == null ? null : row.ship;
                if (body == null || !plugin.engine.isEntityInPlay(body) || !body.isHulk()) {
                    continue; // unbound / gone / still live — sticky latch retries next frame
                }
                if (body.getStationSlot() != null) {
                    body.setStationSlot(null);
                    moduleDetachApplied++;
                    logFracture("module detach rootId=" + rootId
                            + " slot released (server ROOT_DETACHED; drift free)");
                }
            } catch (Throwable ignore) {
                // fail-soft: a bad body read must not cost the other roots or the sweep
            }
        }
    }

    /** Resolve one wire edge's local family: a family already LATCHED to this exact wire
     * deathTick wins; else the nearest UNLATCHED same-rootId family within
     * {@link #FRACTURE_BIND_TOLERANCE_TICKS} of its mintTick latches to the wire tick on this
     * first bind (no double-claim — a family latched to a different wire tick is never
     * re-claimed). Null = unbound; the caller parks until expiry (a late-join edge for a
     * never-presented corpse correctly never binds). */
    private FractureFamily bindFractureFamily(
            cc.starsector.client.net.ClientHulkFractureRecord record) {
        return bindFractureFamily(record.rootEntityId(), record.deathTick());
    }

    /** The bind core, keyed by the wire identity alone — {@code HKPS} pose keyframes bind AND
     * latch through the same law as edges (an unsplit corpse's family latches off its
     * FIRST keyframe — the main case has no edges at all). */
    private FractureFamily bindFractureFamily(long rootEntityId, long deathTick) {
        ArrayList<FractureFamily> list =
                fractureFamilies.get(Long.valueOf(rootEntityId));
        if (list == null) {
            return null;
        }
        FractureFamily nearest = null;
        long nearestDelta = Long.MAX_VALUE;
        for (FractureFamily candidate : list) {
            if (candidate.latchedWireTick == deathTick) {
                return candidate; // already latched to this exact wire family
            }
            if (candidate.latchedWireTick != -1L) {
                continue; // latched to a DIFFERENT wire family — never re-claim
            }
            long delta = Math.abs(candidate.mintTick - deathTick);
            if (delta < nearestDelta) {
                nearestDelta = delta;
                nearest = candidate;
            }
        }
        if (nearest == null || nearestDelta > FRACTURE_BIND_TOLERANCE_TICKS) {
            return null;
        }
        nearest.latchedWireTick = deathTick;
        logFracture("family bound rootId=" + rootEntityId + " wireDeathTick="
                + deathTick + " mintTick=" + nearest.mintTick
                + " delta=" + nearestDelta);
        return nearest;
    }

    /**
     * D-HULKDEL ({@link RemoteRespawnCorpseLane}): the wire death tick this body's family has
     * LATCHED — a server {@code HKPS}/{@code HKFR} record bound to it, i.e. the server holds a wreck
     * for it — or {@code -1} when no family under {@code rootEntityId} holds the body or none has
     * seen the wire yet (a client-minted family of an absence-inferred kill).
     */
    long wireLatchedDeathTick(long rootEntityId, ShipAPI body) {
        ArrayList<FractureFamily> list = fractureFamilies.get(Long.valueOf(rootEntityId));
        if (list == null || body == null) {
            return -1L;
        }
        for (FractureFamily family : list) {
            if (family.pieces.contains(body)) {
                return family.latchedWireTick;
            }
        }
        return -1L;
    }

    /** Drop a closed-out family from its per-root list + emit the totals line at the edge. */
    private void closeOutFractureFamily(FractureFamily family) {
        // Violent close-outs (~6800 pose applies) sat adjacent to felt
        // stutters — tag the frame so the worst-frames ledger self-attributes them.
        plugin.frameLedger.note("split");
        clearFamilyCoast(family); // Coast entries leave with their family
        Long rootKey = Long.valueOf(family.rootEntityId);
        ArrayList<FractureFamily> list = fractureFamilies.get(rootKey);
        if (list != null) {
            list.remove(family);
            if (list.isEmpty()) {
                fractureFamilies.remove(rootKey);
            }
        }
        logFractureTotals("family close-out rootId=" + family.rootEntityId);
    }

    /** Apply one authoritative fracture edge to its bound family via the engine's own verbs:
     * ADD cuts the family's current largest piece with public {@code splitShip()} (the engine's
     * own H cut-largest rule; cut GEOMETRY stays per-client by contract); REMOVE removes the
     * piece at the carried pre-edge size rank + explosion FX at its pose. Never throws. */
    private int applyFractureEdge(FractureFamily family,
            cc.starsector.client.net.ClientHulkFractureRecord record) {
        try {
            // Prune pieces the local engine already dropped: ranks must index the LIVE local set.
            // Since the keep-alive pinned the off-camera fade (~30.5 s, CORRECTED), the
            // sole local pruner of wire-family pieces is the mirrored map-border cull (vanilla
            // both sides by owner directive) — the already-applied guard below owns that case.
            for (java.util.Iterator<ShipAPI> pit = family.pieces.iterator(); pit.hasNext();) {
                ShipAPI piece = pit.next();
                if (piece == null || !plugin.engine.isEntityInPlay(piece)) {
                    pit.remove();
                    poseCoast.remove(piece); // Mirror applyPoseKeyframe's dead-piece prune — no orphaned coast entries
                }
            }
            if (family.pieces.isEmpty()) {
                fractureRankMisses++;
                logFracture("edge on empty local family rootId=" + family.rootEntityId
                        + " deathTick=" + family.latchedWireTick
                        + " -> no-op (local corpse gone)");
                return FRACTURE_APPLY_DROP;
            }
            if (record.isPieceAdded()) {
                ShipAPI largest = null;
                float largestArea = -1f;
                for (ShipAPI piece : family.pieces) {
                    float area = pieceArea(piece);
                    if (area > largestArea) {
                        largestArea = area;
                        largest = piece;
                    }
                }
                // HKCG replay: a warm latch under THIS ADD's ordinal replays the
                // server's exact cut through the engine's own two-arg carve; ANY miss falls
                // back to today's local RNG cut — the edge is never blocked on geometry.
                ShipAPI cutParent = largest;
                ShipAPI cut = null;
                if (HULK_CUT_GEOMETRY) {
                    try {
                        CutGeometryLatch latch = cutGeometryLatches.get(cutGeometryLatchKey(
                                record.rootEntityId(), record.deathTick(),
                                family.appliedCutSeq));
                        if (latch != null && latch.warm()) {
                            ShipAPI matched = matchCutParent(family, latch);
                            if (matched == null) {
                                hkcgParentMismatch++;
                                logFracture("HKCG parent mismatch rootId="
                                        + family.rootEntityId + " deathTick="
                                        + family.latchedWireTick + " seq="
                                        + family.appliedCutSeq + " -> local cut");
                            } else {
                                cut = applyCutGeometry(matched, latch);
                                if (cut != null) {
                                    cutParent = matched;
                                    hkcgApplied++;
                                    logFracture("HKCG cut applied rootId="
                                            + family.rootEntityId + " deathTick="
                                            + family.latchedWireTick + " seq="
                                            + family.appliedCutSeq);
                                } else {
                                    hkcgFallback++;
                                    logFracture("HKCG apply missed rootId="
                                            + family.rootEntityId + " deathTick="
                                            + family.latchedWireTick + " seq="
                                            + family.appliedCutSeq + " -> local cut");
                                }
                            }
                        }
                    } catch (Throwable t) {
                        // Any HKCG-path failure degrades to the local cut, never out.
                        hkcgFallback++;
                        logFracture("HKCG path FAILED " + t.getClass().getSimpleName()
                                + " -> local cut");
                        cut = null;
                    }
                }
                if (cut == null) {
                    cut = cutParent != null ? cutParent.splitShip() : null;
                }
                if (cut == null) {
                    return FRACTURE_APPLY_RETRY; // tesselation <2 can be transient (retry once)
                }
                family.pieces.add(cut);
                // Engine-quiet: a cut piece gets a brand-new ACTIVE engine controller with
                // pristine engines — pin it to the dead-corpse contract.
                if (HULK_ENGINE_QUIET && HulkEngineQuiet.quiet(cut)) {
                    engineQuiets++;
                }
                // splitShip reset BOTH halves' overkill to 0 —
                // re-pin the parent AND the new piece.
                if (HULK_OVERKILL_PIN) {
                    if (HulkOverkillPin.pin(cutParent)) {
                        overkillPins++;
                    }
                    if (HulkOverkillPin.pin(cut)) {
                        overkillPins++;
                    }
                }
                clearFamilyCoast(family); // post-split kinematics re-adopt at the next keyframe
                fractureAddsApplied++;
                // ADD break-SFX law: vanilla sounds ONCE at break start
                // (explosionScale>0-gated) — a mid-life overkill break, past the kill window whose
                // splits ride the native death SFX; per-family debounce = the H interval ticks.
                if (record.simTick() - record.deathTick() > BREAK_SFX_MIN_TICKS_PAST_DEATH
                        && record.simTick() - family.lastBreakSfxTick >= BREAK_SFX_DEBOUNCE_TICKS
                        && cutParent.getExplosionScale() > 0f) {
                    if (HulkBreakSfxAdapter.play(cutParent)) {
                        fractureBreakSfx++;
                    }
                    family.lastBreakSfxTick = record.simTick();
                }
                if (family.pieces.size() != record.familyPieceCount()) {
                    fractureCountMismatches++;
                }
                logFracture("ADD applied rootId=" + family.rootEntityId
                        + " deathTick=" + family.latchedWireTick
                        + " local_pieces=" + family.pieces.size()
                        + " wire_pieces=" + record.familyPieceCount());
                return FRACTURE_APPLY_OK;
            }
            // PIECE_REMOVED: already-applied guard FIRST — if the post-prune
            // local count already equals the record's POST-edge familyPieceCount, the local
            // engine (mirrored border cull) removed this piece before the edge arrived; applying
            // the rank-indexed REMOVE now would delete a surviving SIBLING (server-live piece
            // invisible = the already-fixed unbound-flood class, re-created).
            // Exemption: under cut-failure debt the local count is one short
            // of the server's, so equality here is the GHOST signature (the server's REMOVE
            // coincides with the deficit), not the border cull — apply the REMOVE.
            if (family.pieces.size() == record.familyPieceCount()) {
                if (family.cutFailureDebt == 0) {
                    fractureRemovesAlreadyApplied++;
                    logFracture("REMOVE already-applied rank=" + record.sizeRank()
                            + " rootId=" + family.rootEntityId
                            + " deathTick=" + family.latchedWireTick
                            + " local_pieces=" + family.pieces.size()
                            + " wire_pieces=" + record.familyPieceCount() + " -> drop");
                    return FRACTURE_APPLY_DROP;
                }
                fractureRemovesUnderDebt++;
                logFracture("REMOVE equality under debt=" + family.cutFailureDebt
                        + " rank=" + record.sizeRank()
                        + " rootId=" + family.rootEntityId
                        + " deathTick=" + family.latchedWireTick
                        + " -> apply (ghost guard exempt)");
            }
            // PIECE_REMOVED: rank the live pieces by bbox area, largest = 0 (the server metric).
            ArrayList<ShipAPI> ranked = new ArrayList<ShipAPI>(family.pieces);
            ranked.sort((a, b) -> Float.compare(pieceArea(b), pieceArea(a)));
            if (record.sizeRank() >= ranked.size()) {
                fractureRankMisses++;
                logFracture("REMOVE rank " + record.sizeRank() + " out of local range "
                        + ranked.size() + " rootId=" + family.rootEntityId + " -> no-op");
                return FRACTURE_APPLY_DROP;
            }
            ShipAPI removed = ranked.get(record.sizeRank());
            OmegaDissolve od = dissolving.remove(removed); // The wire edge ends a dissolve
            if (record.isRemoveDestroyed()) {
                // Kind 3: the engine VAPORIZED the piece — replay the vanilla
                // destruction presentation, not the quiet membership prune.
                presentVaporize(removed);
                fractureRemovesViolent++;
            } else if (od != null) {
                // A dissolving omega parent leaves SILENTLY —
                // vanilla's fade end has no explosion. The alpha at
                // the edge is the skew meter: ≈0 = aligned, >0.2 = the REMOVE came >0.8 s early.
                omegaEnded++;
                logFracture("omega dissolve END rootId=" + family.rootEntityId
                        + " elapsed=" + od.elapsed + " alpha=" + removed.getExtraAlphaMult2());
            } else if (plugin.reclamationFx.endedByWire(removed)) {
                // A Threat hulk mid-reclamation leaves SILENTLY — vanilla's
                // reclamation end is removeEntity + alpha 0, no explosion.
                reclaimEnded++;
                logFracture("reclaim dissolve END rootId=" + family.rootEntityId
                        + " alpha=" + removed.getExtraAlphaMult2());
            } else {
                if (isOmegaHull(removed)) {
                    // An omega hull removed with no dissolve running — expected on an
                    // F11 sweep (the corpse goes at kill+1 s, before any split); a meter otherwise.
                    omegaUndressed++;
                    logFracture("omega REMOVE undressed rootId=" + family.rootEntityId);
                }
                plugin.engine.spawnExplosion(new Vector2f(removed.getLocation()),
                        new Vector2f(removed.getVelocity()), FRACTURE_EXPLOSION_COLOR,
                        Math.max(20f, removed.getCollisionRadius() * 2f), 1.5f);
            }
            plugin.engine.removeEntity(removed);
            family.pieces.remove(removed);
            poseCoast.remove(removed);
            clearFamilyCoast(family); // Survivors' coast is stale post-edge
            fractureRemovesApplied++;
            if (family.pieces.size() != record.familyPieceCount()) {
                fractureCountMismatches++;
            }
            logFracture("REMOVE applied kind=" + record.edgeKind()
                    + " rank=" + record.sizeRank() + " rootId=" + family.rootEntityId
                    + " deathTick=" + family.latchedWireTick
                    + " local_pieces=" + family.pieces.size()
                    + " wire_pieces=" + record.familyPieceCount());
            return FRACTURE_APPLY_OK;
        } catch (Throwable t) {
            fractureApplyFailures++;
            // Observability payment: the silent catch once left a run's triage blind.
            logFracture("apply FAILED rootId=" + family.rootEntityId + " deathTick="
                    + family.latchedWireTick + " " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return FRACTURE_APPLY_DROP;
        }
    }

    /** The kind-3 REMOVE_DESTROYED presentation — vanilla's small-hulk
     * vaporize replayed with the engine's own verbs: the
     * viewport-gated explosion visual (the {@code ExplosionParticleSystem} terminal IS the API
     * {@code spawnExplosion}; size law {@code sqrt(r)*60*explosionScale}, fighter ×26.25 — the
     * correction; the viewport pad is vanilla's {@code r*1.5}) + the
     * {@code explosionScale>0}-gated native break SFX (the ONE sound vanilla plays).
     * Presentation-only and fail-soft: the caller's authoritative {@code removeEntity} lands
     * regardless of anything here. */
    private void presentVaporize(ShipAPI piece) {
        try {
            float r = piece.getCollisionRadius();
            float scale = piece.getExplosionScale();
            com.fs.starfarer.api.combat.ViewportAPI viewport = plugin.engine.getViewport();
            if (viewport != null && viewport.isNearViewport(piece.getLocation(), r * 1.5f)) {
                plugin.engine.spawnExplosion(new Vector2f(piece.getLocation()),
                        new Vector2f(piece.getVelocity()), VAPORIZE_EXPLOSION_COLOR,
                        (float) Math.sqrt(r) * 15f * (piece.isFighter() ? 1.75f : 4f) * scale,
                        piece.isFighter() ? 1f : 2f);
            }
            if (scale > 0f && HulkBreakSfxAdapter.play(piece)) {
                fractureBreakSfx++;
            }
        } catch (Throwable ignore) {
            // presentation-only: the authoritative removal must land regardless
        }
    }

    /** The render-thread pose sweep (runs after {@link #maintainFractureEdges} in the
     * same {@link #maintainFractureFamilies} call): drain the net-thread keyframe intake and
     * one-shot-adopt each. NO park — keyframes are periodic, the next self-heals (a late-join
     * keyframe for a never-presented corpse correctly never binds and drops counted). */
    private void maintainPoseKeyframes() {
        ArrayList<cc.starsector.client.net.ClientHulkPoseRecord> drained = null;
        synchronized (poseIntake) {
            if (!poseIntake.isEmpty()) {
                drained = new ArrayList<cc.starsector.client.net.ClientHulkPoseRecord>(
                        poseIntake);
                poseIntake.clear();
            }
        }
        if (drained == null) {
            return;
        }
        for (cc.starsector.client.net.ClientHulkPoseRecord record : drained) {
            applyPoseKeyframe(record);
        }
    }

    /** Apply one authoritative corpse-pose keyframe: bind+latch by the wire identity
     * (the edge law), prune dead pieces, rank live pieces by bbox area desc (the REMOVE idiom),
     * drop the whole record on a count/rank mismatch (counters), then ONE-SHOT EXACT adoption —
     * direct {@code getLocation().set} / {@code setFacing} / {@code getVelocity().set} /
     * {@code setAngularVelocity}, DELIBERATELY bypassing the isAlive()-guarded apply wrappers
     * (the setters are corpse-safe and side-effect-free — engine ground truth). Under the
     * tick-anchored coast the adoption presents the keyframe extrapolated to the AWSS
     * presented tick — the exact pose the same-sweep {@link #assertPoseCoast} computes — and
     * the measured deltas diff against that target, so the hist/adopt lines keep measuring
     * what the eye sees (raw-at-arrival would re-book the CTRL-vs-AWSS lane skew as a phantom
     * snap). The contact glide amends the free-glide-era "no glide machinery" decision for
     * CONTACT corrections only (the defect it fixes: the ram-blip was this hard set landing up
     * to 500 ms after the local yield guess): a contact-signature adopt keeps the piece's
     * presented pose and hands {@link #assertPoseCoast} a decaying offset instead; velocity
     * always adopts immediately (it drives the coast, the eye only sees pose). Coast off =
     * raw one-shot adoption, the pre-1272 behavior. Never throws. */
    private void applyPoseKeyframe(cc.starsector.client.net.ClientHulkPoseRecord record) {
        try {
            FractureFamily family =
                    bindFractureFamily(record.rootEntityId(), record.deathTick());
            if (family == null) {
                poseDroppedUnbound++; // no park: the next cadence keyframe self-heals
                return;
            }
            // Prune pieces the local engine already dropped (~90 s off-camera corpse fade —
            // a known, accepted asymmetry): ranks must index the LIVE local set.
            for (java.util.Iterator<ShipAPI> pit = family.pieces.iterator(); pit.hasNext();) {
                ShipAPI piece = pit.next();
                if (piece == null || !plugin.engine.isEntityInPlay(piece)) {
                    pit.remove();
                    poseCoast.remove(piece);
                }
            }
            ArrayList<ShipAPI> ranked = new ArrayList<ShipAPI>(family.pieces);
            ranked.sort((a, b) -> Float.compare(pieceArea(b), pieceArea(a)));
            if (record.pieceCount() != ranked.size()) {
                poseCountMismatches++; // family shape disagrees — an HKFR edge is in flight
                return;
            }
            if (record.sizeRank() >= ranked.size()) {
                poseRankMisses++;
                return;
            }
            ShipAPI piece = ranked.get(record.sizeRank());
            if (piece.getStationSlot() != null) {
                // Still SLOTTED — native slot re-glue owns the pose (see
                // assertPoseCoast). Family already bound+latched above (the keyframe's edge-law
                // role is served); adopting the pose here would be the 2 Hz half of the
                // two-writer fight. Coast dropped for the same no-stale-anchor reason.
                poseCoast.remove(piece);
                poseSlottedSkips++;
                return;
            }
            // The eye-visible adopted pose is the keyframe led to the presented
            // tick (coast off or clock unarmed ⇒ lead 0 = raw, the pre-1277 behavior).
            long presentedTick = plugin.lastSnapshotSimTick;
            float lead = HULK_POSE_COAST && presentedTick != Long.MIN_VALUE
                    ? (presentedTick - record.simTick()) / (float) ServerTickRate.ticksPerSecond()
                    : 0f;
            float targetX = record.locX() + record.velX() * lead;
            float targetY = record.locY() + record.velY() * lead;
            float targetFacing =
                    ((record.facing() + record.angVel() * lead) % 360f + 360f) % 360f;
            float dx = targetX - piece.getLocation().x;
            float dy = targetY - piece.getLocation().y;
            if (dx * dx + dy * dy > POSE_LARGE_SNAP_UNITS * POSE_LARGE_SNAP_UNITS) {
                poseLargeSnaps++;
            }
            // per-apply adoption deltas BEFORE the sets — what the eye sees.
            float dvx = record.velX() - piece.getVelocity().x;
            float dvy = record.velY() - piece.getVelocity().y;
            float dFacingSigned =
                    ((targetFacing - piece.getFacing()) % 360f + 540f) % 360f - 180f;
            float dFacing = Math.abs(dFacingSigned);
            float posU = (float) Math.sqrt(dx * dx + dy * dy);
            float velUs = (float) Math.hypot(dvx, dvy);
            recordPoseDeltas(posU, velUs, dFacing);
            // Contact signature — the keyframe velocity stepping off the
            // presented velocity (a server-side impulse since the last keyframe), a
            // yield-seeded coast (the local engine already presented its contact guess; this
            // adopt is the authoritative correction), or an in-flight glide (a follow-burst
            // keyframe mid-glide keeps smoothing rather than snapping the residue).
            PoseCoast prior = HULK_POSE_COAST ? poseCoast.get(piece) : null;
            boolean glide = HULK_CONTACT_GLIDE && prior != null
                    && presentedTick != Long.MIN_VALUE
                    && (velUs > CONTACT_GLIDE_VEL_US
                            || prior.anchorTick == Long.MIN_VALUE
                            || prior.glideStartTick != Long.MIN_VALUE)
                    && posU > CONTACT_GLIDE_MIN_U && posU < POSE_LARGE_SNAP_UNITS;
            logPoseAdopt(record, piece, posU, velUs, dFacing, glide); // Capped line
            if (!glide) {
                piece.getLocation().set(targetX, targetY);
                piece.setFacing(targetFacing);
            }
            piece.getVelocity().set(record.velX(), record.velY());
            piece.setAngularVelocity(record.angVel());
            if (HULK_POSE_COAST) {
                // The adopted keyframe IS the piece's coast state until the next
                // — base pose RAW at the keyframe's own simTick (the tick anchor).
                PoseCoast coast = prior;
                if (coast == null) {
                    coast = new PoseCoast();
                    poseCoast.put(piece, coast);
                }
                coast.x = record.locX();
                coast.y = record.locY();
                coast.facing = record.facing();
                coast.velX = record.velX();
                coast.velY = record.velY();
                coast.angVel = record.angVel();
                coast.anchorTick = record.simTick();
                if (glide) {
                    // Offset = presented-before − led target; the same-sweep
                    // assertPoseCoast owns the piece's pose sets from here (the skipped
                    // hard-set above is deliberate — zero visible jump at adopt).
                    coast.glideOffX = -dx;
                    coast.glideOffY = -dy;
                    coast.glideOffFacing = -dFacingSigned;
                    coast.glideStartTick = presentedTick;
                    poseContactGlides++;
                } else {
                    coast.glideStartTick = Long.MIN_VALUE; // a hard adopt clears any glide
                }
            }
            poseApplied++;
        } catch (Throwable t) {
            fractureApplyFailures++;
            // Observability payment: a silent catch once left a run's triage blind.
            logFracture("pose apply FAILED rootId=" + record.rootEntityId() + " deathTick="
                    + record.deathTick() + " " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    /** The server's cut-largest/rank metric mirrored API-side: bbox area over the exact
     * bounds' orig segment points; fail-soft fallback = collisionRadius². */
    private static float pieceArea(ShipAPI piece) {
        float[] bbox = pieceBbox(piece);
        if (bbox != null) {
            return (bbox[2] - bbox[0]) * (bbox[3] - bbox[1]);
        }
        float r = piece.getCollisionRadius();
        return r * r;
    }

    /** The piece's LOCAL-frame bbox {@code {minX, minY, maxX, maxY}} over its exact bounds'
     * orig segment points (the frame the carried rings live in), or null when
     * degenerate/unreadable — callers fall soft. */
    private static float[] pieceBbox(ShipAPI piece) {
        try {
            com.fs.starfarer.api.combat.BoundsAPI bounds = piece.getExactBounds();
            if (bounds != null) {
                float minX = Float.POSITIVE_INFINITY;
                float minY = Float.POSITIVE_INFINITY;
                float maxX = Float.NEGATIVE_INFINITY;
                float maxY = Float.NEGATIVE_INFINITY;
                List<com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI> segments =
                        bounds.getOrigSegments();
                if (segments != null) {
                    for (com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI seg : segments) {
                        for (Vector2f p : new Vector2f[] {seg.getP1(), seg.getP2()}) {
                            if (p == null) {
                                continue;
                            }
                            minX = Math.min(minX, p.x);
                            minY = Math.min(minY, p.y);
                            maxX = Math.max(maxX, p.x);
                            maxY = Math.max(maxY, p.y);
                        }
                    }
                }
                if (maxX > minX && maxY > minY) {
                    return new float[] {minX, minY, maxX, maxY};
                }
            }
        } catch (Throwable ignore) {
            // fall through to the null fail-soft
        }
        return null;
    }

    /** Parent pick: the carried union bbox (both exact rings, the parent's PRE-SPLIT
     * local frame) must match a live piece's own local-frame bbox within
     * {@link #CUT_GEOMETRY_BBOX_TOL} per axis (the carried rings ARE the parent's pre-split
     * ring partitioned — their union is its bbox up to wire quantization + float dust);
     * several matches take the largest area (the engine cuts the current-largest). */
    private static ShipAPI matchCutParent(FractureFamily family, CutGeometryLatch latch) {
        float[] union = carriedUnionBbox(latch.records[0], latch.records[2]);
        float unionW = union[2] - union[0];
        float unionH = union[3] - union[1];
        ShipAPI matched = null;
        float matchedArea = -1f;
        for (ShipAPI piece : family.pieces) {
            float[] local = pieceBbox(piece);
            if (local == null) {
                continue;
            }
            if (Math.abs((local[2] - local[0]) - unionW) <= CUT_GEOMETRY_BBOX_TOL
                    && Math.abs((local[3] - local[1]) - unionH) <= CUT_GEOMETRY_BBOX_TOL) {
                float area = (local[2] - local[0]) * (local[3] - local[1]);
                if (area > matchedArea) {
                    matchedArea = area;
                    matched = piece;
                }
            }
        }
        return matched;
    }

    private static float[] carriedUnionBbox(
            cc.starsector.client.net.ClientHulkCutGeometryRecord a,
            cc.starsector.client.net.ClientHulkCutGeometryRecord b) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (cc.starsector.client.net.ClientHulkCutGeometryRecord record
                : new cc.starsector.client.net.ClientHulkCutGeometryRecord[] {a, b}) {
            short[] raw = record.points();
            for (int i = 0; i < raw.length; i += 2) {
                float x = raw[i] / 64f;
                float y = raw[i + 1] / 64f;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        return new float[] {minX, minY, maxX, maxY};
    }

    /** Rebuild the two carve-feed bags from a warm latch and replay the cut on the
     * matched parent via {@link HulkCutGeometryAdapter} (slot order: parent bag first =
     * the engine's own sorted feed). Returns the carved-off piece or null — the caller counts
     * the miss and falls back to the local cut. */
    private static ShipAPI applyCutGeometry(ShipAPI parent, CutGeometryLatch latch) {
        List<Vector2f> exactParent = dequantizeClosedRing(latch.records[0]);
        List<Vector2f> visualParent = dequantizeClosedRing(latch.records[1]);
        List<Vector2f> exactClone = dequantizeClosedRing(latch.records[2]);
        List<Vector2f> visualClone = dequantizeClosedRing(latch.records[3]);
        return HulkCutGeometryAdapter.cut(parent,
                exactParent, visualParent, cutPointsFor(latch.records[0], exactParent),
                exactClone, visualClone, cutPointsFor(latch.records[2], exactClone));
    }

    /** The carried OPEN ring → fresh {@code Vector2f} list re-CLOSED by value (last ==
     * first coords, DISTINCT instances — the vanilla ring structure); de-quantize = ÷64. */
    private static List<Vector2f> dequantizeClosedRing(
            cc.starsector.client.net.ClientHulkCutGeometryRecord record) {
        short[] raw = record.points();
        ArrayList<Vector2f> ring = new ArrayList<Vector2f>(raw.length / 2 + 1);
        for (int i = 0; i < raw.length; i += 2) {
            ring.add(new Vector2f(raw[i] / 64f, raw[i + 1] / 64f));
        }
        ring.add(new Vector2f(ring.get(0)));
        return ring;
    }

    /** cut-point law: {@code [fresh copy of ring[idx0], ALIASES of ring[idx1..idxN-1],
     * fresh copy of ring[idxN]]} in carried-index order — interiors alias the bag ring's own
     * instances (vanilla's per-bag aliasing), endpoints are orphan copies, CHAIN order both
     * bags (bag2's vanilla order quirk carries no behavioral weight). Indices index
     * the OPEN ring, valid into the closed list (its extra point is the trailing closure). */
    private static List<Vector2f> cutPointsFor(
            cc.starsector.client.net.ClientHulkCutGeometryRecord exactRecord,
            List<Vector2f> closedExactRing) {
        int[] indices = exactRecord.cutIndices();
        ArrayList<Vector2f> cutPoints = new ArrayList<Vector2f>(indices.length);
        for (int i = 0; i < indices.length; i++) {
            Vector2f ringPoint = closedExactRing.get(indices[i]);
            boolean endpoint = i == 0 || i == indices.length - 1;
            cutPoints.add(endpoint ? new Vector2f(ringPoint) : ringPoint);
        }
        return cutPoints;
    }

    /** Fracture state dies with the round — the server detector's reset() clears
     * its families at the wrap (a cleared family can never apply a stale edge into the fresh
     * world). Cumulative counters persist; the totals line is the round's un-capped debrief
     * signal. */
    void resetForMatchReset() {
        logFractureTotals("match-reset");
        fractureFamilies.clear();
        dissolving.clear(); // The bodies die in the MRST rebuild like every family piece
        // The shard children are appended rows the MRST
        // sweep REMOVES — free the slots (the pool itself is kept for the next round).
        for (int i = 0; i < fadeIns.size(); i++) {
            fadeIns.get(i).free();
        }
        fadeInsActive = 0;
        poseCoast.clear(); // Coast state dies with the round's families
        parkedFractureEdges.clear();
        synchronized (despawnTickByEntity) {
            despawnTickByEntity.clear();
        }
        synchronized (fractureIntake) {
            fractureIntake.clear();
        }
        synchronized (poseIntake) {
            poseIntake.clear(); // A stale keyframe must never adopt into the fresh world
        }
        synchronized (cutGeometryIntake) {
            cutGeometryIntake.clear(); // Stale geometry must never feed a fresh round
        }
        cutGeometryLatches.clear();
    }
}
