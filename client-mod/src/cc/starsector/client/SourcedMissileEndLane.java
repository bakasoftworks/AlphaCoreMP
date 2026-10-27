package cc.starsector.client;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import org.lwjgl.util.vector.Vector2f;

/**
 * The SOURCED missile
 * end-confession presentation collaborator ({@code MissilePoseLane}-owned, the FFC backref
 * shape): consumes the GPEE v7 semantic widening under which {@code MISSILE_END} (kind 6)
 * confesses EVERY vanished missile, sourced included, not just the v5 sourceless
 * mine/system-spawn family.
 *
 * <p>Why it exists: a streamed foe puppet is a
 * teleported pose-follower with no local swept collision, and a SOURCED missile's wire
 * disappearance was indistinguishable from a band-cap dropout — so on the TARGET's own
 * screen a server-confirmed hammer hit presented as the missile sailing through the hull
 * and outliving the wire entity ~5 s (hull damage landed via ledger; impact never showed).
 * With the v7 confession the ambiguity is gone: the server said this missile ENDED at pose
 * P on tick T, so the puppet retires the SAME tick (no free local-physics tick ⇒
 * the +1-tick transition collision can never double-present) and an INTACT end presents a
 * damage-less impact at the confessed pose, snapped to the victim's facing surface. A
 * DESTROYED end (PD kill) drives the engine's native hp-death on the local body instead of
 * the old silent retire, so the vanilla shoot-down puff presents (the lethal
 * {@code applyDamage} targets ONLY the retired missile body; ship damage never rides this
 * lane, the safety property holds).
 *
 * <p>Scope law: SOURCED twins only ({@code t.sourceEntityId > 0} — the server's
 * {@code NO_SOURCE} partition mirrored). Only the legacy degraded {@code (0,-1)} pair stays
 * on the pre-v7 absence-time consumers. Since a later widening moved every
 * weaponless spawn to {@code (N,-1)}, the whole sourceless family (mines, motes, drone-strike
 * conversions, canisters) is served HERE, same tick — kept deliberately
 * (NEVER-ADD-LATENCY: reverting them to the G=6 absence confirm would re-add ~6 frames of end
 * latency). What that arm may PAINT is the narrower law: the generic impact FX is
 * {@link #paintsImpactFx MOUNTED-only} — an INTACT mote end on the (N,-1) pair collected the
 * 80 su orange flash, the painted-FX defect class. Specialized sourced families
 * (phase-charge bombs, canister-family checks) keep their own end FX — this lane's generic
 * impact FX excludes them and covers the plain guided class (the hammer/harpoon shape).
 *
 * <p>Render-thread only, fail-soft throughout; damage-less presentation — own/foe damage
 * stays wire-authoritative (the safety property; no {@code DamagingExplosion},
 * no local fuse). Logs through the owning lane's capped budget.
 */
final class SourcedMissileEndLane {

    /** End-pose→victim attribution slack (su): wire pose quantization + ≤1-2 sim ticks of
     *  delist-pose trail at combat missile speeds (the {@code CONV_HIT_SLACK_SU}
     *  rationale, same class). */
    private static final float IMPACT_SLACK_SU = 30f;
    /** Generic impact visual — modest fixed size/color (per-spec explosionSpec reads are a
     *  mine/bomb-lane luxury the open-ended sourced family can't pre-read; a neutral flash
     *  marks WHERE the authoritative end landed without claiming any specific yield).
     *  Sound deferred until a first-hand spec read pins an id (never guess an asset id). */
    private static final float FOE_IMPACT_FX_SIZE = 80f;
    private static final float FOE_IMPACT_FX_SECONDS = 0.6f;
    private static final java.awt.Color FOE_IMPACT_FX_COLOR =
            new java.awt.Color(255, 165, 80, 255);
    /** Wire-value sanity ceiling for the confessed end pose (the deferred-refactors
     *  wire-value guard row, PAID): NaN/Inf or beyond-any-arena coords refuse the FX
     *  instead of riding the distance math (certified map.half = 9000 su; the negated-≤
     *  comparison form at the check site is what catches NaN). Package-private:
     * the prox-charge engine door's det-pose pick shares this ceiling. */
    static final float WIRE_END_POSE_CEIL_SU = 1_000_000f;

    private final MissilePoseLane mpl;

    /** The OWN-twin wire-victim hit presenter's row budget (its own capped
     *  lane: this lane's generic budget was spent by tick ~3000, the own lane's
     *  was alive at 44228 — the rows must survive to the late-flight cells the auditor names).
     *  Now the VICTIM-HIT row class only (the ladder's hit / skip / threw rows). */
    private static final int OWN_VICTIM_LOG_MAX = 400;
    private final PredictReconcileCombatPlugin.PredictLane ownVictimLog =
            new PredictReconcileCombatPlugin.PredictLane(OWN_VICTIM_LOG_MAX);
    /** Row budget of the own-puppet impact-FX class (door EXPLODE + skip rows). */
    private static final int OWN_IMPACT_LOG_MAX = 400;
    private final PredictReconcileCombatPlugin.PredictLane ownImpactLog =
            new PredictReconcileCombatPlugin.PredictLane(OWN_IMPACT_LOG_MAX);
    /** Row budget of the any-source twinless VICTIM-END class ({@link #noteTwinlessVictimEnd}; 29 % of one measured run's single budget). */
    private static final int TWINLESS_LOG_MAX = 200;
    private final PredictReconcileCombatPlugin.PredictLane twinlessLog =
            new PredictReconcileCombatPlugin.PredictLane(TWINLESS_LOG_MAX);
    private int ownVictimHitApplied;    // predict_missile_own_victim_hit_applied
    private int ownVictimHitUntreated;  // predict_missile_own_victim_hit_untreated (applyWireAckedHit -1: not a treated replica)
    private int ownVictimHitNoBody;     // predict_missile_own_victim_hit_no_body (victim id → no local body / own / dead / no damage)
    private int ownVictimHitFailures;   // predict_missile_own_victim_hit_failures (counted throws)
    private int ownVictimHitFizzling;   // predict_missile_own_victim_hit_fizzling (the FIZZLING + victim arm admitted)
    private int ownVictimHitShieldMiss; // predict_missile_own_victim_hit_shield_miss (a SHIELD_ABSORBED end, no local shield contact: skipped, never hull)
    private int ownImpactExplodeDoor;   // predict_missile_own_impact_explode_door
    private int ownImpactDoorSkipped;   // predict_missile_own_impact_door_skipped (any other door: counted ABSENT, never painted)
    private int ownImpactDoorFault;     // predict_missile_own_impact_door_fault (counted throws)
    private int anySourceVictimEndTwinless; // predict_missile_victim_end_twinless_any_source (victim-form INTACT end, no twin at arrival —
                                            // ANY firer: the wire end names no source, so this is a denominator, never an own count)
    private int ownVictimEndLocalFirst; // predict_missile_own_victim_end_local_first (…and the OWN tombstone says the body left play first)
    // Split out of no_body / failures.
    private int ownVictimHitSkipLocalHitFirst; // predict_missile_own_victim_hit_skip_local_hit_first (local body didDamage() first)
    private int twinlessVictimEndFailures;     // predict_missile_twinless_victim_end_failures (noteTwinlessVictimEnd throws)
    // A prox charge's PD kill now takes the generic death puff.
    private int proxEndDestroyedPuff;          // predict_missile_prox_end_destroyed_puff
    private int deathPuffFailures;             // predict_missile_death_puff_failures (presentDeathPuff throws)

    private int sourcedEndRetired;   // predict_missile_sourced_end_retired (the watch bar)
    private int foeImpactPresented;  // predict_missile_foe_impact_presented
    /** The {@link FoeImpactDoorLaw} door meters (round line). */
    private int foeImpactExplodeDoor;        // predict_missile_foe_impact_explode_door
    private int foeImpactNativeDone;         // predict_missile_foe_impact_native_done
    private int foeImpactPaintBodyless;      // predict_missile_foe_impact_paint_residue_bodyless
    private int foeImpactPaintBodyGone;      // predict_missile_foe_impact_paint_residue_body_gone
    private int foeImpactPaintExplosionSpec; // predict_missile_foe_impact_paint_residue_explosion_spec
    private int foeImpactPaintGlowOff;       // predict_missile_foe_impact_paint_residue_glow_off
    private int foeImpactPresentFault;       // predict_missile_foe_impact_present_fault
    // The native-done VICTIM mismatch measure and the pose-ceiling drop
    private int foeImpactNativeDoneVictimMismatch; // predict_missile_foe_impact_native_done_victim_mismatch
    private int foeImpactPoseCeilingDrop;    // predict_missile_foe_impact_pose_ceiling_drop
    // The foe FIZZLING+victim door — its explode sub-class, paint skips, refusals.
    private int foeFizzlingExplodeDoor;      // predict_missile_foe_fizzling_explode_door (foeImpactExplodeDoor counts it too)
    private int foeImpactOwnShieldPresent;   // predict_missile_foe_impact_own_shield_present (PRESENT_OWN_SHIELD, act=shield)
    private int foeImpactOwnShieldResidue;   // predict_missile_foe_impact_own_shield_residue (the door fired; the act= token names the residue)
    private int foeImpactOwnShieldGlow;      // predict_missile_foe_impact_own_shield_glow (the door on a no-explosionSpec spec — the engine's hit glow drew at the ring contact)
    private int foeFizzlingPaintSkipped;     // predict_missile_foe_fizzling_paint_skipped (a PAINT_RESIDUE_* door: never painted)
    private int foeFizzlingVictimUnnamed;    // predict_missile_foe_fizzling_victim_unnamed (no victim on the wire)
    private int foeFizzlingVictimUnresolved; // predict_missile_foe_fizzling_victim_unresolved (no local ship for it)
    private int foeFizzlingVictimFar;        // predict_missile_foe_fizzling_victim_far (end pose outside the victim's reach)
    /** The VICTIM_MISMATCH row's OWN budget — the
     *  shared foe pose lane ({@code MissilePoseLane.MISSILE_LOG_MAX} = 400) was spent before the one
     *  mismatch of one recorded flight fired, so 0 mismatch rows landed on either seat. Announce-once per
     *  wire missile id (the ids already written live in {@link #victimMismatchLoggedIds}); a row
     *  refused by the cap counts {@code ..._victim_mismatch_rows_capped}. */
    private static final int VICTIM_MISMATCH_LOG_MAX = 200;
    private final long[] victimMismatchLoggedIds = new long[VICTIM_MISMATCH_LOG_MAX];
    private int victimMismatchRows;
    private int foeImpactVictimMismatchRowsCapped; // predict_missile_foe_impact_victim_mismatch_rows_capped
    private int foeImpactVictimMismatchPresented;  // predict_missile_foe_impact_victim_mismatch_presented (door EXPLODE_VICTIM_MISMATCH)
    private int foeDeathPuffDriven;  // predict_missile_foe_death_puff
    private int foeUnstableEnds;     // predict_missile_foe_unstable_ends
    /** The devour end-hook's own lane: its mint path + its {@code _foe_devour_end*} meters. */
    private final DevourEndLane devour;

    /** Round wrap: wire missile ids are REUSED across rounds, so the announce-once
     *  scan is round-local (else a repeated id's row is skipped uncounted); the 200-row budget re-arms
     *  with it. Meters stay cumulative. Called from {@code MissilePoseLane.resetForMatchReset}. */
    void resetForMatchReset() {
        victimMismatchRows = 0;
    }

    /** The fuse explosion's visual radius, from {@code unstable_fragment_missile.proj}. */
    static final float UNSTABLE_EXPLOSION_RADIUS = 120f;
    /** {@code .proj:11} — the explosion colour. */
    static final Color UNSTABLE_EXPLOSION_COLOR = new Color(155, 0, 0, 255);
    /** {@code .proj:32-34} — the detailed explosion's flash core. */
    static final Color UNSTABLE_FLASH_CORE = new Color(255, 185, 185, 255);
    /** {@code .proj:41} — the fuse explosion's sound. */
    static final String UNSTABLE_EXPLOSION_SOUND = "unstable_fragment_explosion";
    static final String UNSTABLE_SPEC_ID = "unstable_fragment_missile";

    /** L1-G3: the Threat unstable-fragment carrier (fuse + 120-radius red explosion, {@code .proj:20-42}). */
    static boolean isUnstableFragmentSpec(String specId) {
        return UNSTABLE_SPEC_ID.equals(specId);
    }

    /** {@code assaying_rift_proj.proj:2} — the Hungering Rift ({@code shrouded_maw_Ravenous}'s
     *  {@code assaying_rift} mount). */
    static final String ASSAYING_RIFT_SPEC_ID = "assaying_rift_proj";

    /** The rift's contact look is the red bloom + sound
     *  its own trail plugin mints at the wire end ({@link CcWrapAssayingRiftEffect}, the
     *  widened predicate) — vanilla has NO generic impact puff on it ({@code .proj:18/:49}), so the
     *  INTACT-end impact FX here would paint an orange 80-su puff + surface-snapped hit particle
     *  NEXT TO the bloom. Excluded like the other self-presenting carriers — but ONLY when the end
     *  has a local body: the bloom is the BODY's trail plugin, so the
     *  body-less structural end ({@link BallisticEndLinkLane} CL-2 expiry) has nothing to bloom
     *  and keeps the generic puff rather than presenting NOTHING. */
    static boolean isAssayingRiftSpec(String specId) {
        return ASSAYING_RIFT_SPEC_ID.equals(specId);
    }

    SourcedMissileEndLane(MissilePoseLane mpl) {
        this.mpl = mpl;
        this.devour = new DevourEndLane(this, mpl);
    }

    /** The kind-6 same-tick retire hook — runs AFTER the {@link MineBombEndFxLane} store
     *  intake (the route-first law: the store is written before any consumer here asks). */
    void onEnd(cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        long id = event.entityId();
        MissilePoseLane.Twin t = mpl.twinByWireId.get(Long.valueOf(id));
        if (t == null) {
            // No twin will ever consume this verdict: the missile is gone from the wire, so
            // no future link/presence-adopt can claim the id (2-sighting law needs a live
            // record). Take the just-stored entry back out so ballistic-blacklisted volume
            // (annihilator-class rocket spam — twinless by design) can never grow the store
            // to its cap and mint a FALSE feed-defect row. Cost: a snap/ctrl reorder that
            // adopts the record one frame later loses the verdict — the mine classifier's
            // no-record default (det) stays the honest fallback there.
            mpl.end.takeWireEnd(id);
            mpl.end.onTwinlessEnd(id, event); // Mine local-det lead/lag row
            noteTwinlessVictimEnd(id, event); // The own local-first class, counted
            // Lever C: a twinless end can still be a SKIP-SET confession (engine-slotted
            // unguided — reaper class — riding pure-local flight): the ballistic lane resolves
            // its pair-linked local bolt and forces the end, so the victim seat's phantom
            // hull damage finally presents an impact. Unlinked ids buffer below (CL-2).
            if (!mpl.ballistic.onConfessedEnd(event)) {
                // CL-2: twinless AND no linked bolt is no longer an instant drop —
                // the dominant miss cause is the snap/ctrl reorder race (the record that would
                // have linked arrives a frame later), so the end
                // BUFFERS and the maintain retry re-dispatches it; the honest
                // f3-twinless-end-dropped announce rides the post-expiry drop instead.
                mpl.ballistic.bufferPending(event);
            }
            return;
        }
        consumeTwin(t, event);
    }

    /** CL-2 — late consume for a BUFFERED twinless end whose id got
     *  twin-claimed after arrival (the reorder race healed via link or presence-adopt):
     *  same dispatch as the live {@link #onEnd} twin branch. */
    void consumeLateTwin(cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        MissilePoseLane.Twin t = mpl.twinByWireId.get(Long.valueOf(event.entityId()));
        if (t != null) {
            consumeTwin(t, event);
        }
    }

    /** The generic impact-PAINT gate, pure. TRUE only for a twin fired from a
     *  real weapon MOUNT ({@code mount >= 0}) that is not a mine. The old gate was {@code !t.mine}
     *  alone, a faithful proxy only while the server degraded every weaponless spawn to
     *  {@code (0,-1)} (which the scope gate above excludes): a later widening changed it to
     *  {@code (N,-1)} and silently moved motes/canisters/conversions into this arm, where an
     *  INTACT mote end collected the 80 su impact paint (the painted-FX defect class — hand-painted
     *  presentation, never the engine's own path). The same-tick retire and the DESTROYED death
     *  puff (the engine's own lethal applyDamage) stay for the whole family. Static + pure: the
     *  selftest row (MineNativeFuseLawSmoke F4). */
    static boolean paintsImpactFx(int sourceMountIndex, boolean mine) {
        return !mine && sourceMountIndex
                != cc.starsector.client.net.ServerMissileState.NO_SOURCE_MOUNT;
    }

    /**
     * The OWN-twin wire-victim hit gate, pure (in a recorded flight
     * the aurora's two swarmer hits on the terminator drone presented 0 on seat 1): an OWN twin
     * whose wire end is of the IMPACT class ({@link ClientGameplayEdgeEvent#endCauseIntactClass}:
     * INTACT or the v15 SHIELD_ABSORBED refinement) AND names a victim ({@code endVictimWireId > 0}).
     * Before this the own branch only retired/faded the local body, and the only consumer of the
     * victim form was the foe devour end-hook — so an own missile's server-dealt hit reached the
     * shooter's seat as nothing but the silent hull fold (no number, no flash). DESTROYED ends stay
     * quiet; a foe twin's hit is the victim seat's business (the replica-hit lane there).
     * A FIZZLING end that names a victim IS an impact: the
     * server's {@code victimWireId} = "the SHIP the missile's LAST engine impact hit" (its
     * {@code GameplayEdgeEvent.missileEnd} v15 javadoc), and in one recorded flight the server dealt damage on 11/17
     * such own (tick, victim) pairs this gate dropped silently (the old "FIZZLING dealt nothing" premise
     * was wrong). It takes the SAME ladder (the engine's own path; the shield takes a
     * shield hit), counted on {@code own_victim_hit_fizzling}. Smoke row: {@code MineNativeFuseLawSmoke} F7.
     */
    static boolean ownVictimHitEligible(boolean own, int endCauseCode, long victimWireId) {
        return own && victimWireId > 0L
                && (cc.starsector.client.net.ClientGameplayEdgeEvent.endCauseIntactClass(endCauseCode)
                        || endCauseCode == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_FIZZLING);
    }

    /** The own-puppet impact door gate, pure (smoke F9;
     *  17/17 applied own hits ended in a silent removeEntity). handedOff: an own-LIVE twin fades, never a door;
     *  !sourcelessMine: its wire end already fires the engine det ({@code onTrackEnd}) — no double det; APPLY
     *  only: LOCAL_HIT_FIRST drew the native glow, every other rung ({@link #PRESENT_NONE} too) applied nothing. */
    static boolean ownPuppetImpactDoor(boolean handedOff, boolean sourcelessMine, int verdict) {
        return handedOff && !sourcelessMine && verdict == PRESENT_APPLY;
    }

    /** The own door's pre-decide skip, pure (smoke F9b): no victim body
     *  ({@code no-victim}), or a kept fuse-AI mine ({@code native-fuse-ai}: its {@code explode()} spawns a
     *  real local DamagingExplosion); {@code null} = decide the door. */
    static String ownDoorSkip(boolean victimNull, boolean mine) {
        return victimNull ? "no-victim" : (mine ? "native-fuse-ai" : null);
    }

    /** Pure (smoke F9c): the door's glow point = the point the ladder APPLIED the
     *  hit at when it has one (the applied hit may have dropped the shield, so a recompute would land on
     *  the hull while the damage was booked on the bubble); the recomputed point only when it has none. */
    static Vector2f ownDoorGlowPoint(Vector2f applyAt, Vector2f recomputed) {
        return applyAt != null ? applyAt : recomputed;
    }

    /**
     * The own-end
     * VICTIM-HIT ladder's APPLY point by the server's end cause, pure (smoke FoeImpactDoorLawSmoke D24).
     * WHY: a {@code SHIELD_ABSORBED} end (cause 3) is the server engine's own shield verdict, but
     * {@code MineBombEndFxLane.impactPoint} falls from the local replica's shield contact to a BOUNDS
     * point whenever that shield is off/stale or its arc misses the end pose — 3 of 13 own-end cause-3
     * rows of a recorded flight painted HULL: a client-only hull
     * flash for a server-said shield hit (the F2 class). The engine's own shield branch never touches
     * hull, so the shield contact is the only safe point; {@code null} = no contact ⇒ the caller's
     * counted {@code why=shield-miss} skip. Every other cause keeps {@code hullPoint} (the ladder as before).
     */
    static Vector2f ownEndApplyPoint(int cause, Vector2f shieldContact, Vector2f hullPoint) {
        return cause == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_SHIELD_ABSORBED
                ? shieldContact : hullPoint;
    }

    /** The {@code why=shield-miss} skip row's source evidence -- the skip
     *  returns before {@code applyWireAckedHit}, so no {@code dmg-apply ... vShieldOn=/vShieldFacing=} row ever
     *  names the victim's local shield for this class. Bounded ints: {@code on=} -1 (no shield) / 0 / 1,
     *  {@code arc=} the active arc, {@code facing=} the shield facing, {@code sd=} the end pose's distance past
     *  the shield ring in su ({@code sd > CONV_HIT_SLACK_SU} = a REACH miss, else an ARC miss).
     *  Pure (FoeImpactDoorLawSmoke D24d). */
    static String shieldMissTokens(int on, float activeArc, float facing, float ringDistSu) {
        return " on=" + on + " arc=" + (int) activeArc + " facing=" + (int) facing + " sd=" + (int) ringDistSu;
    }

    /** The point the own-victim ladder applied its last APPLY at (null on every other rung / before the
     *  ladder ran); read by {@link #presentOwnPuppetImpactDoor} on the same wire end. */
    private Vector2f ownApplyAt;

    /** {@link #presentVerdict} codes: apply, then the skip ladder in evaluation order. */
    static final int PRESENT_APPLY = 0;
    static final int PRESENT_SKIP_NO_BODY = 1;
    static final int PRESENT_SKIP_VICTIM_OWN = 2;
    static final int PRESENT_SKIP_VICTIM_DEAD = 3;
    static final int PRESENT_SKIP_NO_LOCAL_BODY = 4;
    static final int PRESENT_SKIP_LOCAL_HIT_FIRST = 5;
    static final int PRESENT_SKIP_END_POSE = 6;
    static final int PRESENT_SKIP_NO_DAMAGE = 7;
    /** A SHIELD_ABSORBED end with no local shield contact ({@link #ownEndApplyPoint} null): never a hull point. */
    static final int PRESENT_SKIP_SHIELD_MISS = 8;
    /** No wire hit applied — the end was ineligible, or the ladder threw. */
    static final int PRESENT_NONE = -1;

    /**
     * The OWN-victim hit
     * presenter's decision ladder, lifted pure so a smoke row pins it. WHY the new
     * {@code localHitFirst} rung: an own twin whose LOCAL body already landed its hit
     * ({@code didDamage()}) was presented a second time from the wire (the engine's own path twice on
     * one missile); it is now a counted skip {@code why=local-hit-first} on its OWN meter, never
     * {@code no_body} and never a failure. It sits after {@code no-local-body} (didDamage needs a
     * body) and before the wire-pose guard. The pose rung rejects NaN / |pose| &gt; the ceiling.
     * The rung input
     * is {@code localHitOnVictim} — the local body hit THIS victim (or one of its modules), never
     * merely {@code didDamage()}: a seeker that spent itself on drone 292 must not suppress the
     * server's hit on 291 (the ABSENT-TELL class). See {@link #localHitOnVictim}.
     */
    static int presentVerdict(boolean victimGone, boolean victimIsOwn, boolean victimDead,
            boolean localBodyGone, boolean localHitOnVictim, float endX, float endY, float dmg) {
        if (victimGone) {
            return PRESENT_SKIP_NO_BODY;
        }
        if (victimIsOwn) {
            return PRESENT_SKIP_VICTIM_OWN;
        }
        if (victimDead) {
            return PRESENT_SKIP_VICTIM_DEAD;
        }
        if (localBodyGone) {
            return PRESENT_SKIP_NO_LOCAL_BODY;
        }
        if (localHitOnVictim) {
            return PRESENT_SKIP_LOCAL_HIT_FIRST;
        }
        if (!(Math.abs(endX) <= WIRE_END_POSE_CEIL_SU) || !(Math.abs(endY) <= WIRE_END_POSE_CEIL_SU)) {
            return PRESENT_SKIP_END_POSE; // wire-value guard: NaN / absurd pose never presents
        }
        if (!(dmg > 0f)) {
            return PRESENT_SKIP_NO_DAMAGE;
        }
        return PRESENT_APPLY;
    }

    /**
     * The pure
     * same-victim predicate behind the {@code local-hit-first} rung: the local body landed its hit
     * ({@code didDamage}) AND what it hit ({@code DamagingProjectileAPI.getDamageTarget()},
     * "what the damage was dealt to, once didDamage() returns true"; read the same way in
     * {@code DamageWitness}) is the wire victim itself or a module whose parent station is
     * the victim. WHY: in a recorded flight the twins chased drone 292 while the server's hit landed on
     * 291; a bare {@code didDamage()} suppressed the 291 presentation. Objects, not API types, so the
     * smoke pins it without an engine.
     */
    static boolean localHitOnVictim(boolean didDamage, Object target, Object targetParentStation,
            Object victim) {
        if (!didDamage || target == null || victim == null) {
            return false;
        }
        return target == victim || targetParentStation == victim;
    }

    /** The engine-side reader for
     *  {@link #localHitOnVictim}: the parent station of a ship target (a module), else null. */
    private static Object parentStationOf(Object target) {
        return target instanceof ShipAPI ? ((ShipAPI) target).getParentStation() : null;
    }

    /** The foe-impact DOOR's victim-mismatch
     *  input: both sides resolved and different AND not the same module FAMILY (a local module of the
     *  wire victim, or the wire victim a module of the local target — the {@link #localHitOnVictim}
     *  precedent, both directions). The mismatch count + row keep the raw measurement; only the door
     *  is exempted, so the native module glow is never doubled at the parent's surface. Objects, not
     *  API types, so {@code MineNativeFuseLawSmoke} F8d pins it without an engine. */
    static boolean victimMismatchForDoor(Object localTarget, Object localParentStation, Object wireVictim,
            Object victimParentStation) {
        return wireVictim != null && localTarget != null && localTarget != wireVictim
                && localParentStation != wireVictim && victimParentStation != localTarget;
    }

    /** The {@code tgt=} token of the
     *  local-hit-first skip row: the local hit's target class/id, or {@code null}. */
    private static String targetToken(Object target) {
        if (target == null) {
            return "null";
        }
        String id = target instanceof ShipAPI ? ((ShipAPI) target).getId() : null;
        return target.getClass().getSimpleName() + (id == null ? "" : "/" + id);
    }

    /** The {@code why=} token of a {@link #presentVerdict} skip (the older row vocabulary). */
    static String presentVerdictName(int verdict) {
        switch (verdict) {
            case PRESENT_APPLY: return "apply";
            case PRESENT_SKIP_NO_BODY: return "no-body";
            case PRESENT_SKIP_VICTIM_OWN: return "victim=own";
            case PRESENT_SKIP_VICTIM_DEAD: return "victim-dead";
            case PRESENT_SKIP_NO_LOCAL_BODY: return "no-local-body";
            case PRESENT_SKIP_LOCAL_HIT_FIRST: return "local-hit-first";
            case PRESENT_SKIP_END_POSE: return "end-pose-guard";
            case PRESENT_SKIP_NO_DAMAGE: return "no-damage";
            case PRESENT_SKIP_SHIELD_MISS: return "shield-miss";
            default: return "unknown:" + verdict;
        }
    }

    /**
     * Present the server's hit of an OWN missile on the wire-named victim through
     * the engine's OWN damage path (the fix-shape law):
     * {@code applyWireAckedHit} on the victim replica at the facing-surface point of the confessed end
     * pose, with the LOCAL body's engine-true damage/type/EMP (spec × the ship's stats — never a hand
     * table) and the own ship as source so the engine's floaty rule shows the player's numbers; the
     * hull ledger books it wire-acked (no replay entry, the bar never dips twice). Runs BEFORE the
     * caller retires the body, so the local seeker — which may have flown at a different local
     * target (the twins chased drone 292 while the server's hit landed on 291) — never
     * lands a second, local hit after the wire's. Every miss is a counted class; fail-soft.
     * Returns the verdict: the {@link #ownPuppetImpactDoor} input.
     */
    private int presentOwnVictimHit(MissilePoseLane.Twin t,
            cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        // The Twin overload delegates to the
        // body-typed ladder (ONE ladder, ONE set of ownVictimHit* meters); its row tail is unchanged.
        return presentOwnVictimHitVerdict(t.missile, t.wireId, t.specId, event, "handedOff=" + t.handedOff);
    }

    /**
     * {@code presentVerdict}'s handled class:
     * TRUE for APPLY (the wire hit went through the engine's own path) and LOCAL_HIT_FIRST (the local
     * body already landed it); every other rung is "not handled" (the caller keeps its fallback). Pure
     * (smoke MineNativeFuseLawSmoke F8c).
     */
    static boolean presentHandled(int verdict) {
        return verdict == PRESENT_APPLY || verdict == PRESENT_SKIP_LOCAL_HIT_FIRST;
    }

    /**
     * The OWN-victim hit ladder extracted from
     * {@link #presentOwnVictimHit} and typed at {@code DamagingProjectileAPI} (every read the ladder
     * makes — damage/type/EMP/didDamage/target — lives there), so the twin path and the ballistic
     * lane's own UNENROLLED body ({@link BallisticEndLinkLane#bufferPending}) share ONE ladder and ONE
     * set of {@code ownVictimHit*} meters. {@code rowTag} is the VICTIM-HIT row's last token (twins:
     * {@code handedOff=…}, byte-equal to the pre-extraction row; ballistic: {@code cls=ballistic-own}).
     * Returns {@link #presentHandled} of the verdict; a throw is counted + announced and returns false.
     */
    boolean presentOwnVictimHitBody(com.fs.starfarer.api.combat.DamagingProjectileAPI body,
            long wireId, String specId, cc.starsector.client.net.ClientGameplayEdgeEvent event,
            String rowTag) {
        return presentHandled(presentOwnVictimHitVerdict(body, wireId, specId, event, rowTag));
    }

    /** The ladder, moved verbatim: its verdict; a counted throw = PRESENT_NONE. */
    private int presentOwnVictimHitVerdict(com.fs.starfarer.api.combat.DamagingProjectileAPI body,
            long wireId, String specId, cc.starsector.client.net.ClientGameplayEdgeEvent event,
            String rowTag) {
        ownApplyAt = null; // Never a stale point from an earlier end
        long victimId = event.endVictimWireId();
        try {
            ShipAPI ownShip = mpl.plugin.engine == null ? null : mpl.plugin.engine.getPlayerShip();
            ShipAPI victim = resolveShip(victimId);
            float dmg = body == null ? 0f : body.getDamageAmount();
            // The local-hit-first input requires
            // the SAME victim (or its module), not a bare didDamage() on any target.
            boolean didDamage = body != null && body.didDamage();
            Object localTgt = didDamage ? body.getDamageTarget() : null;
            boolean hitOnVictim = localHitOnVictim(didDamage, localTgt, parentStationOf(localTgt), victim);
            int verdict = presentVerdict(victim == null, victim != null && victim == ownShip,
                    victim != null && !victim.isAlive(), body == null,
                    hitOnVictim, event.endX(), event.endY(), dmg);
            if (verdict == PRESENT_SKIP_LOCAL_HIT_FIRST) {
                // The local body already landed its OWN hit on THIS victim -- a
                // second, wire hit would double-present; counted on its own meter, never no_body.
                ownVictimHitSkipLocalHitFirst++;
                ownVictimLog.log("predict v16 own-end VICTIM-HIT skip: id=" + wireId
                        + " tick=" + event.simTick() + " victim=" + victimId + " why=local-hit-first"
                        + " tgt=" + targetToken(localTgt));
                return verdict;
            }
            if (verdict != PRESENT_APPLY) {
                String skip = presentVerdictName(verdict);
                ownVictimHitNoBody++;
                ownVictimLog.log("predict v16 own-end VICTIM-HIT skip: id=" + wireId
                        + " tick=" + event.simTick() + " victim=" + victimId + " why=" + skip);
                return verdict;
            }
            Vector2f loc = new Vector2f(event.endX(), event.endY());
            // A SHIELD_ABSORBED end applies at a shield contact or not at all.
            Vector2f at = ownEndApplyPoint(event.endCauseCode(), OwnDetLane.shieldContactOrNull(victim, loc,
                    MineBombEndFxLane.CONV_HIT_SLACK_SU), MineBombEndFxLane.impactPoint(victim, loc));
            if (at == null) {
                // the skip returns before applyWireAckedHit, so no dmg-apply row names the
                // victim's local shield -- the source evidence rides the skip row (+ rowTag: twin vs ballistic).
                // sd= splits a REACH miss (pose divergence, sd > slack) from an ARC miss (sd <= slack).
                com.fs.starfarer.api.combat.ShieldAPI sh = victim.getShield();
                Vector2f sl = sh == null ? null : sh.getLocation();
                float sd = sl == null ? 0f : (float) Math.hypot(loc.x - sl.x, loc.y - sl.y) - sh.getRadius();
                ownVictimLog.log("predict v16 own-end VICTIM-HIT skip: id=" + wireId + " tick=" + event.simTick()
                        + " victim=" + victimId + " why=" + presentVerdictName(PRESENT_SKIP_SHIELD_MISS)
                        + shieldMissTokens(sh == null ? -1 : sh.isOn() ? 1 : 0, sh == null ? 0f : sh.getActiveArc(),
                                sh == null ? 0f : sh.getFacing(), sd) + " " + rowTag);
                ownVictimHitShieldMiss++; // counted AFTER the reads -- a throw lands on _failures only
                return PRESENT_SKIP_SHIELD_MISS;
            }
            ownApplyAt = new Vector2f(at); // The door glows where the hit was applied
            DamageType type = body.getDamageType();
            float emp = body.getEmpAmount();
            float applied = mpl.plugin.applyWireAckedHit(victim, at, dmg, type, emp, ownShip, event.simTick());
            if (applied >= 0f) {
                ownVictimHitApplied++;
            } else {
                ownVictimHitUntreated++;
            }
            ownVictimLog.log("predict v16 own-end VICTIM-HIT: id=" + wireId
                    + " tick=" + event.simTick() + " victim=remote:" + victimId
                    + " cause=" + event.endCauseCode() + " spec=" + specId
                    + " dmg=" + (int) dmg + " type=" + type + " emp=" + (int) emp
                    + " at=" + (int) at.x + "," + (int) at.y
                    + " applied=" + (applied >= 0f ? String.valueOf((int) applied) : "untreated")
                    + " " + rowTag);
            return verdict;
        } catch (Throwable e) {
            ownVictimHitFailures++; // counted, never silent
            ownVictimLog.log("predict v16 own-end VICTIM-HIT threw: id=" + wireId
                    + " victim=" + victimId + " " + e);
            // Announce once.
            FallbackAnnounce.announceOnce("own-victim-hit",
                    "presentOwnVictimHit threw " + e.getClass().getSimpleName()
                    + " (first id=" + wireId + ") -> the server's own-missile hit went unpresented");
            return PRESENT_NONE;
        }
    }

    /** The own-puppet impact EXPLODE door: a wire hit the ladder ran to its
     *  APPLY rung (treated, or untreated = applyWireAckedHit −1 with zero local damage — both count
     *  in own_impact_explode_door) takes the engine's own explode() glow at the apply point before onPuppetAbsence removes the body (the foe route,
     *  {@link FoeImpactDoorLaw}'s damage-free subset). Any other door = a counted skip row, NEVER a paint; a kept
     *  fuse AI (t.mine) skips (its explode() = a real DamagingExplosion). Glow = raw radius. */
    private void presentOwnPuppetImpactDoor(MissilePoseLane.Twin t,
            cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        try {
            MissileAPI missile = t.missile;
            ShipAPI victim = resolveShip(event.endVictimWireId());
            String skip = ownDoorSkip(victim == null, t.mine); // pinned by smoke F9b
            FoeImpactDoorLaw.Door door = null;
            if (skip == null) {
                com.fs.starfarer.api.loading.MissileSpecAPI spec = missile.getSpec();
                boolean didDamage = missile.didDamage();
                Object tgt = didDamage ? missile.getDamageTarget() : null; // presentImpactFxOwned's inputs
                boolean doorMismatch = tgt != null && tgt != victim
                        && victimMismatchForDoor(tgt, parentStationOf(tgt), victim, victim.getParentStation());
                door = FoeImpactDoorLaw.decide(true, mpl.plugin.engine.isEntityInPlay(missile), didDamage,
                        doorMismatch, spec == null || spec.getExplosionSpec() == null,
                        spec != null && spec.isUseHitGlowWhenDealingDamage(), spec == null ? 0f : spec.getExplosionRadius());
            }
            if (door == FoeImpactDoorLaw.Door.EXPLODE || door == FoeImpactDoorLaw.Door.EXPLODE_VICTIM_MISMATCH) {
                // the glow at the APPLY point — impactPoint = the
                // shield contact / nearest bounds point the applied hit itself used (surfaceToward = its fallback).
                Vector2f loc = ownDoorGlowPoint(ownApplyAt, // The ladder's apply point first
                        MineBombEndFxLane.impactPoint(victim, new Vector2f(event.endX(), event.endY())));
                explodeAt(missile, loc);
                ownImpactExplodeDoor++;
                ownImpactLog.log("predict v16 own-puppet impact-FX: id=" + t.wireId + " spec=" + t.specId
                        + " tick=" + event.simTick() + " at=" + (int) loc.x + "," + (int) loc.y
                        + " snap=victim door=" + door.name());
                return;
            }
            ownImpactDoorSkipped++;
            ownImpactLog.log("predict v16 own-puppet impact-FX skip: id=" + t.wireId + " spec=" + t.specId
                    + " tick=" + event.simTick() + " door=" + (door == null ? skip : door.name()));
        } catch (Throwable e) {
            ownImpactDoorFault++;
            FallbackAnnounce.announceOnce("own-impact-door-" + e.getClass().getSimpleName(), "presentOwnPuppetImpactDoor"
                    + " threw (first id=" + t.wireId + ") -> the own-missile impact glow went unpresented");
        }
    }

    /** The EXPLODE door's engine act (foe + own routes; collision
     *  class NONE first, so the posed body lands no native local hit): intangible, posed at the SERVER end, {@code explode()}. */
    private static void explodeAt(MissileAPI missile, Vector2f loc) {
        if (missile.getCollisionClass() != com.fs.starfarer.api.combat.CollisionClass.NONE) {
            missile.setCollisionClass(com.fs.starfarer.api.combat.CollisionClass.NONE);
        }
        Vector2f body = missile.getLocation();
        if (body != null) {
            body.set(loc); // det where the SERVER said the missile ended (victim surface)
        }
        missile.explode();
    }

    /**
     * The TWINLESS victim-form end: an own body that left play locally BEFORE the
     * wire end (the local-first idiom; {@code pruneLocalGone} tombstoned it) may already have landed
     * its local hit elsewhere (a recorded case: the local seekers most plausibly spent
     * themselves on the apex's shield while the server's missiles hit the drone), and its damage is
     * unreadable (no body) — so the wire's hit is COUNTED and named, never applied here; the victim
     * gets the hull fold. Only {@code local_first} sizes that class: the OWN tombstone is the one
     * own-scoped signal a twinless end has. The wire's {@code MISSILE_END} carries NO source id, so
     * the twinless total counts EVERY firer's victim-form end (54 rows on seat 1 —
     * the seat-1 pilot's 14 hammer hits, which fly pure-local and never twin, plus 40 friendly-fighter
     * annihilator ends on foes) and is named {@code any_source} for that reason.
     */
    private void noteTwinlessVictimEnd(long id, cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        try {
            long victimId = event.endVictimWireId();
            if (victimId <= 0L || !(event.endedIntact() || event.endCauseCode()
                    == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_FIZZLING)) {
                return; // a twinless FIZZLING+victim end counts too
            }
            anySourceVictimEndTwinless++;
            boolean localFirst = mpl.own.isTombstoned(id);
            if (localFirst) {
                ownVictimEndLocalFirst++;
            }
            twinlessLog.log("predict v16 twinless VICTIM-END (any source): id=" + id
                    + " tick=" + event.simTick() + " victim=" + victimId
                    + " cause=" + event.endCauseCode()
                    + (localFirst ? " own-tombstoned (body left play first)" : " no-tombstone"));
        } catch (Throwable e) {
            // Its OWN counter + one log line (never ownVictimHitFailures).
            twinlessVictimEndFailures++;
            FallbackAnnounce.announceOnce("twinless-victim-end-failed",
                    "noteTwinlessVictimEnd threw " + e.getClass().getSimpleName()
                    + " (first id=" + id + ") -> twinless victim-end rows undercounted");
        }
    }

    /** The twin-owned dispatch ({@link #onEnd} live branch + the CL-2 late retry). */
    private void consumeTwin(MissilePoseLane.Twin t,
            cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        long id = event.entityId();
        if (t.sourceEntityId <= 0L) {
            // The legacy degraded (0,-1) pair: pre-v7 consumers own these ends at
            // absence-confirm — the landed, watch-graded arcs stay untouched. The (N,-1)
            // sourceless family passes and is served same-tick (class javadoc).
            return;
        }
        long tick = event.simTick();
        if (t.own) {
            // The server's hit on a named victim presents through the engine's own
            // damage path BEFORE the body is retired below (the local seeker may have flown elsewhere).
            int verdict = PRESENT_NONE;
            if (ownVictimHitEligible(t.own, event.endCauseCode(), event.endVictimWireId())) {
                // The FIZZLING + victim arm, metered where it admits.
                if (event.endCauseCode() == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_FIZZLING) {
                    ownVictimHitFizzling++;
                }
                verdict = presentOwnVictimHit(t, event);
            }
            if (t.sourcelessMine) {
                // The OWN MINE's wire end is the server det — this
                // branch routed it to the silent puppet removal (59 of 72 mines in one recorded
                // flight presented NOTHING); the end lane's mine arm fires the engine's own
                // explode() door first (the tickAbsence G=6 path already did), the retire
                // below then finds the body gone (or removes a wire-DESTROYED dud quietly).
                mpl.end.onTrackEnd(t, tick);
            }
            if (t.handedOff) {
                if (ownPuppetImpactDoor(t.handedOff, t.sourcelessMine, verdict)) { // glow, then removal
                    presentOwnPuppetImpactDoor(t, event);
                }
                mpl.own.onPuppetAbsence(t, tick); // the prompt-removal law, now same-tick
            } else {
                mpl.own.onAbsenceFade(t, tick); // the flameOut-fade law, now same-tick
            }
        } else {
            mpl.end.onTrackEnd(t, tick); // verdict-take + the bomb/canister/conv end FX, same-tick
            if (presentUnstableEnd(t.specId, t.missile, t.wireId, event,
                    mpl.plugin.ownDetLane != null && mpl.plugin.ownDetLane.ownSourced(t))) {
                foeUnstableEnds++;
            }
            // A (N,-1) foe MINE's INTACT end is the explode() door above —
            // the generic 80 su impact puff on top of it was the L2 double-paint.
            // And NO (N,-1) twin paints it — motes/canisters/conversions
            // have no mount; the flash was hand-painted presentation (paintsImpactFx).
            if (paintsImpactFx(t.sourceMountIndex, t.mine)
                    && presentImpactFx(t.specId, t.missile, t.wireId, event, "sourced")) {
                foeImpactPresented++;
            }
            if (presentDeathPuff(t.specId, t.missile, t.wireId, event, "sourced")) {
                foeDeathPuffDriven++;
            }
            // the devouring-carrier disintegrator look from the
            // WIRE seam — BEFORE retire, while the body is still in play.
            devour.onFoeEnd(t, event);
            mpl.retire(t); // in-play-guarded: no-ops when the native death already removed the body
        }
        mpl.twins.remove(t.missile);
        mpl.twinByWireId.remove(t.wireId);
        sourcedEndRetired++;
        mpl.lane.log("predict v16 sourced-end RETIRE: id=" + id
                + " cause=" + event.endCauseCode() + " tick=" + tick
                + " at=" + (int) event.endX() + "," + (int) event.endY()
                + " victim=" + event.endVictimWireId()
                + (t.own ? (t.handedOff ? " class=own-puppet" : " class=own-live")
                        : " class=foe"));
    }

    /** The generic impact FX — INTACT ends (removed-by-collision / consumed-by-det:
     *  the impact class; painting an impact the server never dealt would break the
     *  truth key). DESTROYED = PD kill presents through {@link #presentDeathPuff} instead
     *  (the old quiet retire here ate the truthful shoot-down puff);
     *  a victimless FIZZLING end retires quietly (a dud or a range-out); a FIZZLING end WITH a
     *  named victim inside reach is vanilla's armed-flameout impact ({@code dudProbabilityOnFlameout}
     *  0.5 default) and takes the EXPLODE door, never paint — snapped by
     *  {@code surfaceToward} to the local facing edge: the wire carries no shield bit on a FIZZLING
     *  end (open by design).
     *  Nearest det-eligible body whose fuse-shaped reach
     *  contains the confessed pose gets the FX snapped to its facing surface — own ship
     *  included (the impact-presents-on-target-screen bar); a far end presents
     *  bare at the confessed pose. Package-shared: the ballistic skip-set
     *  consumer ({@link BallisticEndLinkLane}) presents through the SAME recipe — counters
     *  stay at the call sites ({@code clsLabel} keys the shared log line). TRUE = presented. */
    boolean presentImpactFx(String specId, MissileAPI missile, long wireId,
            cc.starsector.client.net.ClientGameplayEdgeEvent event, String clsLabel) {
        return presentImpactFxOwned(specId, ownerOf(missile), wireId, event, clsLabel, missile);
    }

    /** The body's owner, fail-soft to the foe default (1) on a null or unreadable body. */
    static int ownerOf(MissileAPI missile) {
        try {
            if (missile != null) {
                return missile.getOwner();
            }
        } catch (Throwable ignore) {
            // fail-soft: an unreadable owner keeps the foe default
        }
        return 1;
    }

    /** The boolean face of {@link #presentImpactFxDoor}: TRUE = presented (explode OR paint). */
    boolean presentImpactFxOwned(String specId, int owner, long wireId,
            cc.starsector.client.net.ClientGameplayEdgeEvent event, String clsLabel,
            MissileAPI missile) {
        return presentImpactFxDoor(specId, owner, wireId, event, clsLabel, missile) != null;
    }

    /** The body-less core: the structural expiry present has NO local missile —
     *  owner comes from the pending entry's late record sighting (default foe). {@code missile} =
     *  the local body for this end, or null (hasBody = non-null; the twin / pair-linked-bolt
     *  callers pass it): the self-blooming rift exclusion applies only then (a
     *  body-less rift end has no trail plugin to bloom, so it keeps the puff). Every other spec
     *  ignores it.
     *
     *  <p>After the exclusions + pose ceiling, the
     *  {@link FoeImpactDoorLaw} decides the door — {@code EXPLODE} snaps the body to the server
     *  end pose (victim surface first) and fires the engine's own {@code explode()} (never a
     *  painted particle, never removeEntity here: the caller's in-play-guarded retire removes the
     *  body); {@code SKIP_NATIVE_DONE} presents nothing (counted + one log line, returns false);
     *  every PAINT_RESIDUE_* keeps today's paint, counted by name. TRUE = presented (explode OR
     *  paint). A throw counts {@code present_fault} and announces once per exception class.
     *
     *  <p>A native hit on a ship OTHER than the wire
     *  victim writes its own VICTIM_MISMATCH row ({@link #logVictimMismatch}) and, on the
     *  explode()-safe subset, takes {@code EXPLODE_VICTIM_MISMATCH} = the EXPLODE route with the
     *  victim = the wire victim (the server-truth hit is never ABSENT; no paint, no applyDamage). */
    /* Returns the Door TAKEN (explode OR paint), null = nothing presented — the
     * ballistic consumer stamps it on its CONSUME row (`present=`). The boolean faces above are unchanged. */
    FoeImpactDoorLaw.Door presentImpactFxDoor(String specId, int owner, long wireId,
            cc.starsector.client.net.ClientGameplayEdgeEvent event, String clsLabel,
            MissileAPI missile) {
        boolean hasBody = missile != null;
        if (!MissilePoseLane.MISSILE_POSE_APPLY
                || !(event.endedIntact() // v15: INTACT-class (a shield-absorbed torpedo still puffs)
                        || event.endCauseCode() == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_FIZZLING)
                || MineBombEndFxLane.isProxChargeSpec(specId)
                || MineBombEndFxLane.isCanisterSpec(specId)
                || DroneStrikeFxLane.isConversionSpec(specId)
                || isUnstableFragmentSpec(specId)
                || (hasBody && isAssayingRiftSpec(specId))) {
            return null;
        }
        if (!(Math.abs(event.endX()) <= WIRE_END_POSE_CEIL_SU)
                || !(Math.abs(event.endY()) <= WIRE_END_POSE_CEIL_SU)) {
            // wire-value guard: non-finite/absurd confessed pose never presents — counted + logged
            // (a route that presents nothing is never silent)
            foeImpactPoseCeilingDrop++;
            mpl.lane.log("predict v16 foe impact-FX drop: id=" + wireId + " spec=" + specId
                    + " pose_ceiling at=" + event.endX() + "," + event.endY() + " cls=" + clsLabel);
            return null;
        }
        try {
            // Past the bail a non-INTACT end is FIZZLING — admitted
            // only with a resolved named victim inside reach (each refusal counted in fizzlingVictimAdmit).
            boolean fizzlingVictim = !event.endedIntact();
            ShipAPI fizzleVictim = fizzlingVictim ? fizzlingVictimAdmit(event) : null;
            if (fizzlingVictim && fizzleVictim == null) {
                return null;
            }
            FoeImpactDoorLaw.Door door = FoeImpactDoorLaw.Door.PAINT_RESIDUE_BODYLESS;
            com.fs.starfarer.api.combat.CombatEntityAPI localTarget = null;
            ShipAPI wireVictim = null;
            boolean mismatch = false;
            boolean doorMismatch = false; // The door's input, module family exempt
            Vector2f ownRing = null;      // The OWN shield-ring contact (PRESENT_OWN_SHIELD input)
            String ownRingWhy = null;     // its refusal reason when the own-shield route was considered
            if (hasBody) {
                com.fs.starfarer.api.loading.MissileSpecAPI spec = missile.getSpec();
                boolean didDamage = missile.didDamage();
                if (didDamage) {
                    // The mismatch inputs BEFORE the door.
                    // The mismatch predicate, kept AS IS: both sides must resolve.
                    localTarget = missile.getDamageTarget();
                    wireVictim = event.endVictimWireId() > 0L ? resolveShip(event.endVictimWireId()) : null;
                    mismatch = wireVictim != null && localTarget != null && localTarget != wireVictim;
                    // The module FAMILY is exempt from the DOOR only — the count
                    // + row below keep the raw mismatch (the `local_module_parent=` token measures the family).
                    doorMismatch = mismatch && victimMismatchForDoor(localTarget, parentStationOf(localTarget),
                            wireVictim, wireVictim.getParentStation());
                }
                boolean explosionSpecNull = spec == null || spec.getExplosionSpec() == null;
                // The own-shield route is CONSIDERED only where it could fire —
                // a still-flying body that hit nothing, the server's SHIELD_ABSORBED verdict on the OWN
                // ship. Since a later fix: EVERY spec, not only explosionSpec
                // torpedoes — 23 Locust cause-3 own ends + 8 FIZZLING took EXPLODE = a bare glow at the ring
                // with NO engine shield hit (a player's "explode near my shield, not on it" percept; round 1's same
                // class hit natively = SKIP_NATIVE_DONE); and a FIZZLING end whose ADMITTED victim is the
                // own ship (a burnt-out missile the server popped on our raised shield — the wire carries
                // no shield bit on FIZZLING, so the local ring gate below decides; a local ring that is
                // down/off-bearing keeps the EXPLODE door at the hull edge). The contact = the local ring
                // (raised, arc covers the bearing, pose within the FIZZLING reach slack of the ring); null
                // keeps today's residue and names why on this row (`own_ring=`).
                boolean ownShieldCause = event.endCauseCode()
                        == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_SHIELD_ABSORBED
                        || (fizzlingVictim && fizzleVictim == mpl.plugin.ownShip);
                if (!didDamage && ownShieldCause && event.endVictimWireId() > 0L) {
                    ShipAPI own = resolveShip(event.endVictimWireId());
                    if (own != null && own == mpl.plugin.ownShip) {
                        com.fs.starfarer.api.combat.ShieldAPI shield = own.getShield();
                        Vector2f sl = shield == null ? null : shield.getLocation();
                        // A body reading PHASE_MINE now never takes the route
                        if (MissilePoseLane.keepsNativeAi(missile)) {
                            ownRingWhy = "mine";
                        } else if (shield == null || !shield.isOn() || sl == null) {
                            ownRingWhy = "off";
                        } else if (!ownRingArcUsable(shield.getActiveArc())) {
                            ownRingWhy = "arc"; // under 1 deg the gate and the engine disagree
                        } else {
                            float[] c = ringContact(sl.x, sl.y, shield.getRadius(), event.endX(), event.endY(),
                                    missile.getCollisionRadius(), FIZZLING_VICTIM_REACH_SLACK_SU);
                            if (c == null) {
                                ownRingWhy = "far";
                            } else if (!shield.isWithinArc(new Vector2f(c[0], c[1]))) {
                                ownRingWhy = "arc";
                            } else {
                                ownRing = new Vector2f(c[0], c[1]);
                                ownRingWhy = "ok";
                            }
                        }
                    }
                }
                // The ring contact unlocks PRESENT_OWN_SHIELD = presentOwnShieldHit
                // (a synchronous applyDamage under the flux window + the torpedo's INERT blast) — never
                // explode(): explode()'s DamagingExplosion
                // first collides at F+1, AFTER this advance's own-flux pin (maintainOwnFlux) already carried
                // the server's hit (a 2x-then-snap flux bar) and after a slaved overload can drop the shield
                // (a client-only HULL hit).
                door = FoeImpactDoorLaw.decide(true, mpl.plugin.engine.isEntityInPlay(missile),
                        didDamage, doorMismatch, explosionSpecNull,
                        spec != null && spec.isUseHitGlowWhenDealingDamage(),
                        spec == null ? 0f : spec.getExplosionRadius(), ownRing != null);
            }
            if (mismatch) {
                foeImpactNativeDoneVictimMismatch++;
                logVictimMismatch(event.simTick(), wireId, specId, localTarget, wireVictim,
                        event.endVictimWireId(), door);
            }
            if (door == FoeImpactDoorLaw.Door.SKIP_NATIVE_DONE) {
                foeImpactNativeDone++;
                // a local hit on the WRONG ship
                // leaves the server's victim ABSENT — measured above; the mismatch door presents the explode()-safe
                // subset (EXPLODE_VICTIM_MISMATCH below), the rest stays here, counted + rowed.
                mpl.lane.log("predict v16 foe impact-FX skip: id=" + wireId + " spec=" + specId
                        + " door=" + door.name() + " cls=" + clsLabel
                        + " local_target=" + (localTarget == null ? "none"
                                : (localTarget instanceof ShipAPI ? "owner" + ((ShipAPI) localTarget).getOwner()
                                        + (localTarget == mpl.plugin.engine.getPlayerShip() ? "(own)" : "")
                                        : localTarget.getClass().getSimpleName()))
                        + " wire_victim=" + (wireVictim == null ? "none" : "owner" + wireVictim.getOwner()
                                + (wireVictim == mpl.plugin.engine.getPlayerShip() ? "(own)" : ""))
                        + (mismatch ? " VICTIM_MISMATCH" : "")
                        + " (local body already dealt its hit natively)");
                return null;
            }
            Vector2f loc = new Vector2f(event.endX(), event.endY());
            Vector2f still = new Vector2f();
            boolean victimMismatchDoor = door == FoeImpactDoorLaw.Door.EXPLODE_VICTIM_MISMATCH;
            boolean ownShieldDoor = door == FoeImpactDoorLaw.Door.PRESENT_OWN_SHIELD;
            if (door == FoeImpactDoorLaw.Door.EXPLODE || victimMismatchDoor || ownShieldDoor) {
                // The mismatch door rides this route with
                // victim = the wire victim (resolved + non-null by the mismatch predicate), not the
                // local target the native hit landed on.
                ShipAPI victim = victimMismatchDoor ? wireVictim
                        : ownShieldDoor ? mpl.plugin.ownShip // The own ship, resolved by the ring gate
                        : fizzleVictim != null ? fizzleVictim // The admitted victim, resolved once
                        : (event.endVictimWireId() > 0L ? resolveShip(event.endVictimWireId()) : null);
                ShipAPI snapped = victim != null ? victim : nearestDetEligible(owner, loc);
                String ownAct = null; // The own-shield act's outcome token (the row's `act=`)
                if (ownShieldDoor) {
                    loc = ownRing; // OUTSIDE the local ring: where a real contact leaves the missile centre
                    ownAct = presentOwnShieldHit(missile, specId, ownRing);
                } else {
                    if (snapped != null) {
                        loc = MineBombEndFxLane.surfaceToward(snapped, loc);
                    }
                    // the snapped twin must be INTANGIBLE first — the engine
                    // runs its collision pass AFTER the plugins and BEFORE executeRemoves (frame order: plugins,
                    // collision, executeRemoves — the hit
                    // drift this fixes; the grid skips only CollisionClass.NONE
                    // bodies), so a tangible body teleported onto the victim's surface
                    // would land a NATIVE local hit on top of the server-truth mirror (the hull dip-and-snap
                    // class). The fizzle-belt idiom (MissilePoseLane's fade belt); mines were already NONE.
                    explodeAt(missile, loc); // the block moved verbatim, shared with the own door
                }
                if (victimMismatchDoor) {
                    foeImpactVictimMismatchPresented++;
                } else if (ownShieldDoor) {
                    if (OWN_SHIELD_ACT_CLEAN.equals(ownAct)) {
                        foeImpactOwnShieldPresent++;
                    } else {
                        foeImpactOwnShieldResidue++;
                    }
                } else {
                    foeImpactExplodeDoor++;
                    // The FIZZLING sub-class of the explode door.
                    if (fizzlingVictim) {
                        foeFizzlingExplodeDoor++;
                    }
                }
                mpl.lane.log("predict v16 foe impact-FX: id=" + wireId + " spec=" + specId
                        + " at=" + (int) loc.x + "," + (int) loc.y
                        + " mult=" + (missile.getDamage() == null ? "none"
                                : String.valueOf(missile.getDamage().getMultiplier())) // The glow's brightness
                        + (snapped == null ? " snap=none"
                                : " snap=" + (snapped == victim ? "victim" : "near")
                                        + "-owner" + snapped.getOwner()
                                        + (snapped == mpl.plugin.engine.getPlayerShip()
                                                ? "(own)" : ""))
                        + " cls=" + clsLabel + " door=" + door.name() + (fizzlingVictim ? " cause=FIZZLING" : "")
                        + (ownRingWhy == null ? "" : " own_ring=" + ownRingWhy)
                        + (ownAct == null ? "" : " act=" + ownAct));
                return door;
            }
            if (fizzlingVictim) {
                // Every door reaching the painted fallback
                // below is a PAINT_RESIDUE_* — on a FIZZLING end a counted skip, never the 80-su paint.
                foeFizzlingPaintSkipped++;
                mpl.lane.log("predict v16 foe fizzle-impact skip: id=" + wireId + " spec=" + specId
                        + " door=" + door.name() + " cls=" + clsLabel);
                return null;
            }
            ShipAPI snapped = nearestDetEligible(owner, loc);
            if (snapped != null) {
                loc = MineBombEndFxLane.surfaceToward(snapped, loc);
                mpl.plugin.engine.addHitParticle(loc, still, 70f, 1f, 0.4f,
                        FOE_IMPACT_FX_COLOR);
            }
            mpl.plugin.engine.spawnExplosion(loc, still, FOE_IMPACT_FX_COLOR,
                    FOE_IMPACT_FX_SIZE, FOE_IMPACT_FX_SECONDS);
            switch (door) {
                case PAINT_RESIDUE_BODY_GONE:
                    foeImpactPaintBodyGone++;
                    break;
                case PAINT_RESIDUE_EXPLOSION_SPEC:
                    foeImpactPaintExplosionSpec++;
                    break;
                case PAINT_RESIDUE_GLOW_OFF:
                    foeImpactPaintGlowOff++;
                    break;
                case PAINT_RESIDUE_BODYLESS:
                    foeImpactPaintBodyless++;
                    break;
                default: // A NEW Door value never counts as bodyless silently
                    foeImpactPresentFault++;
                    break;
            }
            mpl.lane.log("predict v16 foe impact-FX: id=" + wireId + " spec=" + specId
                    + " at=" + (int) loc.x + "," + (int) loc.y
                    + (snapped == null ? " snap=none"
                            : " snap=owner" + snapped.getOwner()
                                    + (snapped == mpl.plugin.engine.getPlayerShip()
                                            ? "(own)" : ""))
                    + " cls=" + clsLabel + " door=" + door.name()
                    + (ownRingWhy == null ? "" : " own_ring=" + ownRingWhy));
            return door;
        } catch (Throwable e) {
            // presentation lane; never break the watch — but never silent (counted + once per class)
            foeImpactPresentFault++;
            FallbackAnnounce.announceOnce("foe-impact-present-fault-" + e.getClass().getSimpleName(),
                    "presentImpactFxOwned threw " + e.getClass().getSimpleName()
                    + " (first id=" + wireId + " spec=" + specId + ") -> foe INTACT end presented nothing");
            return null;
        }
    }

    /** The OWN shield-ring contact for a SHIELD_ABSORBED foe end, pure: the
     *  confessed pose projected radially onto the local ring and pushed OUTWARD by the body's collision
     *  radius (where a real contact leaves the missile centre). The contact is
     *  the {@code applyDamage} point (the engine's own arc test picks the shield branch from it)
     * and the inert blast's centre. Null when the pose is
     *  farther than {@code slackSu} from the ring (the server's ring and ours disagree — today's residue),
     *  the ring is not positive, or the bearing is degenerate; NaN anywhere fails closed. The caller
     *  still asks the shield whether the arc covers the point. */
    static float[] ringContact(float cx, float cy, float ringR, float atX, float atY, float bodyR, float slackSu) {
        float dx = atX - cx;
        float dy = atY - cy;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (!(d > 0.01f) || !(ringR > 0f) || !(Math.abs(d - ringR) <= slackSu)) {
            return null;
        }
        float k = (ringR + Math.max(0f, bodyR)) / d;
        return new float[] {cx + dx * k, cy + dy * k};
    }

    /** The OWN-shield act for a foe explosionSpec torpedo the SERVER absorbed on
     *  our raised shield ({@code SHIELD_ABSORBED}, victim = the own ship) while the local body flew past:
     *  (1) the direct hit's ripple, number and sound through the engine's own {@code applyDamage} on the
     *  own body at the ring contact — SYNCHRONOUS inside the end drain, i.e. BEFORE this advance's own-flux
     *  pin ({@code maintainOwnFlux}), under the {@link WireAckedFluxWindow} (the pinned wire flux already
     *  carries this hit, so the apply's rise is restored: never a net local booking). The
     *  same-frame arc test is the engine's own (the caller's
     *  {@code isOn} + {@code isWithinArc} gate), so the hull never takes it; a hull component is restored
     *  through {@code mirrorHull} and named on the row, never a dip (F2); its armor-cell write is named
     *  ({@code +armor_unrestored}), not restored. An un-armable window presents no
     *  ripple at all — fail-closed on the own ship. (2) the torpedo's own blast VISUAL: its explosionSpec
     *  cloned once per spec id with both collision classes NONE ({@link #inertBlastSpec}) through
     *  {@code spawnDamagingExplosion} — the engine's particles, flash and sound set for THAT torpedo
     *  ({@code DamagingExplosion.init}), paired with nothing.
     *  Vanilla excludes the directly-hit ship from its blast anyway; the
     *  bystander blast the server dealt rides their hull mirrors. Never {@code explode()}: its REAL
     *  DamagingExplosion collides at F+1. The body goes intangible first;
     *  the caller's retire removes it. Returns the row's {@code act=} token ({@link #ownShieldActToken});
     *  a throw propagates to the door's own catch (counted {@code present_fault}) AFTER the window restored. */
    private String presentOwnShieldHit(MissileAPI missile, String specId, Vector2f contact) {
        ShipAPI own = mpl.plugin.ownShip;
        if (missile.getCollisionClass() != com.fs.starfarer.api.combat.CollisionClass.NONE) {
            missile.setCollisionClass(com.fs.starfarer.api.combat.CollisionClass.NONE);
        }
        ShipAPI source = missile.getSource();
        float damage = missile.getDamageAmount();
        DamageType type = missile.getDamageType();
        WireAckedFluxWindow flux = WireAckedFluxWindow.arm(own, damage, type);
        float fluxRise = 0f;
        float hullDealt = 0f;
        float armorDealt = 0f;
        boolean restoreFailed = false;
        if (flux != null) {
            com.fs.starfarer.api.combat.FluxTrackerAPI ft = own.getFluxTracker();
            float armedFlux = ft.getCurrFlux();
            float preHull = own.getHitpoints();
            float preArmor = MerlonArmorCensus.armorTotal(own);
            try {
                // emp = 0 (as designed; EMP is a hull-branch component — the shield took this hit)
                mpl.plugin.engine.applyDamage(own, contact, damage, type, 0f, false, false, source, true);
                fluxRise = ft.getCurrFlux() - armedFlux;
                hullDealt = Math.max(0f, preHull - own.getHitpoints());
                if (hullDealt > 0f) {
                    mpl.plugin.mirrorHull(own, preHull); // the F2 guard: a hull component never shows as a dip
                }
                // the hull branch writes armor cells too -- NAMED on the
                // row (+armor_unrestored), not restored: the per-cell restore is QUEUED behind a CALLEE CHECK
                // against the own ArmorCellLane shadow writes + biteState. NaN on either side -> 0.
                float postArmor = MerlonArmorCensus.armorTotal(own);
                armorDealt = preArmor > postArmor ? preArmor - postArmor : 0f;
            } finally {
                restoreFailed = !flux.restore(); // a failed restore is a named residue
            }
        }
        com.fs.starfarer.api.loading.MissileSpecAPI spec = missile.getSpec();
        boolean visual;
        if (spec != null && spec.getExplosionSpec() == null) {
            // A spec with NO explosionSpec (the Locust class) has no blast to clone — its
            // own impact visual IS the engine's hit glow, drawn by explode() and nothing else
            // (null explosionSpec + a non-prox AI draws the glow when
            // isUseHitGlowWhenDealingDamage && explosionRadius > 0, spawns NO DamagingExplosion, returns null),
            // so the F1/F2 second-hit hazard of the explosionSpec class does not exist here. Posed at the ring
            // contact (where a real contact leaves the missile centre), intangible first (explodeAt); the
            // caller's retire removes the body. The visual IS the
            // engine's own act; whether it draws is its own spec-flag choice (a native hit on a glow-off spec
            // draws nothing either) — never a residue; the row's spec= names a glow-off
            // present, and the glow meter below counts only glows the engine drew (it is NOT
            // present − glow — the explosionSpec arm counts present without glow, a glow-on residue the reverse).
            explodeAt(missile, contact);
            visual = true;
            if (spec.isUseHitGlowWhenDealingDamage() && spec.getExplosionRadius() > 0f) {
                foeImpactOwnShieldGlow++;
            }
        } else {
            com.fs.starfarer.api.loading.DamagingExplosionSpec inert = source == null ? null // the ctor derefs the source
                    : inertBlastSpec(specId, spec == null ? null : spec.getExplosionSpec());
            if (inert != null) {
                mpl.plugin.engine.spawnDamagingExplosion(inert, source, contact);
            }
            visual = inert != null;
        }
        return ownShieldActToken(flux != null, fluxRise, hullDealt, armorDealt, flux != null && flux.stoppedOverload,
                visual, restoreFailed);
    }

    /** {@code ShieldAPI.isWithinArc} floors the active arc at 1 degree
     * while the engine's own shield-branch test
     *  does not — under 1 degree the gate could admit a
     *  point the engine sends to the HULL. Refused (the row says {@code own_ring=arc}). Pure; NaN fails closed. */
    static boolean ownRingArcUsable(float activeArc) {
        return activeArc >= 1f;
    }

    /** The torpedo's explosionSpec cloned ONCE per spec id with both collision classes NONE (a clone per
     *  hit would be frame-loop work): the engine draws it ({@code DamagingExplosion.init}:
     *  particles, detailed explosion, flash; the spec's sound set plays at spawn), the grid never pairs it
     *  (the grid skips NONE). Null when the spec carries none. One entry
     *  per torpedo spec seen, for the lane's life. */
    private final java.util.Map<String, com.fs.starfarer.api.loading.DamagingExplosionSpec> inertBlastBySpec =
            new java.util.HashMap<>();

    private com.fs.starfarer.api.loading.DamagingExplosionSpec inertBlastSpec(String specId,
            com.fs.starfarer.api.loading.DamagingExplosionSpec base) {
        if (base == null || specId == null) {
            return null;
        }
        com.fs.starfarer.api.loading.DamagingExplosionSpec inert = inertBlastBySpec.get(specId);
        if (inert == null) {
            inert = base.clone();
            inert.setCollisionClass(com.fs.starfarer.api.combat.CollisionClass.NONE);
            inert.setCollisionClassByFighter(com.fs.starfarer.api.combat.CollisionClass.NONE);
            inertBlastBySpec.put(specId, inert);
        }
        return inert;
    }

    /** The clean own-shield act token: the engine absorbed the direct hit on the shield, the hull stood,
     *  the window restored quietly and the blast drew. */
    static final String OWN_SHIELD_ACT_CLEAN = "shield";

    /** The own-shield act's row token, pure (P7f): {@link #OWN_SHIELD_ACT_CLEAN},
     *  else every residue named — {@code absent:unarmed} (no flux window = no ripple, fail-closed),
     *  {@code absent:no-shield-flux} (the engine took no shield flux: the point missed the arc),
     *  {@code +hull_restored} (a hull component the F2 guard undid), {@code +armor_unrestored} (the same hull
     *  branch's armor-cell write, named and left), {@code +overload_stopped} (max flux
     *  below the hit's shield flux: the engine's synchronous one-shots fired), {@code +noblast} (a null
     *  source or spec on the explosionSpec arm: the torpedo's blast could not draw; a no-explosionSpec
     *  spec's glow is the engine's own spec-flag choice, never a residue), {@code +restore_failed} (the
     *  window's restore threw — the flux pin rewrites the tracker next frame, but the
     *  frame in between is named). A residue on
     *  this row is graded, never hidden. */
    static String ownShieldActToken(boolean armed, float fluxRise, float hullDealt, float armorDealt,
            boolean overloadStopped, boolean blast, boolean restoreFailed) {
        StringBuilder t = new StringBuilder(!armed ? "absent:unarmed"
                : !(fluxRise > 0f) ? "absent:no-shield-flux" : OWN_SHIELD_ACT_CLEAN);
        if (hullDealt > 0f) {
            t.append("+hull_restored");
        }
        if (armorDealt > 0f) {
            t.append("+armor_unrestored");
        }
        if (overloadStopped) {
            t.append("+overload_stopped");
        }
        if (!blast) {
            t.append("+noblast");
        }
        if (restoreFailed) {
            t.append("+restore_failed");
        }
        return t.toString();
    }

    /** A presentation bound, not a sim rule: the wire end pose is where the SERVER delisted the missile
     *  and a flameout contact lands inside the victim's collision circle; the slack covers pose
     *  quantisation + one tick of victim motion. */
    static final float FIZZLING_VICTIM_REACH_SLACK_SU = 50f;

    /** The FIZZLING admission of {@link #presentImpactFxOwned}:
     *  the resolved server-named victim when the wire end pose lies within
     *  {@code getCollisionRadius() + FIZZLING_VICTIM_REACH_SLACK_SU} of it, else null with ONE refusal
     *  counted (unnamed / unresolved / far — lens finding 5: a pass-through missile that damaged a ship
     *  and fizzled far from it must never snap onto that stale victim). */
    private ShipAPI fizzlingVictimAdmit(cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        long victimId = event.endVictimWireId();
        if (victimId <= 0L) {
            foeFizzlingVictimUnnamed++;
            return null;
        }
        ShipAPI victim = resolveShip(victimId);
        if (victim == null) {
            foeFizzlingVictimUnresolved++;
            return null;
        }
        Vector2f at = victim.getLocation();
        float dx = at.x - event.endX();
        float dy = at.y - event.endY();
        float reach = victim.getCollisionRadius() + FIZZLING_VICTIM_REACH_SLACK_SU;
        if (dx * dx + dy * dy <= reach * reach) {
            return victim;
        }
        foeFizzlingVictimFar++;
        return null;
    }

    /** The VICTIM_MISMATCH row on its OWN budget
     *  ({@link #VICTIM_MISMATCH_LOG_MAX}), announce-once per wire missile id; a cap refusal counts
     *  {@code predict_missile_foe_impact_victim_mismatch_rows_capped}. {@code local_target} = the
     *  local target's wire id when it maps ({@link PredictReconcileCombatPlugin#wireIdForLocalShip}),
     *  else its class SimpleName; a station-module target adds {@code local_module_parent=} (is its
     *  parent the wire victim — the module-vs-parent false-count hypothesis, measured, not
     *  filtered). {@code door=} = what the end got. Bounded tokens only (ids, spec id, enum). Called
     *  on a mismatch end only: O(rows) id scan + one remote-entity scan, no per-frame cost. */
    private void logVictimMismatch(long tick, long wireId, String specId,
            com.fs.starfarer.api.combat.CombatEntityAPI localTarget, ShipAPI wireVictim,
            long wireVictimId, FoeImpactDoorLaw.Door door) {
        for (int i = 0; i < victimMismatchRows; i++) {
            if (victimMismatchLoggedIds[i] == wireId) {
                return; // announce-once per wire missile id (the mismatch meter still counted it)
            }
        }
        if (victimMismatchRows >= VICTIM_MISMATCH_LOG_MAX) {
            foeImpactVictimMismatchRowsCapped++;
            return;
        }
        victimMismatchLoggedIds[victimMismatchRows++] = wireId;
        long localWireId = localTarget instanceof ShipAPI
                ? mpl.plugin.wireIdForLocalShip((ShipAPI) localTarget)
                : cc.starsector.server.runtime.PilotIntentFrame.TARGET_NONE;
        Object parent = parentStationOf(localTarget);
        PilotWatchLog.info("predict v16 foe impact VICTIM_MISMATCH: tick=" + tick + " id=" + wireId
                + " spec=" + specId
                + " local_target=" + (localWireId != cc.starsector.server.runtime.PilotIntentFrame.TARGET_NONE
                        ? String.valueOf(localWireId) : localTarget.getClass().getSimpleName())
                + (parent == null ? "" : " local_module_parent=" + (parent == wireVictim ? "wire_victim" : "other"))
                + " wire_victim=" + wireVictimId + " door=" + door.name());
    }

    /** The shared victim snap (the earlier recipe unchanged, extracted):
     * the nearest det-eligible body whose fuse-shaped reach contains {@code loc}, or null. */
    private ShipAPI nearestDetEligible(int owner, Vector2f loc) {
        ShipAPI snapped = null;
        float bestD2 = Float.MAX_VALUE;
        for (ShipAPI ship : mpl.plugin.engine.getShips()) {
            if (!MineBombEndFxLane.detEligible(ship, owner)) {
                continue;
            }
            float reach = IMPACT_SLACK_SU + MineBombEndFxLane.edgeToward(ship, loc);
            float dx = ship.getLocation().x - loc.x;
            float dy = ship.getLocation().y - loc.y;
            float d2 = dx * dx + dy * dy;
            if (d2 <= reach * reach && d2 < bestD2) {
                bestD2 = d2;
                snapped = ship;
            }
        }
        return snapped;
    }

    /** Thin delegates kept after the {@link DevourEndLane} carve for the headless rows that name
     *  them ({@code MineNativeFuseLawSmoke} F6); the verdict and its codes live in the lane. */
    static final int DEVOUR_MINT = DevourEndLane.DEVOUR_MINT;
    static final int DEVOUR_SKIP_SHIELD = DevourEndLane.DEVOUR_SKIP_SHIELD;
    static final int DEVOUR_SKIP_UNNAMED = DevourEndLane.DEVOUR_SKIP_UNNAMED;
    static final int DEVOUR_SKIP_CAUSE = DevourEndLane.DEVOUR_SKIP_CAUSE;

    static int devourEndVerdict(int cause, long victim) {
        return DevourEndLane.devourEndVerdict(cause, victim);
    }

    /** Wire id → local ShipAPI: the own ship via the session id, else the PRCP
     *  replica registry when {@code registryShape}, else the 1v1 foe. {@code null} = no local body
     *  for that wire id. Delegates to the ONE
     *  shared {@link WireShipResolver#resolve} (the null-plugin belt lives there). */
    ShipAPI resolveShip(long wireId) {
        return WireShipResolver.resolve(mpl.plugin, wireId);
    }

    /** The DESTROYED death puff: the server really killed this missile there
     *  (PD class), so the quiet retire was eating a truthful puff. The engine-authoritative-first law: drive the
     *  ENGINE's native death, never draw the FX by hand — a lethal public-API
     *  {@code applyDamage} (≥2×maxHP, HE, bypassShields) on the real local body routes into
     *  the verified {@code Missile.applyDamage} hp≤0 branch (UI ping +
     *  spec-true explosion/hit-glow + debris + explosionSound + removeObject), applied at the
     *  LOCAL body pose (puppets are already teleported to the confessed pose).
     *  Ship damage never rides this — the target is the missile body itself, and null source
     *  keeps attribution empty ({@code Missile.applyDamage} reads source only via
     *  {@code instanceof Ship}). Specialized families (prox/canister/conversion) keep their
     *  own end-FX lanes — same exclusions as the impact recipe (the FX-ownership
     *  bar). Callers still retire/remove behind their in-play guards (fail-soft when the
     *  lethal hit under-kills). Package-shared with {@link BallisticEndLinkLane} like
     *  {@link #presentImpactFx}; counters stay at the call sites. TRUE = driven. */
    /**
     * fidelity-map L1-G3: a Threat unstable-fragment carrier's end is vanilla's
     * proximity-fuse detonation ({@code unstable_fragment_missile.proj}: 120-radius red
     * explosion + {@code unstable_fragment_explosion}), which the server owns (damaging, never
     * mirrored) — the client saw only the generic 80-su orange impact puff ("proxy-det things").
     * Presents the VISUAL + SOUND at the confessed end pose for the ends vanilla's fuse actually
     * dets on ({@code ProximityFuseAI.advance}): INTACT
     * (proximity trigger, {@code explode()}) and FIZZLING ({@code isFading()} — the
     * fuse dets a fading carrier rather than letting it dud), NEVER DESTROYED (a PD kill removes
     * the missile before the fuse runs — no det, no damage; the "red det, no damage"
     * was THIS lane painting dets on PD kills). Force-despawns the carrier's local
     * follower swarm on the fizzle-branch timers so
     * it does not roil on after the flash. Both generic paths exclude this spec. The headless
     * probe's {@code unstable-census} row ({@code dets.born ≈ ends.fizzling + ends.intact})
     * confirms or refutes this keying — {@code dets.born ≈ 0} means the fuse is not advancing
     * headless and the fix moves server-side. {@code ownSourced}
     * = the carrier's wire source is the own ship: the FX still present, the own-damage
     * relay is skipped (the engine exempts a blast's source — {@link OwnDetLane} class javadoc).
     */
    boolean presentUnstableEnd(String specId, MissileAPI missile, long wireId,
            cc.starsector.client.net.ClientGameplayEdgeEvent event, boolean ownSourced) {
        if (!MissilePoseLane.MISSILE_POSE_APPLY || !isUnstableFragmentSpec(specId)) {
            return false;
        }
        int cause = event.endCauseCode();
        if (!cc.starsector.client.net.ClientGameplayEdgeEvent.endCauseIntactClass(cause) // v15: INTACT-class
                && cause != cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_FIZZLING) {
            return false; // DESTROYED = PD kill: vanilla's fuse never dets it (code truth)
        }
        if (!(Math.abs(event.endX()) <= WIRE_END_POSE_CEIL_SU)
                || !(Math.abs(event.endY()) <= WIRE_END_POSE_CEIL_SU)) {
            return false; // wire-value guard: non-finite/absurd confessed pose never presents
        }
        try {
            if (mpl.plugin.engine == null) {
                return false;
            }
            Vector2f loc = new Vector2f(event.endX(), event.endY());
            Vector2f still = new Vector2f();
            mpl.plugin.engine.spawnExplosion(loc, still, UNSTABLE_EXPLOSION_COLOR,
                    UNSTABLE_EXPLOSION_RADIUS, 0.6f); // visual only — damage is the server's
            mpl.plugin.engine.addHitParticle(loc, still, UNSTABLE_EXPLOSION_RADIUS * 0.8f, 1f, 0.35f,
                    UNSTABLE_FLASH_CORE);
            Global.getSoundPlayer().playSound(UNSTABLE_EXPLOSION_SOUND, 1f, 1f, loc, still);
            // Relay the blast to the own-ship local apply lane (the MINE/BOMB
            // path — vanilla falloff vs our own hull, one capped hit, reconcile-clamped to the
            // wire hull) so the det the local player SEES also shows its damage number; before this the det
            // was paint-only and the server's real blast arrived as a silent armour reconcile.
            if (mpl.plugin.ownDetLane != null) {
                mpl.plugin.ownDetLane.onDet(loc, specId, ownSourced); // own-SOURCED = engine-exempt
            }
            int swarmMembers = -1;
            if (missile != null) {
                RoilingSwarmEffect follower = RoilingSwarmEffect.getSwarmFor(missile);
                if (follower != null) {
                    RoilingSwarmEffect.RoilingSwarmParams p = follower.getParams();
                    p.minDespawnTime = 0.5f;
                    p.maxDespawnTime = 1f;
                    p.minFadeoutTime = 0.5f;
                    p.maxFadeoutTime = 1f;
                    follower.setForceDespawn(true);
                    swarmMembers = follower.getNumActiveMembers();
                }
            }
            mpl.lane.log("predict v16 foe unstable-end: id=" + wireId + " cause=" + cause
                    + " at=" + (int) loc.x + "," + (int) loc.y + " follower_members=" + swarmMembers
                    + " (vanilla fuse explosion 120/red + sound, local visual; damage server-side)");
            return true;
        } catch (Throwable t) {
            mpl.lane.log("predict v16 foe unstable-end THREW id=" + wireId + " " + t);
            return false;
        }
    }

    /** The death-puff eligibility gate, pure (smoke
     *  ProxChargeDoorLawSmoke P6): wire DESTROYED only, canister + conversion families excluded
     *  (their own end lanes own them). The prox-charge exclusion is DROPPED while the engine door
     *  is ON: a PD kill (hp&lt;=0) is vanilla's plain missile death for this family too (no fuse
     *  det — lane B2 Q3), and the door itself returns on DESTROYED, so the generic puff is the
     *  truthful look. Door OFF keeps the exclusion (the painted path presents the end). */
    static boolean deathPuffEligible(int endCauseCode, String specId) {
        return endCauseCode == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_DESTROYED
                // The fuse-det family (prox charge + script bomb)
                && !(MineBombEndFxLane.isFuseDetSpec(specId) && !MineBombEndFxLane.PROX_ENGINE_DOOR)
                && !MineBombEndFxLane.isCanisterSpec(specId)
                && !DroneStrikeFxLane.isConversionSpec(specId);
    }

    /** The DESTROYED (PD kill) look: the engine's own lethal applyDamage on the live body.
     *  Gate = {@link #deathPuffEligible} (prox charges now
     *  admitted, counted); the catch counts. */
    boolean presentDeathPuff(String specId, MissileAPI missile, long wireId,
            cc.starsector.client.net.ClientGameplayEdgeEvent event, String clsLabel) {
        if (!MissilePoseLane.MISSILE_POSE_APPLY
                || missile == null
                || !deathPuffEligible(event.endCauseCode(), specId)) {
            // The unstable-fragment carrier is NOT excluded any more — a PD
            // kill (DESTROYED) is vanilla's plain missile death (no fuse det), so the truthful
            // shoot-down puff is its look; presentUnstableEnd owns INTACT + FIZZLING only.
            return false;
        }
        try {
            if (mpl.plugin.engine == null || !mpl.plugin.engine.isEntityInPlay(missile)) {
                return false;
            }
            Vector2f at = new Vector2f(missile.getLocation());
            float lethal = 2f * Math.max(1f, missile.getMaxHitpoints()) + 500f;
            // The killer feed skips this own puff's report
            // (it would overwrite a real local PD killer); the finally clears the flag even on a throw.
            mpl.plugin.dmgWitness.missileKillerPresenting(true);
            try {
                mpl.plugin.engine.applyDamage(missile, at, lethal, DamageType.HIGH_EXPLOSIVE,
                        0f, true, false, null);
            } finally {
                mpl.plugin.dmgWitness.missileKillerPresenting(false);
            }
            if (MineBombEndFxLane.isProxChargeSpec(specId)) {
                proxEndDestroyedPuff++;
            }
            mpl.lane.log("predict v16 foe death-puff: id=" + wireId + " spec=" + specId
                    + " at=" + (int) at.x + "," + (int) at.y + " cls=" + clsLabel);
            return true;
        } catch (Throwable e) {
            // presentation lane; never break the watch (counted, announced once)
            deathPuffFailures++;
            FallbackAnnounce.announceOnce("death-puff-failed",
                    "presentDeathPuff threw " + e.getClass().getSimpleName()
                    + " (first id=" + wireId + " spec=" + specId + ")");
            return false;
        }
    }

    /** Spliced into the MPL meter line beside the end lane's token block (the split/own
     *  meterTokens discipline — the line stays append-consistent). */
    String meterTokens() {
        return " predict_missile_sourced_end_retired=" + sourcedEndRetired
                // The own-twin wire-victim hit presenter.
                + " predict_missile_own_victim_hit_applied=" + ownVictimHitApplied
                + " predict_missile_own_impact_explode_door=" + ownImpactExplodeDoor //
                + " predict_missile_own_impact_door_skipped=" + ownImpactDoorSkipped
                + " predict_missile_own_impact_door_fault=" + ownImpactDoorFault
                + " predict_missile_own_victim_hit_untreated=" + ownVictimHitUntreated
                + " predict_missile_own_victim_hit_no_body=" + ownVictimHitNoBody
                + " predict_missile_own_victim_hit_failures=" + ownVictimHitFailures
                + " predict_missile_own_victim_hit_fizzling=" + ownVictimHitFizzling
                + " predict_missile_victim_end_twinless_any_source=" + anySourceVictimEndTwinless
                + " predict_missile_own_victim_end_local_first=" + ownVictimEndLocalFirst
                + " predict_missile_own_victim_hit_shield_miss=" + ownVictimHitShieldMiss
                + " predict_missile_foe_impact_presented=" + foeImpactPresented
                // The FoeImpactDoorLaw door split.
                + " predict_missile_foe_impact_explode_door=" + foeImpactExplodeDoor
                + " predict_missile_foe_impact_native_done=" + foeImpactNativeDone
                + " predict_missile_foe_impact_paint_residue_bodyless=" + foeImpactPaintBodyless
                + " predict_missile_foe_impact_paint_residue_body_gone=" + foeImpactPaintBodyGone
                + " predict_missile_foe_impact_paint_residue_explosion_spec=" + foeImpactPaintExplosionSpec
                + " predict_missile_foe_impact_paint_residue_glow_off=" + foeImpactPaintGlowOff
                + " predict_missile_foe_impact_present_fault=" + foeImpactPresentFault
                + " predict_missile_foe_impact_native_done_victim_mismatch=" + foeImpactNativeDoneVictimMismatch
                + " predict_missile_foe_impact_victim_mismatch_presented=" + foeImpactVictimMismatchPresented
                + " predict_missile_foe_impact_victim_mismatch_rows_capped=" + foeImpactVictimMismatchRowsCapped
                + " predict_missile_foe_impact_pose_ceiling_drop=" + foeImpactPoseCeilingDrop
                + " predict_missile_foe_unstable_ends=" + foeUnstableEnds
                + " predict_missile_foe_death_puff=" + foeDeathPuffDriven
                + devour.meterTokens() // Carve: the devour end-hook block, same position
                + " predict_devour_presented=" + ReplicaDevourPresenter.presented
                + " predict_devour_members_carried=" + ReplicaDevourPresenter.membersCarried
                + " predict_devour_ticks_no_yield=" + ReplicaDevourPresenter.ticksNoYield
                // Yielding ticks that printed vanilla's floaty vs
                // hidden by Misc.shouldShowDamageFloaty; Σ vanilla-arithmetic hull + Σ armor printed.
                + " predict_devour_ticks_printed=" + ReplicaDevourPresenter.ticksPrinted
                + " predict_devour_ticks_hidden=" + ReplicaDevourPresenter.ticksHidden
                + " predict_devour_hull_tick_sum=" + Math.round(ReplicaDevourPresenter.hullTickSum)
                + " predict_devour_armor_printed_sum=" + Math.round(ReplicaDevourPresenter.armorPrintedSum)
                // Appended, append-consistent.
                + " predict_missile_own_victim_hit_skip_local_hit_first=" + ownVictimHitSkipLocalHitFirst
                + " predict_missile_twinless_victim_end_failures=" + twinlessVictimEndFailures
                // Appended, append-consistent.
                + " predict_missile_prox_end_destroyed_puff=" + proxEndDestroyedPuff
                + " predict_missile_death_puff_failures=" + deathPuffFailures
                // The foe FIZZLING+victim door, appended (order kept).
                + " predict_missile_foe_fizzling_explode_door=" + foeFizzlingExplodeDoor
                + " predict_missile_foe_fizzling_paint_skipped=" + foeFizzlingPaintSkipped
                + " predict_missile_foe_fizzling_victim_unnamed=" + foeFizzlingVictimUnnamed
                + " predict_missile_foe_fizzling_victim_unresolved=" + foeFizzlingVictimUnresolved
                + " predict_missile_foe_fizzling_victim_far=" + foeFizzlingVictimFar
                // the never-rolled explode meter renamed present + its residue twin.
                + " predict_missile_foe_impact_own_shield_present=" + foeImpactOwnShieldPresent
                + " predict_missile_foe_impact_own_shield_residue=" + foeImpactOwnShieldResidue
                + " predict_missile_foe_impact_own_shield_glow=" + foeImpactOwnShieldGlow;
    }
}
