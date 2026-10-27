package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.HashMap;
import java.util.Map;

/**
 * The mine/bomb END-OF-LIFE presentation collaborator split out of {@code MissilePoseLane}
 *
 * ({@code MissilePoseLane}-owned, the FFC backref shape; behavior-identical move): the kind-6
 * {@link WireEnd} store + intake, the prime detection + windup pass, the wire-truth
 * det classifier + mine det FX, the phase-charge end FX with the fuse-envelope snap,
 * and the radius-faithful det-point impact sweep. {@code MissilePoseLane} keeps the shared
 * Twin ledger (prime/windup state rides Twin fields — the shared-ledger precedent), the
 * iteration passes, and thin dispatches. Spawn-side mine FX (fade + {@code mine_spawn}) is the
 * ADOPT concern, not end-of-life — it stays in {@code adoptSourceless}. The CANISTER end
 * concern (knob, gate, classifier + snap, meters) lives in the {@link CanisterEndFxLane}
 * sub-collaborator since the carve; this lane keeps the kind-6 store + the
 * package-static geometry helpers both lanes share, and relays so MPL stays byte-identical.
 *
 * <p>Render-thread only, fail-soft throughout; logs through the owning lane's capped budget
 * ({@code mpl.lane} — shared accounting unchanged by the move).
 */
final class MineBombEndFxLane {

    /**
     * The BOMB end-FX knob,
     * {@code cc.predict.bombEndFx} = {@code on|off}, DEFAULT ON: a phase-charge puppet
     * ({@code phase_charge_bomber} — the Flash bomber's live munition; the plain contact
     * {@code bomb} is deliberately excluded) whose track ends NOT wire-FIZZLING gets a
     * damage-less explosion + {@code prox_charge_explosion} at its last wire pose. Vanilla law:
     * a phase charge ALWAYS explodes at end of life — prox-trigger, contact, or fade-trigger
     * — so the explosion is the
     * correct DEFAULT end, not a special case; the bare {@code removeEntity} poof was the reported
     * defect. Known residue: a server-side PD kill (hp≤0) vanishes silently in vanilla
     * but ends absent-not-fizzling on the wire, so it presents the same explosion — the wire
     * carries no det/hit/expire bit and the common end IS the explosion.
     * {@code off} = the old bare poof, byte-for-byte. Invalid values THROW.
     */
    static final boolean BOMB_END_FX = parseOnOff("cc.predict.bombEndFx",
            System.getProperty("cc.predict.bombEndFx", "on"));

    /**
     * The MINE prime-detection knob, {@code cc.predict.minePrimeFx} =
     * {@code on|off}, DEFAULT ON. Vanilla law: the fuse triggers
     * on proximity OR on {@code isFading()} end-of-life, so every mine that lives out
     * its track ends in the 3 s ping→windup→boom (trigger STOPS the mine); only a
     * shot-down (hp≤0) mine vanishes silently. ON: the client reconstructs that sequence from
     * the pose-history STOP (the prime tell — no wire bit needed): ping at the confirmed stop,
     * {@code mine_windup_heavy} ~1 s before the expected boom, and the end-of-track explosion
     * fires for every PRIMED (or fuse-envelope-near) end — quiet is only the never-primed
     * moving end (shot down in flight). OFF = the older near-guess classifier,
     * byte-for-byte (the A/B lever). Invalid values THROW.
     */
    static final boolean MINE_PRIME_FX = parseOnOff("cc.predict.minePrimeFx",
            System.getProperty("cc.predict.minePrimeFx", "on"));

    /**
     * The mine DET-POINT impact knob, {@code cc.predict.mineDetImpactFx} =
     * {@code on|off}, DEFAULT ON: at a presented mine det, every body inside
     * the TRUE AoE — explosionSpec radius 250 + its facing edge; BOTH sides + wrecks, because
     * {@code minelayer_mine.proj} explosionSpec is {@code MISSILE_FF} (vanilla friendly damage,
     * a deliberate trade) — gets a hit-flash at its facing surface (shield ring
     * when the replica shield is raised toward the det). STRICTLY radius-faithful (the
     * truth key): a body outside the AoE never flashes, so the FX cannot paint damage the
     * server never dealt — flux/armor numbers stay wire-authoritative, this only marks WHERE
     * the authoritative AoE landed. {@code off} = the det explosion alone, byte-for-byte.
     * Compiled ON (E3 hardwire; the
     * {@code cc.predict.mineDetImpactFx} knob-resolved shape is in git).
     */
    static final boolean MINE_DET_IMPACT_FX = true;

    /**
     * The BOMB impact knob, {@code cc.predict.bombImpactFx} = {@code on|off},
     * DEFAULT ON: a phase-charge end whose last wire pose sits inside the fuse envelope
     * (behaviorSpec range 40 + the target's facing edge) of a body hostile to the bomb SNAPS
     * the end explosion to that body's facing surface (shield ring when raised toward the
     * pose) and adds a hit-flash there — the reported defect: flash bombs read
     * as crossing the own shield with no det cycle (the explosion landed at the stale
     * last wire pose while the puppet overflew through the G-frame absence grace). The
     * proximity test mirrors the engine's own fuse shape, so a snap
     * can only land where the server fuse could actually have fired; a far end keeps the
     * placement. Presentation-only either way — own/foe damage stays wire-authoritative.
     * {@code off} = the last-wire-pose placement, byte-for-byte. Invalid values THROW.
     */
    static final boolean BOMB_IMPACT_FX = parseOnOff("cc.predict.bombImpactFx",
            System.getProperty("cc.predict.bombImpactFx", "on"));

    /**
     * The
     * prox-charge ENGINE DOOR, compiled ON (the E3 hardwire idiom of {@link #MINE_DET_IMPACT_FX}).
     * Engine law: {@code phase_charge(_bomber).proj} carries its explosionSpec ONLY
     * inside the PROXIMITY_FUSE behaviorSpec and {@code flameoutTime == fadeTime}, so
     * {@code Missile.isFading() == isFizzling()} and {@code ProximityFuseAI.advance} calls its
     * private {@code explode()} (removeObject + the REAL DamagingExplosion 500 HE r150 + sound +
     * particles + flash) on the first fading frame: EVERY vanilla prox-charge end is a detonation
     * (fade / prox / contact); only a PD kill (hp&lt;=0) dies without one. {@code MissileAPI.explode()}
     * is NOT a door for this family (hit-glow only). ON: at the wire END the puppet
     * body gets the engine's OWN fuse ({@code CombatEngineAPI.createProximityFuseAI}) and the relay's
     * park idiom {@code flameOut()} — the engine's own fade trigger then detonates it (sound, FX and
     * the local damage entity all engine-native); the wire FIZZLING relay is skipped for the family
     * ({@code MissilePoseLane}) and the DESTROYED end takes the generic death puff
     * ({@code SourcedMissileEndLane.deathPuffEligible}). {@code false} = the painted path,
     * byte-for-byte (relay + puff exclusion included). Do NOT confuse with {@code cc.predict.bombImpactFx}
     * (that knob names the snap, which now lives only on the painted fallback).
     */
    static final boolean PROX_ENGINE_DOOR = true;

    /** {@link #proxEndVerdict} outcomes (pure; the smoke rows P1). */
    static final int PROX_END_QUIET = 0;          // a pre-door relay already flamed the twin out
    static final int PROX_END_PUFF = 1;           // wire DESTROYED: PD kill, the generic death puff owns it
    static final int PROX_END_LOCAL_FIRST = 2;    // the body already left play locally
    static final int PROX_END_OWN_LOCAL_FIRST = 3;// own-sourced + own tombstone: the own seat's body ended first
    static final int PROX_END_DET = 4;            // present the det (door or painted fallback)
    /** {@link #proxEndVerdict} cause sentinel: no kind-6 record (the G=6 absence end). */
    static final int PROX_CAUSE_NONE = -1;
    /** {@link #doorRoute} outcomes (smoke rows P4). */
    static final int PROX_ROUTE_DOOR = 0;
    static final int PROX_ROUTE_PAINT = 1;

    /** Alias for the carved-out canister sub-collaborator's knob (law +
     *  provenance live in {@link CanisterEndFxLane}); kept here so the MPL call sites stay
     *  byte-identical across the carve. */
    static final boolean CANISTER_FX = CanisterEndFxLane.CANISTER_FX;

    // The MINE_DET_NEAR_SU fuse-envelope constant retired with the primed/near
    // classifier — quiet-vs-det is wire-truth now (kind-6 END_CAUSE, det by default).
    /** minelayer_mine.proj explosionSpec radius / explosionColor — the det visual. */
    private static final float MINE_DET_FX_SIZE = 250f;
    private static final float MINE_DET_FX_SECONDS = 1.0f;
    private static final java.awt.Color MINE_DET_FX_COLOR = new java.awt.Color(255, 100, 50, 255);
    /** phase_charge_bomber.proj explosionSpec — radius / explosionColor / sound (read
     *  first-hand at build): r150/core75, [155,125,255,255], prox_charge_explosion. */
    private static final float BOMB_END_FX_SIZE = 150f;
    private static final float BOMB_END_FX_SECONDS = 0.75f;
    private static final java.awt.Color BOMB_END_FX_COLOR = new java.awt.Color(155, 125, 255, 255);
    /** phase_charge_bomber.proj behaviorSpec.range — the prox-fuse envelope (su) the snap
     *  mirrors (+ the target's facing edge, the fuse's own proximity shape, re-read first-hand
     *  at build). */
    private static final float BOMB_FUSE_NEAR_SU = 40f;
    /** minelayer_mine.proj behaviorSpec — delay 3 s / windupDelay 1 s / pingRadius 450 /
     *  pingColor [255,50,50,255] (the trigger() ping pair + primed windup). */
    private static final float MINE_DELAY_SECONDS = 3f;
    private static final float MINE_WINDUP_LEAD_SECONDS = 1f;
    private static final float MINE_PING_RADIUS = 450f;
    private static final java.awt.Color MINE_PING_COLOR = new java.awt.Color(255, 50, 50, 255);
    /** Prime detection: the mine hasn't left this bubble (su) for this long (s) ⇒ the fuse
     *  STOPPED it (primed). Bubble beats a per-tick velocity estimate — wire pose quantization
     *  never fakes a stop, and a drifting unprimed mine keeps re-anchoring. Cost: the ping lands
     *  ~this many seconds after the engine's (windup/boom scheduling subtracts it back out). */
    private static final float MINE_STOP_EPS_SU = 8f;
    private static final float MINE_STOP_CONFIRM_SECONDS = 0.75f;

    /** Defensive bound — sourceless ends are volume-bounded (~hundreds/round); a runaway map
     *  is a feed defect (count it), never a reason for a bigger cap. */
    private static final int WIRE_END_CAP = 4096;

    static final class WireEnd {
        final int cause;
        final float x;
        final float y;
        /** The kind-6 event's SERVER tick (= the impact's hull-delta tick, 214322 reel) — the hull
         *  credit order bound of the conversion apply. */
        final long tick;

        WireEnd(int cause, float x, float y, long tick) {
            this.cause = cause;
            this.x = x;
            this.y = y;
            this.tick = tick;
        }
    }

    private final MissilePoseLane mpl;

    /** The MISSILE_END (GPEE kind 6) wire end-cause store: the server's
     *  post-mortem verdict keyed by missile wire id. Fed by the PRCP route-first relay
     *  (the mis-fold trap law), render thread. v5-class SOURCELESS entries (mine/system-spawn
     *  family) are consumed (remove-take) at the absence-confirmed track end; v7 SOURCED
     *  entries are consumed the SAME tick by {@link SourcedMissileEndLane}
     *  (twin-linked) or taken straight back out (twinless — the ballistic-blacklist volume
     *  class must never grow the store to its cap). Cleared at round reset. Value =
     *  END_CAUSE_* code + the last authoritative end pose (finite, split law). */
    private final Map<Long, WireEnd> wireEnds = new HashMap<Long, WireEnd>();

    private int mineDetFx;           // predict_missile_mine_det_fx (end-of-track det PAINTED —
                                     //   non-mine sourceless residue only)
    private int mineExplodeDoor;     // predict_missile_mine_explode_door (wire end ⇒
                                     //   MissileAPI.explode() on the live mine body)
    private int mineExplodeLocalFirst;// predict_missile_mine_explode_local_first (the
                                     //   body already detonated locally — nothing to explode)
    private int mineLocalLeadEnds;   // predict_missile_mine_local_lead_ends (twinless mine ends with
                                     //   a latched local tick — the lead/lag sample count)
    private long mineLocalLeadTicksSum; // predict_missile_mine_local_lead_ticks_sum (wire − local, t;
                                     //   positive = local det led the wire end)
    private int mineEndQuiet;        // predict_missile_mine_end_quiet (wire-verified
                                     //   DESTROYED hp-death — vanilla's only silent-ish end)
    // kind-6 wire end-cause delivery accounting at the mine classifier.
    private int mineEndWireDestroyed;// predict_missile_mine_end_wire_destroyed (quiet: hp-death)
    private int mineEndWireIntact;   // predict_missile_mine_end_wire_intact (det: the EOL class)
    private int mineEndWireFizzling; // predict_missile_mine_end_wire_fizzling (det; ~never, mines)
    private int mineEndWireNone;     // predict_missile_mine_end_wire_none (no record -> det default)
    private int wireEndsDropped;     // predict_missile_wire_ends_dropped (cap clears — feed defect)
    // The FX-EXP lifecycle arm (bomb end + mine prime reconstruction).
    private int bombEndFx;           // predict_missile_bomb_endfx (phase-charge end explosion)
    private int minePrimes;          // predict_missile_mine_primes (pose-stop prime detected)
    private int mineWindupFx;        // predict_missile_mine_windup_fx (windup sound played)
    // The det-point/impact presentation arm.
    private int mineDetImpactFx;     // predict_missile_mine_det_impact_fx (radius-faithful hits)
    private int bombImpactSnap;      // predict_missile_bomb_impact_snap (end snapped to a body)
    private int bombOwnImpactFx;     // predict_missile_bomb_own_impact (snap target = own ship)
    // The prox-charge engine door (PROX_ENGINE_DOOR).
    private int proxDoor;            // predict_missile_prox_door (engine fuse installed + flameOut)
    // The cause_* counters below tally every
    // DET-class verdict incl. local_first / own_local_first (they count BEFORE those branches).
    private int proxDoorCauseIntact; // predict_missile_prox_door_cause_intact (INTACT; incl. local_first)
    private int proxDoorCauseShield; // predict_missile_prox_door_cause_shield (SHIELD_ABSORBED; incl. local_first)
    private int proxDoorCauseFizzling;// predict_missile_prox_door_cause_fizzling (fade-det label; incl. local_first)
    private int proxDoorCauseNone;   // predict_missile_prox_door_cause_none (no record: absence end; incl. local_first)
    /** A cause code outside the known set
     *  (was silently lumped into cause_intact). */
    private int proxDoorCauseUnknown; // predict_missile_prox_door_cause_unknown
    /** prox_door counts fuse
     *  ARMS (flight: prox_door=29, 1 witnessed) — these name what the engine then DID. */
    private int proxDoorDetWitnessed; // predict_missile_prox_door_det_witnessed (engine det sighted)
    private int proxDoorSilent;       // predict_missile_prox_door_silent (body left play, no det seen)
    private int proxDoorHeldExpired;  // predict_missile_prox_door_held_expired (F5 backstop removal)
    private int proxDoorHeldEvicted;  // predict_missile_prox_door_held_evicted (held list cap overflow)
    private int proxDoorHeldFailures; // predict_missile_prox_door_held_failures (advanceHeld throws)
    private int proxDoorRadiusFallback; // predict_missile_prox_door_radius_fallback (spec radius unreadable)
    private int proxDoorCauseDestroyed;// predict_missile_prox_door_cause_destroyed (PD kill -> puff lane)
    private int proxDoorQuiet;       // predict_missile_prox_door_quiet (twin already relay-fizzled)
    private int proxDoorLocalFirst;  // predict_missile_prox_door_local_first (body out of play)
    private int proxDoorOwnLocalFirst;// predict_missile_prox_door_own_local_first (own tombstone)
    private int proxDoorFallback;    // predict_missile_prox_door_fallback (null source -> painted)
    private int proxDoorFailures;    // predict_missile_prox_door_failures (counted door throws)
    private int paintFailures;       // predict_missile_bomb_paint_failures (counted painted-path throws)
    /** The served Nova/Orion script-bomb end meters. */
    private int scriptBombDoor;      // predict_missile_scriptbomb_door (engine door ran on a script bomb)
    private int scriptBombPaint;     // predict_missile_scriptbomb_paint (M11 per-spec paint: null mint source)
    /** The carved-out canister end-of-life sub-collaborator (knob, family
     *  gate, classifier + snap, meter tokens); this lane keeps the kind-6 store + geometry
     *  helpers it borrows back. */
    private final CanisterEndFxLane canister;

    MineBombEndFxLane(MissilePoseLane mpl) {
        this.mpl = mpl;
        this.canister = new CanisterEndFxLane(mpl, this);
    }

    private static boolean parseOnOff(String knob, String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(value)) {
            return true;
        }
        if ("off".equals(value)) {
            return false;
        }
        throw new IllegalStateException(knob + " must be on|off; got '" + raw + "'");
    }

    /** Mine prime detection on the fresh-pose stream: the fuse's {@code trigger()}
     *  STOPS the mine ({@code slowToMaxSpeed} decel), so a mine
     *  that held one {@code MINE_STOP_EPS_SU} bubble for {@code MINE_STOP_CONFIRM_SECONDS} is
     *  PRIMED — present vanilla's own ping instant (trigger() verbatim: hit-particle pair
     *  at pingRadius + white core, {@code mine_ping}). Sound/particles are damage-less
     *  presentation; the server's fuse stays the only detonation authority. */
    void trackMinePrime(MissilePoseLane.Twin t, long tick) {
        if (!MissilePoseLane.MINE_FX || !MINE_PRIME_FX || !MissilePoseLane.MISSILE_POSE_APPLY
                || !t.sourcelessMine || t.fizzled || t.primedTick != Long.MIN_VALUE
                || t.mine) { // A mine body's OWN fuse AI pings — paint retired
            return;
        }
        if (t.refTick == Long.MIN_VALUE) {
            t.refX = t.lastWireX;
            t.refY = t.lastWireY;
            t.refTick = tick;
            return;
        }
        float dx = t.lastWireX - t.refX;
        float dy = t.lastWireY - t.refY;
        if (dx * dx + dy * dy > MINE_STOP_EPS_SU * MINE_STOP_EPS_SU) {
            t.refX = t.lastWireX;
            t.refY = t.lastWireY;
            t.refTick = tick;
            t.everMoved = true;
            return;
        }
        if (!t.everMoved
                || tick - t.refTick < MINE_STOP_CONFIRM_SECONDS * ServerTickRate.ticksPerSecond()) {
            return;
        }
        t.primedTick = tick;
        minePrimes++;
        try {
            Vector2f loc = new Vector2f(t.lastWireX, t.lastWireY);
            Vector2f still = new Vector2f();
            mpl.plugin.engine.addHitParticle(loc, still, MINE_PING_RADIUS, 1f, 0.25f,
                    MINE_PING_COLOR);
            mpl.plugin.engine.addHitParticle(loc, still, MINE_PING_RADIUS * 0.25f, 1f, 0.25f,
                    java.awt.Color.white);
            Global.getSoundPlayer().playSound("mine_ping", 1f, 1f, loc, still);
        } catch (Throwable ignore) {
            // presentation nicety; the prime latch (and the end classifier it feeds) stands
        }
        mpl.mineLane.log("predict v16 mine PRIME: id=" + t.wireId + " tick=" + tick
                + " at=" + (int) t.lastWireX + "," + (int) t.lastWireY);
    }

    /** The primed-mine windup pass (fresh frames): vanilla plays
     *  {@code mine_windup_heavy} once when the remaining delay drops under windupDelay=1 s.
     * The engine's boom lands delay=3 s after its OWN trigger;
     *  our prime confirm lags that by {@code MINE_STOP_CONFIRM_SECONDS}, so the due time
     *  subtracts it back out. */
    void driveMineWindup(long tick) {
        long dueTicks = Math.round((MINE_DELAY_SECONDS - MINE_STOP_CONFIRM_SECONDS
                - MINE_WINDUP_LEAD_SECONDS) * ServerTickRate.ticksPerSecond());
        for (MissilePoseLane.Twin t : mpl.twins.values()) {
            if (t.primedTick == Long.MIN_VALUE || t.windupPlayed
                    || tick - t.primedTick < dueTicks
                    || t.mine) { // The engine fuse plays the windup — paint retired
                continue;
            }
            t.windupPlayed = true;
            mineWindupFx++;
            try {
                Global.getSoundPlayer().playSound("mine_windup_heavy", 1f, 1f,
                        new Vector2f(t.lastWireX, t.lastWireY), new Vector2f());
            } catch (Throwable ignore) {
                // presentation nicety
            }
        }
    }

    /** The absence-confirmed track end (the tickAbsence dispatch): remove-take the
     *  kind-6 verdict for EVERY ended track (bombs included — entries must not outlive their
     *  twin), hand it to the mine classifier, then the phase-charge end (no-op unless a
     *  non-fizzled phase charge) — same order as the pre-split inline pair — then the
     *  conversion det-damage relay (no-op unless a droneStrikeFx-carved terminator track).
     *  The bomb end now receives the removed kind-6
     *  verdict too (the prox-charge engine door's cause matrix + det pose). */
    void onTrackEnd(MissilePoseLane.Twin t, long tick) {
        WireEnd wireEnd = wireEnds.remove(Long.valueOf(t.wireId));
        presentMineEndFx(t, tick, wireEnd);
        presentBombEndFx(t, tick, wireEnd);
        presentConversionEndDet(t, tick, wireEnd);
        canister.presentEndFx(t, tick, wireEnd);
    }

    /** The {@code pruneLocalGone} (local-death) dispatch; law + guards live at
     *  {@link CanisterEndFxLane#onPruneExit} since the carve (thin relay so the MPL call
     *  site stays byte-identical). */
    void onCanisterPruneExit(MissilePoseLane.Twin t, long tick) {
        canister.onPruneExit(t, tick);
    }

    /** The end-of-track det classifier + FX, WIRE-TRUTH edition
     * (supersedes the earlier primed/near
     *  heuristic). Engine law: a vanilla EOL mine ALWAYS self-destruct-detonates
     *  (fade ⇒ trigger, no silent-fizzle path exists) and its
     *  det delists as {@code END_CAUSE_INTACT} (trigger un-fizzles, hp untouched); the ONLY
     *  vanilla silent-ish end is the shot-down hp-death ({@code DESTROYED}). So the classifier
     *  is: quiet needs a POSITIVE wire DESTROYED verdict (kind-6 store, remove-taken by
     *  {@link #onTrackEnd}); INTACT / FIZZLING / no-record present the det — the honest DEFAULT
     *  (the old heuristic's ~13% prime recall under-presented dets: the
     *  mine_end_quiet=211 defect). Det FX = the proj's explosionSpec as damage-LESS
     *  presentation at the wire end pose ({@code mine_explosion} + visual explosion) — the
     *  safety property is untouched (no {@code DamagingExplosion}, no local fuse; server
     *  det+AoE stay engine-authoritative). Fizzled twins already played the death arc.
     *  Timing: the FX lands at the G=6 absence confirm, ~0.1-0.2 s after the server det — the
     *  standing absence-grace class. */
    private void presentMineEndFx(MissilePoseLane.Twin t, long tick, WireEnd wireEnd) {
        if (!MissilePoseLane.MINE_FX || !MissilePoseLane.MISSILE_POSE_APPLY || !t.sourcelessMine
                || t.fizzled) {
            return;
        }
        try {
            if (wireEnd == null) {
                mineEndWireNone++;
            } else if (wireEnd.cause
                    == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_DESTROYED) {
                mineEndWireDestroyed++;
                mineEndQuiet++; // wire-verified hp-death — the death pop rode the wire fizzle/
                return;         // removal arc, never this lane; silence stays honest here
            } else if (wireEnd.cause
                    == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_FIZZLING) {
                mineEndWireFizzling++;
            } else {
                mineEndWireIntact++;
            }
            // Det pose: the kind-6 floats are the server's last authoritative missile pose —
            // prefer them over the client's last-seen wire sample (one feed-gap fresher).
            Vector2f loc = wireEnd != null ? new Vector2f(wireEnd.x, wireEnd.y)
                    : new Vector2f(t.lastWireX, t.lastWireY);
            if (t.mine) {
                // The ENGINE door. The body kept its fuse AI,
                // so MissileAPI.explode() IS vanilla's own det — removeObject + the spec's
                // DamagingExplosion (mine_explosion sound, detailed explosion, damage dealt to
                // every local body in r250 incl. the own ship via MISSILE_FF; the hull reconcile
                // folds it to wire truth after). Never removeObject, never a ghost-fade, never
                // a painted explosion here. A body already out of play detonated LOCALLY first
                // (the own-shot idiom; the pose lane latched its tick) — nothing to do.
                if (mpl.plugin.engine.isEntityInPlay(t.missile)) {
                    Vector2f body = t.missile.getLocation();
                    if (body != null) {
                        body.set(loc); // det where the SERVER said the mine was (one feed-gap fresher)
                    }
                    // Arm the door-det expectation FIRST, as the prox door does
                    // at presentProxDoor — the engine's local det is server-dealt (the door-det class), else a
                    // remote victim's hull takes the recipe's amount as a REPLICA apply and rebounds at the fold.
                    // It was dormant while the mine's explosion carried 0 (PresentedMintRecipe javadoc).
                    armDoorExpectation(t, loc);
                    t.missile.explode();
                    mineExplodeDoor++;
                    mpl.mineLane.log("predict v13 mine wire-end EXPLODE: id=" + t.wireId + " tick=" + tick
                            + " at=" + (int) loc.x + "," + (int) loc.y
                            + " wireCause=" + (wireEnd == null ? "none" : String.valueOf(wireEnd.cause))
                            + (t.own ? " own" : " foe"));
                } else {
                    mineExplodeLocalFirst++;
                }
                return;
            }
            Vector2f still = new Vector2f();
            mpl.plugin.engine.spawnExplosion(loc, still, MINE_DET_FX_COLOR, MINE_DET_FX_SIZE,
                    MINE_DET_FX_SECONDS);
            Global.getSoundPlayer().playSound("mine_explosion", 1f, 1f, loc, still);
            presentDetImpacts(loc);
            // Own det-damage relay (LAW in the lane); an own-SOURCED det is engine-exempt
            mpl.plugin.ownDetLane.onDet(loc, t.specId, mpl.plugin.ownDetLane.ownSourced(t));
            mineDetFx++;
            mpl.mineLane.log("predict v13 mine det-FX: id=" + t.wireId + " tick=" + tick
                    + " at=" + (int) loc.x + "," + (int) loc.y
                    + " wireCause=" + (wireEnd == null ? "none" : String.valueOf(wireEnd.cause)));
        } catch (Throwable ignore) {
            // presentation lane; never break the watch
        }
    }

    /** The phase-charge end-FX (the reported "Flash bombs poof" defect):
     *  a {@code phase_charge_bomber} track ending NOT wire-FIZZLING always exploded server-side
     *  (prox, contact, or fade trigger), so present its explosionSpec
     *  as damage-less FX at the last wire pose (r150 / [155,125,255] / prox_charge_explosion,
     *  read first-hand from the .proj at build). Fizzled twins already rode the :fizzle-relay
     *  death arc — vanilla's silent miss shape for the plain contact bomb family, which this
     *  spec-keyed gate excludes. Same safety property as the mine FX: no
     *  {@code DamagingExplosion}, no local fuse.
     *
     *  <p>SUPERSEDED as the default by the prox-charge
     *  ENGINE DOOR ({@link #PROX_ENGINE_DOOR}) — the "fizzled = silent miss" premise above is WRONG
     *  for this family (every vanilla prox-charge end detonates, the fade-det labels FIZZLING).
     * The dispatcher runs {@link #proxEndVerdict} over the wire cause; a DET goes
     *  through {@link #doorRoute}: the engine's own fuse ({@link #presentProxDoor}) or, for a body
     *  with no source (the fuse AI and DamagingExplosion both deref it), this painted path
     *  ({@link #presentBombEndPainted}, the code unchanged). Door OFF = today's gate and
     *  painted path, byte-for-byte. */
    private void presentBombEndFx(MissilePoseLane.Twin t, long tick, WireEnd wireEnd) {
        // The guard admits the fuse-det family (prox charge OR
        // the Nova/Orion script bomb, re-classed out of sourcelessMine at adopt); every painted
        // fallback below dispatches through presentFuseDetPainted (never phase-charge constants
        // on a script bomb).
        if (!BOMB_END_FX || !MissilePoseLane.MISSILE_POSE_APPLY || t.sourcelessMine
                || !isFuseDetSpec(t.specId)) {
            return;
        }
        if (!PROX_ENGINE_DOOR) {
            if (!t.fizzled) {
                presentFuseDetPainted(t, tick, wireEnd);
            }
            return;
        }
        int cause = wireEnd == null ? PROX_CAUSE_NONE : wireEnd.cause;
        boolean inPlay;
        boolean ownTomb;
        try {
            inPlay = mpl.plugin.engine.isEntityInPlay(t.missile);
            ownTomb = mpl.plugin.ownDetLane != null && mpl.plugin.ownDetLane.ownSourced(t)
                    && mpl.own.isTombstoned(t.wireId);
        } catch (Throwable e) {
            // A guard-read throw falls
            // through to the declared M11 painted fallback — never a silent end.
            proxDoorFailures++;
            FallbackAnnounce.announceOnce("prox-door-guard",
                    "prox-charge door guard read threw " + e.getClass().getSimpleName()
                    + " (first id=" + t.wireId + ") -> painted det fallback");
            presentFuseDetPainted(t, tick, wireEnd);
            return;
        }
        int verdict = proxEndVerdict(cause, t.fizzled, inPlay, ownTomb);
        if (verdict == PROX_END_QUIET) {
            proxDoorQuiet++;
            return;
        }
        if (verdict == PROX_END_PUFF) {
            proxDoorCauseDestroyed++; // PD kill: SourcedMissileEndLane.presentDeathPuff presents it
            return;
        }
        if (cause == PROX_CAUSE_NONE) {
            proxDoorCauseNone++;
        } else if (cause == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_SHIELD_ABSORBED) {
            proxDoorCauseShield++;
        } else if (cause == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_FIZZLING) {
            proxDoorCauseFizzling++;
        } else if (cause == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_INTACT) {
            proxDoorCauseIntact++;
        } else {
            proxDoorCauseUnknown++; //
        }
        if (verdict == PROX_END_LOCAL_FIRST) {
            proxDoorLocalFirst++;
            return;
        }
        if (verdict == PROX_END_OWN_LOCAL_FIRST) {
            proxDoorOwnLocalFirst++;
            return;
        }
        if (scriptBombEndPainted(isScriptBombSpec(t.specId), t.mintSourceNull)) {
            // A script bomb minted with NO wire
            // source replica carries the engine's SYNTHETIC source (never re-sided for a non-mine),
            // so getSource() != null would route a wrong-sided fuse through the door — paint instead.
            presentScriptBombEndPainted(t, tick, wireEnd);
            return;
        }
        boolean sourceKnown;
        try {
            sourceKnown = t.missile.getSource() != null;
        } catch (Throwable e) {
            sourceKnown = false; // unreadable source = the fuse AI would NPE too -> painted fallback
            proxDoorFailures++;
            FallbackAnnounce.announceOnce("prox-door-source-read",
                    "prox-charge getSource() threw " + e.getClass().getSimpleName()
                    + " (first id=" + t.wireId + ") -> painted fallback");
        }
        if (doorRoute(true, inPlay, sourceKnown) == PROX_ROUTE_PAINT) {
            proxDoorFallback++;
            FallbackAnnounce.announceOnce("prox-door-source",
                    "prox-charge end with no source ship (first id=" + t.wireId + " spec=" + t.specId
                    + ") -> painted det fallback (the engine fuse + DamagingExplosion deref the source)");
            presentFuseDetPainted(t, tick, wireEnd);
            return;
        }
        presentProxDoor(t, tick, wireEnd);
    }

    /** The script-bomb paint route, pure (selftest row): a script bomb
     *  whose adopt minted with NO wire source replica paints (M11); everything else continues to
     *  {@link #doorRoute}. */
    static boolean scriptBombEndPainted(boolean scriptBomb, boolean mintSourceNull) {
        return scriptBomb && mintSourceNull;
    }

    /** The prox-charge ENGINE DOOR body (all
     *  starfarer.api): move the puppet to the server's det pose, stop it, install the engine's
     *  OWN {@code ProximityFuseAI} ({@code createProximityFuseAI} — it replaces the NOOP/thrust
     *  puppet AI), then the relay's park idiom verbatim ({@code MissilePoseLane} fizzle
     *  relay: collapse the 1e9 puppet cap, {@code flameOut()}). Next frame {@code Missile.advance}
     *  starts the fizzle; the frame after, the fuse's fade trigger (or its prox trigger — the same
     *  private {@code explode()}) removes the body and adds the REAL DamagingExplosion + sound +
     *  FX. The twin is marked {@code engineDetPending} so the caller's same-tick
     *  {@code MissilePoseLane.retire} leaves the body to the engine (a removeEntity here would
     *  delete it before the fuse ever advanced). NO spawnExplosion / playSound / ownDetLane.onDet
     *  here: the engine deals the own-ship damage locally and the own-ship pin folds it — the mine
     *  door's accepted idiom; onDet would double-apply. A throw BEFORE the pending mark paints the
     *  fallback (counted); after it, the engine owns the body. */
    private void presentProxDoor(MissilePoseLane.Twin t, long tick, WireEnd wireEnd) {
        Vector2f loc = proxDoorPose(wireEnd != null, wireEnd != null ? wireEnd.x : 0f,
                wireEnd != null ? wireEnd.y : 0f, t.lastWireX, t.lastWireY);
        try {
            Vector2f body = t.missile.getLocation();
            if (body != null) {
                body.set(loc);
            }
            Vector2f vel = t.missile.getVelocity();
            if (vel != null) {
                vel.set(0f, 0f);
            }
            com.fs.starfarer.api.combat.MissileAIPlugin fuse =
                    mpl.plugin.engine.createProximityFuseAI(t.missile);
            t.missile.setMissileAI(fuse);
            // The relay's parked derivation, verbatim (the MissilePoseLane fizzle relay).
            boolean parked = false;
            try {
                parked = t.missile.getFlightTime() >= MissilePoseLane.PUPPET_MAX_FLIGHT_TIME - 1f;
            } catch (Throwable e) {
                proxDoorFailures++; // unreadable => treated as un-parked (the relay's pre-fix path)
                FallbackAnnounce.announceOnce("prox-door-flighttime",
                        "prox-charge getFlightTime() threw " + e.getClass().getSimpleName()
                        + " (first id=" + t.wireId + ") -> treated as un-parked");
            }
            t.missile.setMaxFlightTime(parked ? 0f : t.missile.getFlightTime());
            t.missile.flameOut();
            t.engineDetPending = true;
            proxDoor++;
            if (isScriptBombSpec(t.specId)) {
                scriptBombDoor++; // The script-bomb share of the door
            }
            // Arm the
            // door-det expectation (the engine's local det is server-dealt), then hold the body
            // for the read-only witness / F5 backstop walk (advanceHeld). No latency, no FX.
            armDoorExpectation(t, loc);
            holdForWitness(t, loc);
            String src;
            try {
                src = String.valueOf(t.missile.getSource().getOwner());
            } catch (Throwable e) {
                src = "?"; // log-only read; the door already ran
            }
            mpl.mineLane.log("predict v16 prox-charge wire-end DET: id=" + t.wireId + " spec=" + t.specId
                    + " tick=" + tick + " at=" + (int) loc.x + "," + (int) loc.y
                    + " wireCause=" + (wireEnd == null ? "none" : String.valueOf(wireEnd.cause))
                    + " src=" + src + (parked ? " parked" : ""));
        } catch (Throwable e) {
            proxDoorFailures++;
            FallbackAnnounce.announceOnce("prox-door-failed",
                    "prox-charge engine door threw " + e.getClass().getSimpleName()
                    + " (first id=" + t.wireId + ")" + (t.engineDetPending
                            ? " after the fuse+flameOut (engine owns the det)"
                            : " before the fuse+flameOut -> painted det fallback"));
            if (!t.engineDetPending) {
                presentFuseDetPainted(t, tick, wireEnd); // per-family paint
            }
        }
    }

    /** The door's det pose, pure: the kind-6 END floats
     *  (the server's explode pose, one feed-gap fresher) when present AND finite inside the
     *  {@code SourcedMissileEndLane.WIRE_END_POSE_CEIL_SU} ceiling (the lane's finiteness idiom —
     *  {@code !(|v| <= ceil)} also rejects NaN), else the last wire pose (the det-by-default
     *  law for the G=6 absence end). */
    static Vector2f proxDoorPose(boolean hasEnd, float endX, float endY, float lastX, float lastY) {
        if (hasEnd && Math.abs(endX) <= SourcedMissileEndLane.WIRE_END_POSE_CEIL_SU
                && Math.abs(endY) <= SourcedMissileEndLane.WIRE_END_POSE_CEIL_SU) {
            return new Vector2f(endX, endY);
        }
        return new Vector2f(lastX, lastY);
    }

    // ---- door expectation + held witness ----

    /** The held walk bounds. The
     *  witness window = the frame the body left play + 3 more render frames (a DamagingExplosion
     *  lives its explosionSpec duration 0.12 s, ~7 frames at 60 fps, so it is still listed). The
     *  backstop runs on the SIM TICK clock ({@code plugin.lastSnapshotSimTick}, the clock the
     *  door already arms on): 120 t = {@code HELD_MAX_SIM_SEC} 2.0 s at the server's 60 Hz loop. */
    static final int HELD_SIGHT_FRAMES = 4;
    static final double HELD_MAX_SIM_SEC = 2.0;
    static final long HELD_MAX_TICKS = 120L;
    static final int HELD_CAP = 32;
    /** Sighting radius (su) between the door's det pose and the engine's DamagingExplosion. */
    static final float HELD_SIGHT_SU = 64f;

    /** {@link #heldVerdict} outcomes (pure; smoke ProxChargeDoorLawSmoke P8). */
    static final int HELD_KEEP = 0;
    static final int HELD_WITNESSED = 1;
    static final int HELD_SILENT = 2;
    static final int HELD_EXPIRED = 3;

    /** One held door body. {@code goneFrames} = -1 while the body is in play. */
    private static final class HeldDet {
        final long wireId;
        final com.fs.starfarer.api.combat.MissileAPI missile;
        final Object source;
        final float detX;
        final float detY;
        final long armTick;
        int goneFrames = -1;

        HeldDet(long wireId, com.fs.starfarer.api.combat.MissileAPI missile, Object source,
                float detX, float detY, long armTick) {
            this.wireId = wireId;
            this.missile = missile;
            this.source = source;
            this.detX = detX;
            this.detY = detY;
            this.armTick = armTick;
        }
    }

    private final java.util.ArrayList<HeldDet> held = new java.util.ArrayList<HeldDet>();

    /**
     * The held entry's step,
     * pure. In play: KEEP until the body is older than {@link #HELD_MAX_TICKS} on the sim clock
     * (EXPIRED = the backstop; a tick of {@code Long.MIN_VALUE} = no clock, never expires). Left
     * play: WITNESSED when the engine's det was sighted, SILENT once {@link #HELD_SIGHT_FRAMES}
     * gone frames saw none, else KEEP. {@code goneFrames} is 0 on the first gone frame.
     */
    static int heldVerdict(boolean inPlay, int goneFrames, boolean detSighted, long armTick, long nowTick) {
        if (inPlay) {
            if (armTick != Long.MIN_VALUE && nowTick != Long.MIN_VALUE
                    && nowTick - armTick > HELD_MAX_TICKS) {
                return HELD_EXPIRED;
            }
            return HELD_KEEP;
        }
        if (detSighted) {
            return HELD_WITNESSED;
        }
        return goneFrames >= HELD_SIGHT_FRAMES - 1 ? HELD_SILENT : HELD_KEEP;
    }

    /** The sighting test, pure: the
     *  engine's explosion lies within {@link #HELD_SIGHT_SU} of the door's det pose. */
    static boolean detSightedAt(float px, float py, float detX, float detY) {
        float dx = px - detX;
        float dy = py - detY;
        return dx * dx + dy * dy <= HELD_SIGHT_SU * HELD_SIGHT_SU;
    }

    /**
     * Arm
     * {@code plugin.doorDet} for the engine det this door just set in motion, so a REMOTE victim's
     * HullLedger records the local det server-dealt (no replay entry; the own ship's pin folds its
     * own). Radius = the prox charge's DAMAGING radius, which
     * {@code phase_charge(_bomber).proj} carries ONLY inside the PROXIMITY_FUSE behaviorSpec
     * ({@code behaviorSpec.explosionSpec.radius} 150; the top-level {@code explosionRadius} is
     * "purely visual"): the public starfarer.api chain {@code MissileAPI.getSpec() -> MissileSpecAPI},
     * {@code MissileSpecAPI.getBehaviorSpec() -> ShotBehaviorSpecAPI.getParams() -> JSONObject},
     * then {@code MissileSpecAPI.getExplosionSpec() -> DamagingExplosionSpec.getRadius()} as the
     * second read. Unreadable ⇒ {@link DoorDetExpectation#FALLBACK_RADIUS}, counted.
     */
    private void armDoorExpectation(MissilePoseLane.Twin t, Vector2f loc) {
        try {
            float radius = doorExplosionRadius(t.missile);
            if (!(radius > 0f)) {
                radius = DoorDetExpectation.FALLBACK_RADIUS;
                proxDoorRadiusFallback++;
                FallbackAnnounce.announceOnce("prox-door-radius",
                        "prox-charge spec explosion radius unreadable (first id=" + t.wireId
                        + " spec=" + t.specId + ") -> door expectation uses FALLBACK_RADIUS");
            }
            mpl.plugin.doorDet.arm(t.missile.getSource(), loc.x, loc.y, radius,
                    mpl.plugin.lastSnapshotSimTick);
        } catch (Throwable e) {
            proxDoorFailures++;
            FallbackAnnounce.announceOnce("prox-door-expect",
                    "prox-charge door expectation arm threw " + e.getClass().getSimpleName()
                    + " (first id=" + t.wireId + ") -> the engine det may class as local damage");
        }
    }

    /** The spec's damaging radius, fail-soft
     *  (0 = unreadable); see {@link #armDoorExpectation} for the API chain it reads. */
    private static float doorExplosionRadius(com.fs.starfarer.api.combat.MissileAPI m) {
        try {
            com.fs.starfarer.api.loading.MissileSpecAPI spec = m == null ? null : m.getSpec();
            if (spec == null) {
                return 0f;
            }
            com.fs.starfarer.api.loading.ShotBehaviorSpecAPI bs = spec.getBehaviorSpec();
            org.json.JSONObject params = bs == null ? null : bs.getParams();
            org.json.JSONObject es = params == null ? null : params.optJSONObject("explosionSpec");
            if (es != null) {
                float r = (float) es.optDouble("radius", 0d);
                if (r > 0f) {
                    return r;
                }
            }
            com.fs.starfarer.api.loading.DamagingExplosionSpec de = spec.getExplosionSpec();
            return de == null ? 0f : de.getRadius();
        } catch (Throwable e) {
            return 0f; // counted by the caller's radius_fallback meter + announce
        }
    }

    /** Enroll the door body for the witness
     *  walk; the list is capped at {@link #HELD_CAP} (overflow evicts the OLDEST, counted). */
    private void holdForWitness(MissilePoseLane.Twin t, Vector2f loc) {
        try {
            if (held.size() >= HELD_CAP) {
                HeldDet old = held.remove(0);
                proxDoorHeldEvicted++;
                mpl.mineLane.log("predict v16 prox-door HELD-EVICT: id=" + old.wireId
                        + " (held cap=" + HELD_CAP + ")");
            }
            held.add(new HeldDet(t.wireId, t.missile, t.missile.getSource(), loc.x, loc.y,
                    mpl.plugin.lastSnapshotSimTick));
        } catch (Throwable e) {
            proxDoorHeldFailures++;
            FallbackAnnounce.announceOnce("prox-door-hold",
                    "prox-charge held enroll threw " + e.getClass().getSimpleName()
                    + " (first id=" + t.wireId + ") -> this door goes unwitnessed");
        }
    }

    /**
     * The per-render-frame
     * held walk (called once per frame from {@code MissilePoseLane.maintain}, before its fresh-frame
     * gate). Read-only sighting: a body that left play is WITNESSED when a
     * {@code DamagingExplosion} of the same source lies within {@link #HELD_SIGHT_SU} of the det
     * pose (a simple-name STRING compare — no engine-internal class reference, the B-deny law),
     * else SILENT after {@link #HELD_SIGHT_FRAMES} frames (the reported "just disappeared" class,
     * made visible). A body still in play past {@link #HELD_MAX_TICKS} is removed (the F5 backstop:
     * a fuse that never fired; UNVERIFIED). No latency, no painted FX.
     */
    void advanceHeld(long tick) {
        if (held.isEmpty()) {
            return;
        }
        try {
            com.fs.starfarer.api.combat.CombatEngineAPI engine = mpl.plugin.engine;
            if (engine == null) {
                return;
            }
            java.util.List<com.fs.starfarer.api.combat.DamagingProjectileAPI> projs = null;
            for (java.util.Iterator<HeldDet> it = held.iterator(); it.hasNext();) {
                HeldDet h = it.next();
                boolean inPlay = h.goneFrames < 0 && engine.isEntityInPlay(h.missile);
                boolean sighted = false;
                if (!inPlay) {
                    h.goneFrames++;
                    if (projs == null) {
                        projs = engine.getProjectiles();
                    }
                    sighted = sightDet(projs, h);
                }
                int v = heldVerdict(inPlay, h.goneFrames, sighted, h.armTick, tick);
                if (v == HELD_WITNESSED) {
                    proxDoorDetWitnessed++;
                    it.remove();
                } else if (v == HELD_SILENT) {
                    proxDoorSilent++;
                    it.remove();
                    mpl.mineLane.log("predict v16 prox-door SILENT: id=" + h.wireId
                            + " det=" + (int) h.detX + "," + (int) h.detY + " armTick=" + h.armTick
                            + " tick=" + tick + " (body left play, no engine det sighted in "
                            + HELD_SIGHT_FRAMES + " frames)");
                } else if (v == HELD_EXPIRED) {
                    it.remove();
                    engine.removeEntity(h.missile);
                    proxDoorHeldExpired++;
                    mpl.mineLane.log("predict v16 prox-door HELD-EXPIRED: id=" + h.wireId
                            + " armTick=" + h.armTick + " tick=" + tick
                            + " (still in play past " + HELD_MAX_TICKS + "t -> removed; fuse never fired)");
                }
            }
        } catch (Throwable e) {
            proxDoorHeldFailures++;
            FallbackAnnounce.announceOnce("prox-door-held",
                    "prox-charge held walk threw " + e.getClass().getSimpleName()
                    + " -> held door bodies unwitnessed this frame");
        }
    }

    /** Scan for the engine's det of a held
     *  body: same source, class simple-name {@code DamagingExplosion}, inside the sight radius. */
    private static boolean sightDet(
            java.util.List<com.fs.starfarer.api.combat.DamagingProjectileAPI> projs, HeldDet h) {
        if (projs == null) {
            return false;
        }
        for (com.fs.starfarer.api.combat.DamagingProjectileAPI p : projs) {
            if (p == null || p.getSource() != h.source
                    || !"DamagingExplosion".equals(p.getClass().getSimpleName())) {
                continue;
            }
            Vector2f at = p.getLocation();
            if (at != null && detSightedAt(at.x, at.y, h.detX, h.detY)) {
                return true;
            }
        }
        return false;
    }

    /** The prox-charge end verdict, pure (smoke P1).
     *  Order: a twin a pre-door relay already flamed out stays QUIET (guard); wire DESTROYED (PD
     *  kill, vanilla's plain missile death — no det) = PUFF (the death-puff lane); a body already
     *  out of play = LOCAL_FIRST; an own-sourced twin whose own tombstone is set = OWN_LOCAL_FIRST;
     *  every other cause (INTACT / SHIELD_ABSORBED / FIZZLING = the fade-det / no record) = DET. */
    static int proxEndVerdict(int cause, boolean fizzled, boolean inPlay, boolean ownTomb) {
        if (fizzled) {
            return PROX_END_QUIET;
        }
        if (cause == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_DESTROYED) {
            return PROX_END_PUFF;
        }
        if (!inPlay) {
            return PROX_END_LOCAL_FIRST;
        }
        if (ownTomb) {
            return PROX_END_OWN_LOCAL_FIRST;
        }
        return PROX_END_DET;
    }

    /** The det route, pure (smoke P4): the engine DOOR
     *  needs the door ON, a body in play, and a known source (the fuse AI derefs
     *  {@code getSource().getOriginalOwner()} every frame; DamagingExplosion NPEs on a null
     *  source — lane B2 Q2); anything else = the PAINTED path. */
    static int doorRoute(boolean doorOn, boolean inPlay, boolean sourceKnown) {
        return doorOn && inPlay && sourceKnown ? PROX_ROUTE_DOOR : PROX_ROUTE_PAINT;
    }

    /** The wire-FIZZLING relay skip predicate, pure (smoke
     *  P2): a MINE (its fuse runs the expiry arc) and, with the door ON, a
     *  prox charge (its FIZZLING is the fade-DET — the END door presents it) never take the
     *  flameOut relay. */
    static boolean fizzleRelaySkips(boolean mine, String specId) {
        // The fuse-det family — a script bomb's FIZZLING is its fade-det too
        return mine || (PROX_ENGINE_DOOR && isFuseDetSpec(specId));
    }

    /** The painted phase-charge end (the pre-door body, unchanged): now the
     *  door-OFF path and the null-source fallback. Extracted
     *  verbatim from {@code presentBombEndFx}; the formerly silent catch now counts. */
    private void presentBombEndPainted(MissilePoseLane.Twin t, long tick) {
        try {
            Vector2f loc = new Vector2f(t.lastWireX, t.lastWireY);
            Vector2f still = new Vector2f();
            // The fuse-envelope snap (knob bombImpactFx) — if the track ended where the
            // engine's own prox fuse could have fired (range 40 + the target's facing edge,
            // nearest wins), the det belongs ON that body's surface, not at the wire pose the
            // puppet already overflew. Own ship included: this IS the flash-bomb-
            // on-own-shield det cycle, presentation-only (damage stays wire-authoritative).
            ShipAPI snapped = null;
            if (BOMB_IMPACT_FX) {
                int bombOwner = t.missile.getOwner();
                float bestD2 = Float.MAX_VALUE;
                for (ShipAPI ship : mpl.plugin.engine.getShips()) {
                    if (!detEligible(ship, bombOwner)) {
                        continue;
                    }
                    float reach = BOMB_FUSE_NEAR_SU + edgeToward(ship, loc);
                    float dx = ship.getLocation().x - loc.x;
                    float dy = ship.getLocation().y - loc.y;
                    float d2 = dx * dx + dy * dy;
                    if (d2 <= reach * reach && d2 < bestD2) {
                        bestD2 = d2;
                        snapped = ship;
                    }
                }
                if (snapped != null) {
                    loc = surfaceToward(snapped, loc);
                    mpl.plugin.engine.addHitParticle(loc, still, 70f, 1f, 0.5f, BOMB_END_FX_COLOR);
                    bombImpactSnap++;
                    if (snapped == mpl.plugin.engine.getPlayerShip()) {
                        bombOwnImpactFx++;
                    }
                }
            }
            mpl.plugin.engine.spawnExplosion(loc, still, BOMB_END_FX_COLOR, BOMB_END_FX_SIZE,
                    BOMB_END_FX_SECONDS);
            Global.getSoundPlayer().playSound("prox_charge_explosion", 1f, 1f, loc, still);
            // Own det-damage relay (LAW in the lane); an own-SOURCED det is engine-exempt
            mpl.plugin.ownDetLane.onDet(loc, t.specId, mpl.plugin.ownDetLane.ownSourced(t));
            bombEndFx++;
            mpl.mineLane.log("predict v16 bomb end-FX: id=" + t.wireId + " spec=" + t.specId
                    + " tick=" + tick + " at=" + (int) loc.x + "," + (int) loc.y
                    + (snapped == null ? "" : " snap=owner" + snapped.getOwner()));
        } catch (Throwable e) {
            // presentation lane; never break the watch (counted, announced once)
            paintFailures++;
            FallbackAnnounce.announceOnce("bomb-paint-failed",
                    "phase-charge painted end threw " + e.getClass().getSimpleName()
                    + " (first id=" + t.wireId + ")");
        }
    }

    /** The phase-charge family gate — deliberately TIGHT: the plain {@code bomb} /
     *  {@code clusterbomb_proj} miss is a silent fizzle in vanilla and must stay excluded.
     *  Package-static — {@link SourcedMissileEndLane} excludes the family
     *  from its generic impact FX (this lane's bomb FX own it). */
    static boolean isProxChargeSpec(String specId) {
        // The ship-mounted phasecl's `phase_charge.proj` is
        // byte-identical to `phase_charge_bomber.proj` bar its id (diffed at the bytes) — the twin row.
        return "phase_charge_bomber".equals(specId) || "phase_charge".equals(specId);
    }

    /** The two served script-bomb spec ids (vanilla
     *  {@code OrionDeviceStats.spawnBomb}'s bodies: {@code nb_bomblauncher} / {@code od_bomblauncher}). */
    static final String NOVA_BOMB_SPEC = "nova_burst_bomb";
    static final String ORION_BOMB_SPEC = "orion_device_bomb";

    /** The Nova/Orion script-bomb family gate, deliberately
     *  TIGHT like {@link #isProxChargeSpec}: exactly the two {@code OrionDeviceStats} bomb
     *  specs. Both are PROXIMITY_FUSE bombs with {@code flameoutTime == fadeTime} (a vanilla end
     *  ALWAYS detonates, the M10 prox-charge law), so they ride the prox-charge engine door. A
     *  spec-general predicate is a later row; this gate must NOT be folded into
     *  {@link #isProxChargeSpec} (it fans to OwnDetLane / BallisticEndLinkLane / the sourced lane's
     *  impact + puff-count sites). */
    static boolean isScriptBombSpec(String specId) {
        return NOVA_BOMB_SPEC.equals(specId) || ORION_BOMB_SPEC.equals(specId);
    }

    /** The fuse-DET family (prox charge OR script bomb): the
     *  wire FIZZLING is a fade-det, the END runs the engine door. Used at EXACTLY three sites —
     *  {@link #fizzleRelaySkips}, the {@link #presentBombEndFx} guard, and
     *  {@code SourcedMissileEndLane.deathPuffEligible}; every other site keeps
     *  {@link #isProxChargeSpec}. */
    static boolean isFuseDetSpec(String specId) {
        return isProxChargeSpec(specId) || isScriptBombSpec(specId);
    }

    /** The M11 painted script-bomb end's per-spec row,
     *  read first-hand from the behaviorSpec explosionSpec of {@code nova_burst_bomb.proj} /
     *  {@code orion_device_bomb.proj}: radius, explosionColor, sound. {@code null} for any
     *  other spec. Pure (selftest row). */
    static Object[] scriptBombPaintRow(String specId) {
        if (NOVA_BOMB_SPEC.equals(specId)) {
            return new Object[] {Float.valueOf(200f), new java.awt.Color(100, 165, 255, 155),
                    "system_nova_burst_explosion"};
        }
        if (ORION_BOMB_SPEC.equals(specId)) {
            return new Object[] {Float.valueOf(100f), new java.awt.Color(155, 77, 17, 255),
                    "system_orion_device_explosion"};
        }
        return null;
    }

    /** The painted-end dispatcher: a script bomb NEVER takes
     *  the phase-charge constants; everything else keeps {@link #presentBombEndPainted}. */
    private void presentFuseDetPainted(MissilePoseLane.Twin t, long tick, WireEnd wireEnd) {
        if (isScriptBombSpec(t.specId)) {
            presentScriptBombEndPainted(t, tick, wireEnd);
        } else {
            presentBombEndPainted(t, tick);
        }
    }

    /** The script bomb's M11 painted end: the
     *  {@link #presentBombEndPainted} shape with the per-spec behaviorSpec row
     *  ({@link #scriptBombPaintRow}), at the door's det pose ({@link #proxDoorPose}: the kind-6 END
     *  floats, else the last wire pose). Damage-less {@code spawnExplosion} + the spec sound; NO
     *  {@code ownDetLane.onDet} (no DetSpec exists for these specs — an unknown-spec no-op — so
     *  own-hull damage on a painted end stays a reconcile fold); NO fuse-envelope snap.
     *  Counted ({@code scriptbomb_paint}), announced once. */
    private void presentScriptBombEndPainted(MissilePoseLane.Twin t, long tick, WireEnd wireEnd) {
        try {
            Object[] row = scriptBombPaintRow(t.specId);
            if (row == null) {
                return; // unreachable: the dispatcher gates on isScriptBombSpec
            }
            Vector2f loc = proxDoorPose(wireEnd != null, wireEnd != null ? wireEnd.x : 0f,
                    wireEnd != null ? wireEnd.y : 0f, t.lastWireX, t.lastWireY);
            Vector2f still = new Vector2f();
            mpl.plugin.engine.spawnExplosion(loc, still, (java.awt.Color) row[1],
                    ((Float) row[0]).floatValue(), BOMB_END_FX_SECONDS);
            Global.getSoundPlayer().playSound((String) row[2], 1f, 1f, loc, still);
            scriptBombPaint++;
            FallbackAnnounce.announceOnce("scriptbomb-paint",
                    "script-bomb end with no mint source (first id=" + t.wireId + " spec=" + t.specId
                    + ") -> painted det (the engine fuse + DamagingExplosion would run on a wrong-sided synthetic source)");
            mpl.mineLane.log("predict v16 scriptbomb end-PAINT: id=" + t.wireId + " spec=" + t.specId
                    + " tick=" + tick + " at=" + (int) loc.x + "," + (int) loc.y
                    + " wireCause=" + (wireEnd == null ? "none" : String.valueOf(wireEnd.cause)));
        } catch (Throwable e) {
            paintFailures++;
            FallbackAnnounce.announceOnce("scriptbomb-paint-failed",
                    "script-bomb painted end threw " + e.getClass().getSimpleName()
                    + " (first id=" + t.wireId + ")");
        }
    }

    /** Relay to the carved-out family gate ({@link CanisterEndFxLane}); kept
     *  so the MPL call sites stay byte-identical across the carve. */
    static boolean isCanisterSpec(String specId) {
        return CanisterEndFxLane.isCanisterSpec(specId);
    }

    /** end-pose→victim attribution slack (su) for the conversion det: covers
     *  wire pose quantization + ≤1-2 sim ticks of delist-pose trail at 400 su/s. */
    static final float CONV_HIT_SLACK_SU = 30f;

    private int convDetRelay;   // predict_missile_conv_det_relay (impact end attributed to OWN)
    private int convEndQuiet;   // predict_missile_conv_end_quiet (wire DESTROYED/FIZZLING —
                                //   vanilla deals 0 on both: PD kill / ranged-out fizzle)
    private int convEndNotOwn;  // predict_missile_conv_end_notown (impact end, nearest body
                                //   in envelope absent or not the own ship)
    private int convFoeApply;     // predict_missile_conv_foe_apply (the NOT-OWN impact end
                                  //   with a victim replica in the envelope applied through the
                                  //   engine, wire-acked — replaces the earlier painted foe_impact)
    private int convFoeUntreated; // predict_missile_conv_foe_untreated (a victim body the plugin
                                  //   does not fully treat — nothing applied, row says so)

    /** The terminator-CONVERSION end det-DAMAGE relay (the third det site; the
     *  reported defect: server det damage on the own ship presented as nothing). Vanilla law
     *  (the weapon table's terminator-conversion row + terminator_missile_proj.proj, read first-hand): the
     *  converted missile is DIRECT-IMPACT — 1200 ENERGY on collision, no prox fuse,
     *  {@code fizzleOnReachingWeaponRange} ⇒ a miss 0-damage-explodes (its explosionSpec
     *  r100/core50 carries NO damage keys — cosmetic AoE only). So unlike the mine EOL law
     *  (det by default), damage presents ONLY for an IMPACT end: quiet on wire DESTROYED (PD
     *  kill) AND wire FIZZLING (ranged-out miss); an INTACT/no-record delist mid-flight =
     *  removed-by-collision. Victim attribution = the sweep shape (nearest det-eligible
     *  body whose facing edge + slack contains the end pose, nearest wins); the OWN-ship relay
     *  fires only when that body is the OWN ship (a hit on an adjacent ally must never land on
     *  us); a REPLICA victim takes the same 1200 ENERGY through
     *  {@code PredictReconcileCombatPlugin.applyWireAckedHit} — the engine's own damage path,
     *  wire-acked so the reconcile write returns its hull to wire truth. NO new FX: the
     *  paired drone's death explosion (DroneStrikeFxLane pre-sets) is the det visual and
     *  {@code OwnDetLane}'s engine {@code applyDamage} carries the impact presentation
     *  (sound/number/armor flash) — a second explosion would be a double-image
     *  overlap. EMP/weapon-disables stay OWNED by
     *  the weapon-state wire lane (MEASURED: disables presented while damage didn't).
     *  Knobs: damage = {@code ownDetDamage} (off/meter/apply); this site is the
     *  {@code droneStrikeFx}=on arm. (=off routes conversions to the MINE det site, whose own
     *  {@code t.fizzled} guard starves them the same way this one did — the off-arm stays
     *  conversion-quiet, a disclosed limit; diagnostic lever only, never the live default.)
     *  The round-4 guard law: {@code t.fizzled} is a BIRTH-state for this class — the
     *  conversion crosses wire-FIZZLING from its FIRST sighting, the twin latches
     *  on that first pose, and the {@code t.fizzled} guard therefore starved 100% of
     *  conversion ends, hit or miss (measured: paired=3, all end counters 0). Conversions are
     *  EXEMPT from the fizzle-latch guard; end triage is wire-cause + sweep only. QUIET key
     *  adjudicated [MEASURED 214322 reel, kind-6 decode]: all 3 conversion ends delisted
     *  INTACT — the 2 impacts at the exact server hulldelta ticks (8015/8035) AND the
     *  probable miss (7958, no hulldelta) — so the wire cause does NOT discriminate hit vs
     *  miss for this class (the earlier miss⇒FIZZLING belief is falsified); DESTROYED+FIZZLING
     *  stay quiet as-built and the sweep envelope is the SOLE phantom-hit guard (the
     *  residue-(b) class, (50) dmg-delta auditor watches it). */
    private void presentConversionEndDet(MissilePoseLane.Twin t, long tick, WireEnd wireEnd) {
        if (!MissilePoseLane.MISSILE_POSE_APPLY
                || !DroneStrikeFxLane.isConversionSpec(t.specId)) {
            return;
        }
        try {
            if (wireEnd != null
                    && (wireEnd.cause
                            == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_DESTROYED
                        || wireEnd.cause
                            == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_FIZZLING)) {
                convEndQuiet++; // vanilla deals 0 on both end classes — silence stays honest
                mpl.convLog("predict v16 conv end QUIET: id=" + t.wireId + " tick=" + tick
                        + " wireCause=" + wireEnd.cause + MissilePoseLane.convForensics(t));
                return;
            }
            Vector2f loc = wireEnd != null ? new Vector2f(wireEnd.x, wireEnd.y)
                    : new Vector2f(t.lastWireX, t.lastWireY);
            int owner = t.missile != null ? t.missile.getOwner() : 1;
            ShipAPI victim = null;
            float bestD2 = Float.MAX_VALUE;
            for (ShipAPI ship : mpl.plugin.engine.getShips()) {
                // Hulks EXCLUDED from the conversion sweep (mine sweep unchanged) —
                // the delivery shell's corpse sits at dist≈0 at every impact end and would
                // steal nearest-wins from the own ship (NOT-OWN forever, by construction).
                if (!detEligible(ship, owner) || ship.isHulk()) {
                    continue;
                }
                float reach = CONV_HIT_SLACK_SU + edgeToward(ship, loc);
                float dx = ship.getLocation().x - loc.x;
                float dy = ship.getLocation().y - loc.y;
                float d2 = dx * dx + dy * dy;
                if (d2 <= reach * reach && d2 < bestD2) {
                    bestD2 = d2;
                    victim = ship;
                }
            }
            if (victim == null || victim != mpl.plugin.engine.getPlayerShip()) {
                convEndNotOwn++;
                // Reported ("no apparent damage clientside" when the terminator hits a
                // foe — the server dealt 600-900 hull at the same tick, 14/17 NOT-OWN ends had a foe in the
                // envelope, nothing painted): the conversion body is intangible (C1) and the foe twin is a
                // pose puppet, so the server's hit has NO engine-native impact here. The first fix PAINTED
                // particles at the surface = the fake (a later flight drew the identical "no damage"
                // report). The fix: the engine's OWN damage path on the
                // victim replica — applyDamage at the facing surface, wire-acked (the ledger pushes no
                // replay entry; the reconcile write returns hull to wire truth next frame), the
                // carrier as source so the engine's own floaty rule shows the player's numbers.
                float applied = -1f;
                boolean untreated = false;
                if (victim != null && wireEnd != null // v15: INTACT-class (shield-absorbed still dets)
                        && cc.starsector.client.net.ClientGameplayEdgeEvent.endCauseIntactClass(wireEnd.cause)) {
                    Vector2f at = impactPoint(victim, loc);
                    ShipAPI source = mpl.droneStrike.takeCarrier(t.wireId);
                    applied = mpl.plugin.applyWireAckedHit(victim, at,
                            OwnDetLane.TERMINATOR_HIT_DAMAGE, OwnDetLane.TERMINATOR_HIT_TYPE,
                            OwnDetLane.TERMINATOR_HIT_EMP, source, wireEnd.tick);
                    if (applied >= 0f) {
                        convFoeApply++;
                    } else {
                        untreated = true;
                        convFoeUntreated++;
                    }
                }
                mpl.convLog("predict v16 conv end NOT-OWN: id=" + t.wireId + " tick=" + tick
                        + " at=" + (int) loc.x + "," + (int) loc.y
                        + " wireCause=" + (wireEnd == null ? "none" : String.valueOf(wireEnd.cause))
                        + (victim == null ? " victim=none" : " victim=owner" + victim.getOwner())
                        + " apply=" + (applied >= 0f ? String.valueOf((int) applied)
                                : (untreated ? "untreated" : "none"))
                        + MissilePoseLane.convForensics(t));
                return;
            }
            // Own det-damage relay (LAW in the lane); an own-SOURCED det is engine-exempt
            mpl.plugin.ownDetLane.onDet(loc, t.specId, mpl.plugin.ownDetLane.ownSourced(t));
            convDetRelay++;
            mpl.convLog("predict v16 conv det-relay: id=" + t.wireId + " tick=" + tick
                    + " at=" + (int) loc.x + "," + (int) loc.y
                    + " wireCause=" + (wireEnd == null ? "none" : String.valueOf(wireEnd.cause))
                    + MissilePoseLane.convForensics(t));
        } catch (Throwable e) {
            // presentation lane; never break the watch — but never silent either
            // (a pre-counter throw was an invisible fourth exit).
            try {
                mpl.convLog("predict v16 conv end THREW: id=" + t.wireId + " tick=" + tick
                        + " (" + e.getClass().getSimpleName() + ")");
            } catch (Throwable ignore) {
                // the row is a nicety; the watch stands
            }
        }
    }

    /** A body a munition END could have detted ON: an enemy live hull (the fuse shape —
     *  fuses never trigger friendly) or ANY wreck (contact-det is side-blind on hulks; owner-100
     *  is the engine's hulk flip, so hulk-ness is tested before the owner gates).
     *  Package-static since the carve — {@link CanisterEndFxLane} borrows it back. */
    static boolean detEligible(ShipAPI ship, int munitionOwner) {
        if (ship == null || ship.getLocation() == null) {
            return false;
        }
        if (ship.isHulk()) {
            return true;
        }
        return ship.isAlive() && ship.getOwner() != munitionOwner && ship.getOwner() != 100;
    }

    /** The radius-faithful det-point impact sweep (knob {@code mineDetImpactFx}): every
     *  body inside the TRUE AoE (explosionSpec r250 + its facing edge; MISSILE_FF = both sides
     *  + wrecks, the deliberately kept vanilla friendly damage) gets a hit-flash at its facing
     *  surface. A body outside the radius never flashes — the FX cannot claim damage the
     *  server never dealt (the common far end stays bare). */
    private void presentDetImpacts(Vector2f det) {
        if (!MINE_DET_IMPACT_FX) {
            return;
        }
        Vector2f still = new Vector2f();
        for (ShipAPI ship : mpl.plugin.engine.getShips()) {
            if (ship == null || ship.getLocation() == null
                    || (!ship.isAlive() && !ship.isHulk())) {
                continue;
            }
            float reach = MINE_DET_FX_SIZE + edgeToward(ship, det);
            float dx = ship.getLocation().x - det.x;
            float dy = ship.getLocation().y - det.y;
            if (dx * dx + dy * dy > reach * reach) {
                continue;
            }
            mpl.plugin.engine.addHitParticle(surfaceToward(ship, det), still, 55f, 1f, 0.4f,
                    MINE_DET_FX_COLOR);
            mineDetImpactFx++;
        }
    }

    /** The body's edge toward a reference point: the shield ring when the replica shield
     *  is raised covering that bearing (foeShield keeps ring state wire-true), else the
     *  collision radius. Fail-soft to the collision radius. Package-static since the
     *  carve — {@link CanisterEndFxLane} borrows it back. */
    static float edgeToward(ShipAPI ship, Vector2f ref) {
        float edge = ship.getCollisionRadius();
        try {
            com.fs.starfarer.api.combat.ShieldAPI shield = ship.getShield();
            if (shield != null && shield.isOn() && shield.isWithinArc(ref)) {
                edge = Math.max(edge, shield.getRadius());
            }
        } catch (Throwable ignore) {
            // presentation: the collision edge is the honest fallback
        }
        return edge;
    }

    /** The IMPACT point for the wire-acked replica apply = vanilla's
     *  contact order for a direct-impact munition ({@code OwnDetLane}, the own-ship det's
     *  shape): a RAISED shield's contact point when the arc covers it, else
     *  the nearest point on the victim's EXACT BOUNDS, else the collision-circle fallback.
     *  MEASURED in a flight: 3 of the 14 victim-found conversion ends (ticks 9580/11039/12693)
     *  applied hull=0 armor=0 shield=0 — {@link #surfaceToward}'s collision-CIRCLE point lay
     *  outside the armor grid (12693: 70 su ahead of a Brawler's bow, facing 179°) and the
     *  witness row carried no {@code cell=} (the grid lookup found nothing there), so the engine
     *  dealt nothing and the player saw no numbers for that hit. */
    static Vector2f impactPoint(ShipAPI victim, Vector2f loc) {
        Vector2f shieldHit = OwnDetLane.shieldContactOrNull(victim, loc, CONV_HIT_SLACK_SU);
        if (shieldHit != null) {
            return shieldHit;
        }
        float[] b = OwnDetLane.nearestPointOnBounds(victim, loc);
        if (b != null) {
            return new Vector2f(b[0], b[1]);
        }
        return surfaceToward(victim, loc);
    }

    /** Where FX meets the body: the point on the facing edge ({@link #edgeToward}) along
     *  the body→reference bearing; a reference already inside the edge stays where it is.
     *  Package-static since the carve — {@link CanisterEndFxLane} borrows it back. */
    static Vector2f surfaceToward(ShipAPI ship, Vector2f ref) {
        Vector2f center = ship.getLocation();
        float dx = ref.x - center.x;
        float dy = ref.y - center.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float edge = edgeToward(ship, ref);
        if (dist <= edge || dist <= 0.01f) {
            return new Vector2f(ref);
        }
        float k = edge / dist;
        return new Vector2f(center.x + dx * k, center.y + dy * k);
    }

    /** The MISSILE_END (kind 6, schema v5) intake: PRCP routes the event
     *  to the pose lane FIRST (the mis-fold trap law; thin relay there), render thread.
     *  Store-only — the verdict is consumed at the absence-confirmed end of the twin's track
     *  ({@link #onTrackEnd}), which lands ≥ the G=6 grace after this tick, so the store is
     *  always written before any consumer asks. */
    void onMissileEnd(cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        if (wireEnds.size() >= WIRE_END_CAP) {
            wireEndsDropped += wireEnds.size();
            wireEnds.clear();
            mpl.mineLane.log("predict v13 wire-end store OVERFLOW: cleared at cap=" + WIRE_END_CAP
                    + " (feed defect tell; dropped total=" + wireEndsDropped + ")");
            // One-shot beside the per-cap-cycle lane.log.
            FallbackAnnounce.announceOnce("x9-wire-end-overflow",
                    "wire-end store overflow at cap=" + WIRE_END_CAP + " -> verdicts dropped");
        }
        wireEnds.put(Long.valueOf(event.entityId()),
                new WireEnd(event.endCauseCode(), event.endX(), event.endY(), event.simTick()));
    }

    /** remove-take a kind-6 verdict for the canister sub-collaborator's
     *  prune-exit path (guards live lane-side; a guard-refused exit must NOT consume the
     *  verdict, so the take happens after them — same store semantics as
     *  {@link #onTrackEnd}). */
    WireEnd takeWireEnd(long wireId) {
        return wireEnds.remove(Long.valueOf(wireId));
    }

    /** The TWINLESS wire end of a mine whose local body already ended
     *  (the pose lane latched its local tick at prune): the lead/lag row the re-fly grades —
     *  a local det AHEAD of the wire end is the own-shot idiom (own fuse, zero latency); far
     *  behind = a body the wire outlived (feed/absence class). Counted either way. */
    void onTwinlessEnd(long wireId, cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        Long localTick = mpl.mineLocalDetTick(wireId);
        if (localTick == null) {
            return;
        }
        long lead = event.simTick() - localTick.longValue();
        mineLocalLeadEnds++;
        mineLocalLeadTicksSum += lead;
        mpl.mineLane.log("predict v13 mine local-det LEAD: id=" + wireId + " localTick=" + localTick
                + " wireEndTick=" + event.simTick() + " lead=" + lead + "t"
                + " wireCause=" + event.endCauseCode());
    }

    /** Round reset. end-cause verdicts are round-local like the twins they key;
     *  counters stay cumulative (the FoeHull totals discipline). */
    void resetForMatchReset() {
        wireEnds.clear();
        held.clear(); // Held door bodies are round-local
    }

    /** Spliced into the MPL meter line at the pre-split token position (the split/own
     *  meterTokens discipline — the line stays byte-identical).
     * the prox-door tokens are APPENDED after the canister block. */
    String meterTokens() {
        return " predict_missile_mine_det_fx=" + mineDetFx
                + " predict_missile_mine_explode_door=" + mineExplodeDoor
                + " predict_missile_mine_explode_local_first=" + mineExplodeLocalFirst
                + " predict_missile_mine_local_lead_ends=" + mineLocalLeadEnds
                + " predict_missile_mine_local_lead_ticks_sum=" + mineLocalLeadTicksSum
                + " predict_missile_mine_end_quiet=" + mineEndQuiet
                + " predict_missile_mine_end_wire_destroyed=" + mineEndWireDestroyed
                + " predict_missile_mine_end_wire_intact=" + mineEndWireIntact
                + " predict_missile_mine_end_wire_fizzling=" + mineEndWireFizzling
                + " predict_missile_mine_end_wire_none=" + mineEndWireNone
                + " predict_missile_wire_ends_dropped=" + wireEndsDropped
                + " predict_missile_mine_primes=" + minePrimes
                + " predict_missile_mine_windup_fx=" + mineWindupFx
                + " predict_missile_bomb_endfx=" + bombEndFx
                + " predict_missile_mine_det_impact_fx=" + mineDetImpactFx
                + " predict_missile_bomb_impact_snap=" + bombImpactSnap
                + " predict_missile_bomb_own_impact=" + bombOwnImpactFx
                + " predict_missile_conv_det_relay=" + convDetRelay
                + " predict_missile_conv_end_quiet=" + convEndQuiet
                + " predict_missile_conv_end_notown=" + convEndNotOwn
                + " predict_missile_conv_foe_apply=" + convFoeApply
                + " predict_missile_conv_foe_untreated=" + convFoeUntreated
                + canister.meterTokens()
                // The prox-charge engine door, appended.
                + " predict_missile_prox_door=" + proxDoor
                // The witness / backstop / F6 meters.
                + " predict_missile_prox_door_det_witnessed=" + proxDoorDetWitnessed
                + " predict_missile_prox_door_silent=" + proxDoorSilent
                + " predict_missile_prox_door_held_expired=" + proxDoorHeldExpired
                + " predict_missile_prox_door_cause_unknown=" + proxDoorCauseUnknown
                + " predict_missile_prox_door_held_evicted=" + proxDoorHeldEvicted
                + " predict_missile_prox_door_held_failures=" + proxDoorHeldFailures
                + " predict_missile_prox_door_radius_fallback=" + proxDoorRadiusFallback
                + " predict_missile_prox_door_cause_intact=" + proxDoorCauseIntact
                + " predict_missile_prox_door_cause_shield=" + proxDoorCauseShield
                + " predict_missile_prox_door_cause_fizzling=" + proxDoorCauseFizzling
                + " predict_missile_prox_door_cause_none=" + proxDoorCauseNone
                + " predict_missile_prox_door_cause_destroyed=" + proxDoorCauseDestroyed
                + " predict_missile_prox_door_quiet=" + proxDoorQuiet
                + " predict_missile_prox_door_local_first=" + proxDoorLocalFirst
                + " predict_missile_prox_door_own_local_first=" + proxDoorOwnLocalFirst
                + " predict_missile_prox_door_fallback=" + proxDoorFallback
                + " predict_missile_prox_door_failures=" + proxDoorFailures
                + " predict_missile_bomb_paint_failures=" + paintFailures
                // The served script-bomb end meters, appended.
                + " predict_missile_scriptbomb_door=" + scriptBombDoor
                + " predict_missile_scriptbomb_paint=" + scriptBombPaint;
    }
}
