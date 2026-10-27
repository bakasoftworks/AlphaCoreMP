package cc.starsector.server.protocol;

import cc.starsector.server.runtime.PilotIntentFrame;

/**
 * One tick-stamped gameplay edge event:
 * a weapon discharge, vent-window edge,
 * or ship-system-window edge detected post-step from engine-accepted state, stamped
 * with the exact completed {@link #simTick()} at which post-step state first shows
 * it (every edge belongs to exactly one tick).
 *
 * <p>This is the event-family counterpart of {@link EntityDespawnEvent}: emission is
 * a pure function of post-step engine state per tick, so under the probe
 * pin the whole stream is provable bit-exactly. The dedup/order key
 * is {@code (simTick, entityId, kind, phase, mountIndex)}; application is
 * idempotent set-semantics.
 *
 * <p>{@code entityId} keys the owning ship in the same id scheme as the snapshot
 * stream the detector observes alongside (the B1 in-process detector uses the
 * within-tick repository {@code shipId} exactly as the scout's
 * {@code AuthoritativeWorldSnapshot.fromViewLists} capture does; the B2 wire
 * publisher re-keys to the stable network id like the despawn publisher).
 *
 * <p>{@code mountIndex}/{@code aimFacingDeg}/{@code flightBearingDeg} are the
 * {@code WEAPON_DISCHARGE} payload: the per-ship mount ordinal, the mount's
 * facing at the detection read (a MISSILE discharge carries its fresh
 * projectile's birth facing = mount + barrel offset, {@code LaunchFacingLaw}; the mount
 * angle is its counted fallback), and — schema v2 — the server's
 * TRUE initial flight bearing: the engine's own spawn composition
 * {@code shooterVel + projectileSpeed·û(mountRay)},
 * normalized to [0,360). The mount ray alone is NOT the bearing a
 * moving shooter's bolt flies (up to ~13° apart at combat speeds — the whole
 * "aim_rebase" desync axis); carrying the composed bearing lets the client rebase its
 * predicted bolt onto the server's actual line instead of stripping the velocity term.
 * Either float may be {@link Float#NaN} when its reads are not bound (fail-soft —
 * never a faked value); window kinds carry {@link #NO_MOUNT}/{@code NaN}/{@code NaN}
 * canonically so equal events are bytewise-identical in the NDJSON log.
 *
 * <p>{@code birthSpeed} (schema v6) is the {@code |v0|} half
 * of the same truth: the projectile's birth-velocity magnitude in engine units/sec —
 * measured {@code hypot(projectile.getVelocity())} on the confession lane, or the
 * composed {@code |shooterVel + projectileSpeed·û(ray)|} on the detector poll lane —
 * so the client can compose the full server flight vector
 * {@code birthSpeed·û(flightBearingDeg)} instead of estimating the magnitude locally
 * (the marker-#3 class). {@code NaN} = honest absence (the bearing NaN law);
 * a finite value is a magnitude and must be {@code >= 0}. Non-discharge kinds carry
 * canonical {@code NaN} — the two REUSED float slots (dest/split/end) never extend to it.
 *
 * <p>{@code muzzleX}/{@code muzzleY} (schema v8
 * spawn-truth): the server-true world position the projectile was BORN at — measured
 * {@code projectile.getLocation()} at the onFire confession, or the fresh-spawn entity's
 * own location on the detector poll lane — completing spawn-truth for the victim's
 * composed ray (bearing + |v0| + fire tick already ride the frame; the muzzle was the one
 * missing axis, re-derived until v8 from a lagged shooter replica).
 * The pair is ATOMIC: both finite (one captured
 * point) or both {@code NaN} (honest absence — no live projectile at the read); a mixed
 * pair is rejected fail-closed. Non-discharge kinds carry canonical {@code NaN}/{@code NaN}
 * like {@code birthSpeed}.
 *
 * <p>{@code targetWireId} (schema v9): the {@code AUTOFIRE_TARGET}
 * payload — the stable network id of the SHIP the server's autofire plugin now tracks on
 * {@code (entityId, mountIndex)}, or {@link #TARGET_NONE} for a cleared pick. Every other
 * kind carries {@link #TARGET_ABSENT} canonically (the {@code birthSpeed}/muzzle slot law
 * extended to the long tail).
 *
 * <p>{@code PALADIN_DET} (schema v10): {@code entityId} = the
 * SOURCE ship, {@code mountIndex} = firing mount ordinal or {@link #NO_MOUNT} (script dets
 * carry no weapon, so {@code NO_MOUNT} is the live case), and the two reused float slots
 * carry the det position {@link #detX()}/{@link #detY()} (both REQUIRED finite — the
 * producer read the birth pose off the engine object; a miss skips emission, never NaN).
 *
 * <p>{@code ARC_HIT} (schema v13): {@code entityId} = the SOURCE ship,
 * {@code targetWireId} = the VICTIM ship (REQUIRED {@code > 0} — the producer skips
 * unresolvable ids, never a sentinel), {@code mountIndex} = the arc flavor code
 * ({@link #arcFlavor()}: {@link #ARC_FLAVOR_OTHER}/{@link #ARC_FLAVOR_INIMICAL}/
 * {@link #ARC_FLAVOR_VOLTAIC}; unknown codes are legal on the wire, the client treats them
 * as OTHER), the two reused float slots = the arc's node world point {@link #hitX()}/
 * {@link #hitY()} (both REQUIRED finite), and the remaining three floats = the applied
 * damage split {@link #dmgArmor()}/{@link #dmgHull()}/{@link #dmgShield()} under the
 * shield pair law (the v8 muzzle pair idiom): a hull hit carries armor+hull finite
 * {@code >= 0} with shield NaN; a shield hit carries shield finite {@code >= 0} with
 * armor+hull NaN; every other combination is corruption (fail-closed).
 * {@link #isShieldHit()} ≡ {@code !Float.isNaN(dmgShield)}. EMP is not carried.
 *
 * <p>{@code CRIT_MALFUNCTION} (schema v14):
 * {@code entityId} = the VICTIM ship, {@code targetWireId} = {@link #TARGET_ABSENT}
 * (canonical), {@code mountIndex} = the module code ({@link #critModuleCode(boolean, int)}: a
 * weapon ordinal in {@code getAllWeapons()} order 0..{@link #CRIT_WEAPON_ORDINAL_MAX}, or
 * {@link #CRIT_MODULE_ENGINE_BASE} + an engine ordinal in {@code getShipEngines()} order
 * 0..{@link #CRIT_ENGINE_ORDINAL_MAX}), {@code aimFacingDeg} = the permanent flag (EXACTLY
 * 0 or 1), {@code flightBearingDeg} = the branch code ({@link #CRIT_BRANCH_DIRECT}/
 * {@link #CRIT_BRANCH_DAMAGE}/{@link #CRIT_BRANCH_LETHAL}, exact), {@code birthSpeed} = the
 * hull amount (finite, 0..{@link #CRIT_AMOUNT_CEIL}), muzzle pair canonical NaN/NaN. Every
 * other value is corruption (exact-set checks, fail-closed).
 *
 * <p>{@code MISSILE_END} victim form (schema v15): {@code targetWireId} = the
 * SHIP the missile's LAST engine impact hit ({@code > 0}) or {@link #TARGET_ABSENT}; the
 * end-cause vocabulary gains {@link #END_CAUSE_SHIELD_ABSORBED} (3). Width unchanged.
 *
 * <p>{@code WEAPON_DISCHARGE} declared wait (schema v16):
 * {@code targetWireId} = the RAW ticks from the consumed declaration's {@code applyTick} to this edge's
 * {@link #simTick()} ({@link #declaredWaitTicks()}: {@code 0..}{@link #DECLARED_WAIT_MAX_TICKS}, a ready
 * gun reads 0) or {@link #TARGET_ABSENT} (no stamped declaration consumed). Every other value is
 * corruption (fail-closed). Stamped post-step by {@link #withDeclaredWait(int)}; the emitters mint
 * {@link #TARGET_ABSENT}. Width unchanged.
 */
public record GameplayEdgeEvent(
        long simTick,
        long entityId,
        GameplayEdgeEventKind kind,
        GameplayEdgeEventPhase phase,
        int mountIndex,
        float aimFacingDeg,
        float flightBearingDeg,
        float birthSpeed,
        float muzzleX,
        float muzzleY,
        long targetWireId) {

    /** Canonical {@link #mountIndex()} for the non-mount (window) kinds. */
    public static final int NO_MOUNT = -1;

    /** Canonical absent {@link #targetWireId()} (value-locked to the sentinel family —
     *  negatives are free, every real wire id is {@code > 0}): the only legal value on every kind
     *  whose slot law names no payload; on {@code WEAPON_DISCHARGE} (v16) = no declared wait. */
    public static final long TARGET_ABSENT = PilotIntentFrame.TARGET_ABSENT;

    /** {@code WEAPON_DISCHARGE} (v16): the highest legal declared wait in ticks
     *  (inclusive; the server clamps a longer wait here). */
    public static final int DECLARED_WAIT_MAX_TICKS = 255;

    /** {@code AUTOFIRE_TARGET} payload: the server's pick was CLEARED — the client reverts
     *  to its local autofire pick (mirror of {@code PilotIntentFrame.TARGET_NONE}). */
    public static final long TARGET_NONE = PilotIntentFrame.TARGET_NONE;

    /** {@code ARC_HIT} flavor code ({@link #arcFlavor()}): an arc the producer could not
     *  attribute to a named impact set (tachyon/ion-beam/voltaic-cannon/sabot/… — presented
     *  locally by their own client wrappers already). */
    public static final int ARC_FLAVOR_OTHER = 0;

    /** {@code ARC_HIT} flavor code: the Dweller {@code inimical_emanation_impact} arc. */
    public static final int ARC_FLAVOR_INIMICAL = 1;

    /** {@code ARC_HIT} flavor code: the Threat {@code voltaic_discharge_emp_impact} arc. */
    public static final int ARC_FLAVOR_VOLTAIC = 2;

    /** {@code ARC_HIT} flavor code: the Overseer energy lash's ENEMY branch
     *  ({@code energy_lash_enemy_impact}, {@code EnergyLashSystemScript} — one arc per
     *  system slot, EMP 1500, hull 0; a PHASED victim is overloaded 1 s in the same frame). */
    public static final int ARC_FLAVOR_LASH = 3;

    /** {@code ARC_HIT} flavor code: the lash's SAME-OWNER branch ({@code energy_lash_friendly_impact},
     * shield-piercing, zero damage) — named on the wire, client presentation owed. */
    public static final int ARC_FLAVOR_LASH_FRIENDLY = 4;

    /** {@code ARC_HIT} flavor code: the PROJECTILE ion family's 25 % on-hit arc
     *  ({@code IonCannonOnHitEffect} — {@code ioncannon_shot}/{@code ionpulser_shot}, so the
     *  ion cannon, the fighter ion cannon and the ion pulser): impact set
     *  {@code tachyon_lance_emp_impact} AND non-piercing AND arc damage {@code > 0} (the tachyon
     *  lance / ion beam / pilum twins pierce, the sabot child passes damage 0 — those stay OTHER).
     *  The client's ion wrapper shuts the replica's own local roll; this code is what it draws. */
    public static final int ARC_FLAVOR_ION = 5;

    /** {@code ARC_HIT} flavor code: the sabot MIRV child's 25 % on-hit arc
     *  ({@code SabotOnHitEffect}, the {@code onHitEffect} of {@code sabot_warhead2.proj} — the
     *  child every sabot weapon splits into): the ion recipe with {@code dam = 0.0F} — impact set
     *  {@code tachyon_lance_emp_impact} AND non-piercing AND arc damage {@code <= 0}. Its own code so
     *  the ION witness stays {@code dam > 0}-exact; the client's sabot wrapper shuts the presented
     *  child's local roll and {@code ArcHitFxLane} draws this through the ion body. */
    public static final int ARC_FLAVOR_SABOT = 6;

    // The CRIT_MALFUNCTION wire contract (same names both trees).
    /** {@code CRIT_MALFUNCTION} module code base for ENGINE modules (the engine-mask convention). */
    public static final int CRIT_MODULE_ENGINE_BASE = 1000;
    /** {@code CRIT_MALFUNCTION} highest legal weapon ordinal ({@code getAllWeapons()} index). */
    public static final int CRIT_WEAPON_ORDINAL_MAX = 255;
    /** {@code CRIT_MALFUNCTION} highest legal engine ordinal ({@code getShipEngines()} index). */
    public static final int CRIT_ENGINE_ORDINAL_MAX = 31;
    /** {@code CRIT_MALFUNCTION} branch: the engine's direct {@code setHitpoints} hull loss. */
    public static final int CRIT_BRANCH_DIRECT = 0;
    /** {@code CRIT_MALFUNCTION} branch: the engine's self-sourced {@code applyDamage(ENERGY)}. */
    public static final int CRIT_BRANCH_DAMAGE = 1;
    /** {@code CRIT_MALFUNCTION} branch: the ship died this tick (the kill lane owns the death). */
    public static final int CRIT_BRANCH_LETHAL = 2;
    /** {@code CRIT_MALFUNCTION} hull-amount ceiling (inclusive). */
    public static final float CRIT_AMOUNT_CEIL = 1_000_000f;

    public GameplayEdgeEvent {
        if (simTick < 0L) {
            throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
        }
        if (entityId <= 0L) {
            throw new IllegalArgumentException("entityId must be > 0; got " + entityId);
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (phase == null) {
            throw new IllegalArgumentException("phase must not be null");
        }
        switch (kind) {
            case WEAPON_DISCHARGE -> {
                if (phase != GameplayEdgeEventPhase.ONE_SHOT) {
                    throw new IllegalArgumentException(
                            "WEAPON_DISCHARGE phase must be ONE_SHOT; got " + phase);
                }
                if (mountIndex < 0) {
                    throw new IllegalArgumentException(
                            "WEAPON_DISCHARGE mountIndex must be >= 0; got " + mountIndex);
                }
                if (!Float.isFinite(aimFacingDeg) && !Float.isNaN(aimFacingDeg)) {
                    throw new IllegalArgumentException(
                            "aimFacingDeg must be finite or NaN; got " + aimFacingDeg);
                }
                if (!Float.isFinite(flightBearingDeg) && !Float.isNaN(flightBearingDeg)) {
                    throw new IllegalArgumentException(
                            "flightBearingDeg must be finite or NaN; got " + flightBearingDeg);
                }
                if (Float.isFinite(birthSpeed) ? birthSpeed < 0f : !Float.isNaN(birthSpeed)) {
                    throw new IllegalArgumentException(
                            "birthSpeed must be a finite magnitude >= 0 or NaN; got " + birthSpeed);
                }
                // v8 pair law: one captured point or one honest absence — a mixed pair can
                // only be corruption (both floats read from a single Vector2f), fail-closed.
                boolean muzzleFinite = Float.isFinite(muzzleX) && Float.isFinite(muzzleY);
                boolean muzzleAbsent = Float.isNaN(muzzleX) && Float.isNaN(muzzleY);
                if (!muzzleFinite && !muzzleAbsent) {
                    throw new IllegalArgumentException(
                            "muzzleX/muzzleY must be both finite or both NaN; got "
                                    + muzzleX + "/" + muzzleY);
                }
                // The v16 declared-wait slot law — TARGET_ABSENT
                // or a raw tick count 0..DECLARED_WAIT_MAX_TICKS; anything else is corruption.
                if (targetWireId != TARGET_ABSENT
                        && (targetWireId < 0L || targetWireId > DECLARED_WAIT_MAX_TICKS)) {
                    throw new IllegalArgumentException(
                            "WEAPON_DISCHARGE targetWireId (declared wait) must be TARGET_ABSENT ("
                                    + TARGET_ABSENT + ") or 0.." + DECLARED_WAIT_MAX_TICKS + "; got "
                                    + targetWireId);
                }
            }
            case VENT_WINDOW -> {
                if (phase != GameplayEdgeEventPhase.START && phase != GameplayEdgeEventPhase.END) {
                    throw new IllegalArgumentException(
                            "VENT_WINDOW phase must be START or END; got " + phase);
                }
                requireWindowPayload(mountIndex, aimFacingDeg, flightBearingDeg, birthSpeed);
                requireNaNMuzzle(kind, muzzleX, muzzleY);
                requireAbsentTarget(kind, targetWireId);
            }
            case SYSTEM_WINDOW -> {
                if (phase != GameplayEdgeEventPhase.ON && phase != GameplayEdgeEventPhase.OFF) {
                    throw new IllegalArgumentException(
                            "SYSTEM_WINDOW phase must be ON or OFF; got " + phase);
                }
                if (Float.isFinite(aimFacingDeg) || Float.isFinite(flightBearingDeg)) {
                    // Schema v12: the mote-target ON form — the two float
                    // slots carry the attractor's target world X/Y and the long slot the LOCKED
                    // ship's wire id (or TARGET_NONE = empty-space target). ON edge only; the
                    // OFF edge and every non-mote press keep the plain window payload below.
                    if (phase != GameplayEdgeEventPhase.ON) {
                        throw new IllegalArgumentException(
                                "SYSTEM_WINDOW mote-target payload rides the ON edge only; got " + phase);
                    }
                    if (!Float.isFinite(aimFacingDeg) || !Float.isFinite(flightBearingDeg)) {
                        throw new IllegalArgumentException(
                                "SYSTEM_WINDOW targetX/targetY must both be finite; got "
                                        + aimFacingDeg + "/" + flightBearingDeg);
                    }
                    if (mountIndex != NO_MOUNT) {
                        throw new IllegalArgumentException(
                                "SYSTEM_WINDOW carries mountIndex NO_MOUNT (" + NO_MOUNT + "); got " + mountIndex);
                    }
                    requireNaNBirthSpeed(kind, birthSpeed);
                    requireNaNMuzzle(kind, muzzleX, muzzleY);
                    if (targetWireId != TARGET_NONE && targetWireId < 1L) {
                        throw new IllegalArgumentException(
                                "SYSTEM_WINDOW mote-target targetWireId must be > 0 or TARGET_NONE ("
                                        + TARGET_NONE + "); got " + targetWireId);
                    }
                } else {
                    requireWindowPayload(mountIndex, aimFacingDeg, flightBearingDeg, birthSpeed);
                    requireNaNMuzzle(kind, muzzleX, muzzleY);
                    requireAbsentTarget(kind, targetWireId);
                }
            }
            case SYSTEM_JUMP -> {
                if (phase != GameplayEdgeEventPhase.ONE_SHOT) {
                    throw new IllegalArgumentException(
                            "SYSTEM_JUMP phase must be ONE_SHOT; got " + phase);
                }
                if (mountIndex != NO_MOUNT) {
                    throw new IllegalArgumentException(
                            "SYSTEM_JUMP carries mountIndex NO_MOUNT (" + NO_MOUNT + "); got " + mountIndex);
                }
                // A marker without a destination is useless — the dest floats are REQUIRED
                // finite (the detector SKIPS emission on a pose miss rather than sending NaN).
                if (!Float.isFinite(aimFacingDeg) || !Float.isFinite(flightBearingDeg)) {
                    throw new IllegalArgumentException(
                            "SYSTEM_JUMP destX/destY must both be finite; got "
                                    + aimFacingDeg + "/" + flightBearingDeg);
                }
                requireNaNBirthSpeed(kind, birthSpeed);
                requireNaNMuzzle(kind, muzzleX, muzzleY);
                requireAbsentTarget(kind, targetWireId);
            }
            case MISSILE_SPLIT -> {
                if (phase != GameplayEdgeEventPhase.ONE_SHOT) {
                    throw new IllegalArgumentException(
                            "MISSILE_SPLIT phase must be ONE_SHOT; got " + phase);
                }
                if (mountIndex != NO_MOUNT) {
                    throw new IllegalArgumentException(
                            "MISSILE_SPLIT carries mountIndex NO_MOUNT (" + NO_MOUNT + "); got " + mountIndex);
                }
                // The split position IS the payload — the detector always has the parent's
                // last authoritative pose (it diffed the record out), so NaN is never honest.
                if (!Float.isFinite(aimFacingDeg) || !Float.isFinite(flightBearingDeg)) {
                    throw new IllegalArgumentException(
                            "MISSILE_SPLIT splitX/splitY must both be finite; got "
                                    + aimFacingDeg + "/" + flightBearingDeg);
                }
                requireNaNBirthSpeed(kind, birthSpeed);
                requireNaNMuzzle(kind, muzzleX, muzzleY);
                requireAbsentTarget(kind, targetWireId);
            }
            case MISSILE_END -> {
                if (phase != GameplayEdgeEventPhase.ONE_SHOT) {
                    throw new IllegalArgumentException(
                            "MISSILE_END phase must be ONE_SHOT; got " + phase);
                }
                // The mountIndex slot carries the end-cause wire code — the whole point
                // of the kind (det-vs-shotdown made wire-true; v15
                // + SHIELD_ABSORBED, the shield-vs-hull contact made wire-true).
                if (mountIndex != END_CAUSE_DESTROYED && mountIndex != END_CAUSE_FIZZLING
                        && mountIndex != END_CAUSE_INTACT && mountIndex != END_CAUSE_SHIELD_ABSORBED) {
                    throw new IllegalArgumentException(
                            "MISSILE_END mountIndex must be an END_CAUSE_* code (0/1/2/3); got "
                                    + mountIndex);
                }
                // Same law as MISSILE_SPLIT: the detector diffed the record out, so it
                // always had the missile's last authoritative pose — NaN is never honest.
                if (!Float.isFinite(aimFacingDeg) || !Float.isFinite(flightBearingDeg)) {
                    throw new IllegalArgumentException(
                            "MISSILE_END endX/endY must both be finite; got "
                                    + aimFacingDeg + "/" + flightBearingDeg);
                }
                requireNaNBirthSpeed(kind, birthSpeed);
                requireNaNMuzzle(kind, muzzleX, muzzleY);
                // v15: the long slot names the VICTIM ship of the missile's last
                // impact (> 0) or stays TARGET_ABSENT = "ended on nothing the server saw hit";
                // never TARGET_NONE, never 0 (the ARC_HIT > 0 law, absence allowed).
                if (targetWireId != TARGET_ABSENT && targetWireId <= 0L) {
                    throw new IllegalArgumentException(
                            "MISSILE_END targetWireId must be > 0 or TARGET_ABSENT (" + TARGET_ABSENT
                                    + "); got " + targetWireId);
                }
            }
            case AUTOFIRE_TARGET -> {
                if (phase != GameplayEdgeEventPhase.ONE_SHOT) {
                    throw new IllegalArgumentException(
                            "AUTOFIRE_TARGET phase must be ONE_SHOT; got " + phase);
                }
                if (mountIndex < 0) {
                    throw new IllegalArgumentException(
                            "AUTOFIRE_TARGET mountIndex must be >= 0; got " + mountIndex);
                }
                if (!Float.isNaN(aimFacingDeg) || !Float.isNaN(flightBearingDeg)) {
                    throw new IllegalArgumentException(
                            "AUTOFIRE_TARGET carries aimFacingDeg/flightBearingDeg NaN; got "
                                    + aimFacingDeg + "/" + flightBearingDeg);
                }
                requireNaNBirthSpeed(kind, birthSpeed);
                requireNaNMuzzle(kind, muzzleX, muzzleY);
                // The pick payload: a real ship wire id (> 0) or the explicit CLEARED
                // sentinel. TARGET_ABSENT is never a valid emit — the lane samples before
                // it speaks (the emitter skips unresolvable picks instead of guessing).
                if (targetWireId != TARGET_NONE && targetWireId < 1L) {
                    throw new IllegalArgumentException(
                            "AUTOFIRE_TARGET targetWireId must be > 0 or TARGET_NONE ("
                                    + TARGET_NONE + "); got " + targetWireId);
                }
            }
            case PALADIN_DET -> {
                if (phase != GameplayEdgeEventPhase.ONE_SHOT) {
                    throw new IllegalArgumentException(
                            "PALADIN_DET phase must be ONE_SHOT; got " + phase);
                }
                if (mountIndex < NO_MOUNT) {
                    throw new IllegalArgumentException(
                            "PALADIN_DET mountIndex must be a mount ordinal >= 0 or NO_MOUNT ("
                                    + NO_MOUNT + "); got " + mountIndex);
                }
                // The det position IS the payload — the producer fingerprinted the live
                // engine object and read its birth pose, so NaN is never honest.
                if (!Float.isFinite(aimFacingDeg) || !Float.isFinite(flightBearingDeg)) {
                    throw new IllegalArgumentException(
                            "PALADIN_DET detX/detY must both be finite; got "
                                    + aimFacingDeg + "/" + flightBearingDeg);
                }
                requireNaNBirthSpeed(kind, birthSpeed);
                requireNaNMuzzle(kind, muzzleX, muzzleY);
                requireAbsentTarget(kind, targetWireId);
            }
            case ARC_HIT -> {
                if (phase != GameplayEdgeEventPhase.ONE_SHOT) {
                    throw new IllegalArgumentException(
                            "ARC_HIT phase must be ONE_SHOT; got " + phase);
                }
                // The mount slot carries the flavor code; unknown codes are legal on the wire
                // (the client folds them to OTHER), negatives never are.
                if (mountIndex < 0) {
                    throw new IllegalArgumentException(
                            "ARC_HIT arcFlavor must be >= 0; got " + mountIndex);
                }
                // The node point IS the payload — read off the live arc (getTargetLocation),
                // so NaN is never honest.
                if (!Float.isFinite(aimFacingDeg) || !Float.isFinite(flightBearingDeg)) {
                    throw new IllegalArgumentException(
                            "ARC_HIT hitX/hitY must both be finite; got "
                                    + aimFacingDeg + "/" + flightBearingDeg);
                }
                // Shield pair law (the v8 muzzle-pair idiom): EXACTLY one of
                // {armor AND hull finite >= 0, shield NaN} (hull hit) or
                // {shield finite >= 0, armor AND hull NaN} (shield hit); anything else is
                // corruption (all three came off one ApplyDamageResultAPI), fail-closed.
                boolean hullHit = Float.isFinite(birthSpeed) && birthSpeed >= 0f
                        && Float.isFinite(muzzleX) && muzzleX >= 0f
                        && Float.isNaN(muzzleY);
                boolean shieldHit = Float.isNaN(birthSpeed) && Float.isNaN(muzzleX)
                        && Float.isFinite(muzzleY) && muzzleY >= 0f;
                if (!hullHit && !shieldHit) {
                    throw new IllegalArgumentException(
                            "ARC_HIT dmgArmor/dmgHull/dmgShield must be {finite>=0, finite>=0, NaN}"
                                    + " (hull hit) or {NaN, NaN, finite>=0} (shield hit); got "
                                    + birthSpeed + "/" + muzzleX + "/" + muzzleY);
                }
                // The victim IS the payload: a real ship wire id (> 0) — never a sentinel
                // (the producer skips unresolvable ids instead of guessing).
                if (targetWireId < 1L) {
                    throw new IllegalArgumentException(
                            "ARC_HIT targetWireId must be > 0; got " + targetWireId);
                }
            }
            case CRIT_MALFUNCTION -> {
                // exact-set checks, fail-closed.
                if (phase != GameplayEdgeEventPhase.ONE_SHOT) {
                    throw new IllegalArgumentException(
                            "CRIT_MALFUNCTION phase must be ONE_SHOT; got " + phase);
                }
                boolean weaponCode = mountIndex >= 0 && mountIndex <= CRIT_WEAPON_ORDINAL_MAX;
                boolean engineCode = mountIndex >= CRIT_MODULE_ENGINE_BASE
                        && mountIndex <= CRIT_MODULE_ENGINE_BASE + CRIT_ENGINE_ORDINAL_MAX;
                if (!weaponCode && !engineCode) {
                    throw new IllegalArgumentException(
                            "CRIT_MALFUNCTION module code must be in 0.." + CRIT_WEAPON_ORDINAL_MAX
                                    + " or " + CRIT_MODULE_ENGINE_BASE + ".."
                                    + (CRIT_MODULE_ENGINE_BASE + CRIT_ENGINE_ORDINAL_MAX) + "; got " + mountIndex);
                }
                if (aimFacingDeg != 0f && aimFacingDeg != 1f) {
                    throw new IllegalArgumentException(
                            "CRIT_MALFUNCTION permanent flag must be exactly 0 or 1; got " + aimFacingDeg);
                }
                if (flightBearingDeg != CRIT_BRANCH_DIRECT && flightBearingDeg != CRIT_BRANCH_DAMAGE
                        && flightBearingDeg != CRIT_BRANCH_LETHAL) {
                    throw new IllegalArgumentException(
                            "CRIT_MALFUNCTION branch must be exactly 0/1/2; got " + flightBearingDeg);
                }
                if (!Float.isFinite(birthSpeed) || birthSpeed < 0f || birthSpeed > CRIT_AMOUNT_CEIL) {
                    throw new IllegalArgumentException(
                            "CRIT_MALFUNCTION amount must be finite in 0.." + CRIT_AMOUNT_CEIL + "; got "
                                    + birthSpeed);
                }
                requireNaNMuzzle(kind, muzzleX, muzzleY);
                requireAbsentTarget(kind, targetWireId);
            }
        }
    }

    /** A {@code WEAPON_DISCHARGE} one-shot for {@code (entityId, mountIndex)} at {@code simTick}
     *  ({@code birthSpeed} = the v6 {@code |v0|} magnitude; {@code muzzleX}/{@code muzzleY} =
     *  the v8 server-true birth position, both-finite-or-both-NaN; {@code NaN} = honest
     *  absence). */
    public static GameplayEdgeEvent weaponDischarge(
            long simTick, long entityId, int mountIndex, float aimFacingDeg, float flightBearingDeg,
            float birthSpeed, float muzzleX, float muzzleY) {
        return new GameplayEdgeEvent(
                simTick, entityId, GameplayEdgeEventKind.WEAPON_DISCHARGE,
                GameplayEdgeEventPhase.ONE_SHOT, mountIndex, aimFacingDeg, flightBearingDeg,
                birthSpeed, muzzleX, muzzleY, TARGET_ABSENT);
    }

    /** Muzzle-ABSENT {@link #weaponDischarge(long, long, int, float, float, float, float, float)}
     *  variant (v8 pair = canonical {@code NaN}/{@code NaN}) — for callers/tests exercising the
     *  pre-v8 payload; both production discharge emitters pass the captured pair explicitly. */
    public static GameplayEdgeEvent weaponDischarge(
            long simTick, long entityId, int mountIndex, float aimFacingDeg, float flightBearingDeg,
            float birthSpeed) {
        return weaponDischarge(simTick, entityId, mountIndex, aimFacingDeg, flightBearingDeg,
                birthSpeed, Float.NaN, Float.NaN);
    }

    /** A {@code VENT_WINDOW} edge ({@code start=true} → START, else END). */
    public static GameplayEdgeEvent ventWindow(long simTick, long entityId, boolean start) {
        return new GameplayEdgeEvent(
                simTick, entityId, GameplayEdgeEventKind.VENT_WINDOW,
                start ? GameplayEdgeEventPhase.START : GameplayEdgeEventPhase.END,
                NO_MOUNT, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, TARGET_ABSENT);
    }

    /** A {@code SYSTEM_WINDOW} edge ({@code on=true} → ON, else OFF). */
    public static GameplayEdgeEvent systemWindow(long simTick, long entityId, boolean on) {
        return new GameplayEdgeEvent(
                simTick, entityId, GameplayEdgeEventKind.SYSTEM_WINDOW,
                on ? GameplayEdgeEventPhase.ON : GameplayEdgeEventPhase.OFF,
                NO_MOUNT, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, TARGET_ABSENT);
    }

    /**
     * A {@code SYSTEM_WINDOW} ON edge carrying the mote-attractor target
     * (schema v12): {@code targetX}/{@code targetY} = {@code SharedMoteAIData.attractorTarget}
     * (world, both finite), {@code targetWireId} = the locked ship's wire id or
     * {@link #TARGET_NONE} for an empty-space target. Same kind/phase as
     * {@link #systemWindow(long, long, boolean)} so every window consumer folds it unchanged;
     * only the presenter reads the payload.
     */
    public static GameplayEdgeEvent systemWindowMoteTarget(
            long simTick, long entityId, float targetX, float targetY, long targetWireId) {
        return new GameplayEdgeEvent(
                simTick, entityId, GameplayEdgeEventKind.SYSTEM_WINDOW,
                GameplayEdgeEventPhase.ON, NO_MOUNT, targetX, targetY, Float.NaN,
                Float.NaN, Float.NaN, targetWireId);
    }

    /**
     * A {@code SYSTEM_JUMP} marker: the ship's teleport-class system fired
     * its single-frame jump at {@code simTick}; {@code destX}/{@code destY} = the
     * authoritative post-jump pose (the two float slots re-purposed; both must be finite).
     */
    public static GameplayEdgeEvent systemJump(long simTick, long entityId, float destX, float destY) {
        return new GameplayEdgeEvent(
                simTick, entityId, GameplayEdgeEventKind.SYSTEM_JUMP,
                GameplayEdgeEventPhase.ONE_SHOT, NO_MOUNT, destX, destY, Float.NaN,
                Float.NaN, Float.NaN, TARGET_ABSENT);
    }

    /**
     * A {@code MISSILE_SPLIT} marker: the MIRV-class parent missile
     * {@code parentEntityId} (v13a stable network id) split at {@code simTick};
     * {@code splitX}/{@code splitY} = its last authoritative position (the two float
     * slots re-purposed; both must be finite).
     */
    public static GameplayEdgeEvent missileSplit(long simTick, long parentEntityId, float splitX, float splitY) {
        return new GameplayEdgeEvent(
                simTick, parentEntityId, GameplayEdgeEventKind.MISSILE_SPLIT,
                GameplayEdgeEventPhase.ONE_SHOT, NO_MOUNT, splitX, splitY, Float.NaN,
                Float.NaN, Float.NaN, TARGET_ABSENT);
    }

    /** {@code MISSILE_END} end-cause wire code: damage kill (post-mortem hp ≤ 0). */
    public static final int END_CAUSE_DESTROYED = 0;
    /** {@code MISSILE_END} end-cause wire code: fizzled out ({@code isFizzling} at delist). */
    public static final int END_CAUSE_FIZZLING = 1;
    /** {@code MISSILE_END} end-cause wire code: clean {@code removeObject} — hp untouched,
     *  not fizzling; an EOL mine's self-destruct detonation delists with THIS signature
     *  ({@code GuidedProximityFuseAI}: trigger un-fizzles, explode removes). */
    public static final int END_CAUSE_INTACT = 2;
    /** {@code MISSILE_END} end-cause wire code (schema v15): the INTACT signature
     *  (clean {@code removeObject}) of a missile whose LAST impact the server engine's own
     *  {@code ApplyDamageResultAPI} reported as SHIELD-absorbed (shield damage or over-max shield
     *  damage {@code > 0}) — vanilla's {@code onHit(..., shieldHit=true, ...)} branch, on which a
     *  devouring-swarm carrier mints NOTHING ({@code DevouringSwarmMissileEffect.onHit}).
     *  Every consumer but the devour end-hook treats it as INTACT ({@link #endCauseIntactClass}). */
    public static final int END_CAUSE_SHIELD_ABSORBED = 3;

    /** TRUE for the two clean-{@code removeObject} codes ({@link #END_CAUSE_INTACT} and its v15
     *  shield-absorbed refinement {@link #END_CAUSE_SHIELD_ABSORBED}) — the "impact class" every
     *  generic end consumer keys on; only the devour end-hook separates the two. */
    public static boolean endCauseIntactClass(int endCauseCode) {
        return endCauseCode == END_CAUSE_INTACT || endCauseCode == END_CAUSE_SHIELD_ABSORBED;
    }

    /**
     * A {@code MISSILE_END} marker (schema v5): the sourceless missile
     * {@code missileEntityId} (v13a stable network id) left the missile roster at
     * {@code simTick} with post-mortem triage {@code endCauseCode}
     * ({@link #END_CAUSE_DESTROYED}/{@link #END_CAUSE_FIZZLING}/{@link #END_CAUSE_INTACT},
     * carried in the {@code mountIndex} slot); {@code endX}/{@code endY} = its last
     * authoritative position (the two float slots re-purposed; both must be finite).
     * Victim ABSENT (the pre-v15 shape; see {@link #missileEnd(long, long, int, float, float, long)}).
     */
    public static GameplayEdgeEvent missileEnd(
            long simTick, long missileEntityId, int endCauseCode, float endX, float endY) {
        return missileEnd(simTick, missileEntityId, endCauseCode, endX, endY, TARGET_ABSENT);
    }

    /**
     * The v15 shape: {@code victimWireId} = the SHIP the missile's LAST engine
     * impact hit (the {@code DamageListener} memo joined at delist, {@code MissileImpactMemo}), or
     * {@link #TARGET_ABSENT} when the server saw no ship impact (or could not resolve the victim's
     * wire id — fail-closed toward "ended on nothing"). {@code endCauseCode} may then read
     * {@link #END_CAUSE_SHIELD_ABSORBED}.
     */
    public static GameplayEdgeEvent missileEnd(
            long simTick, long missileEntityId, int endCauseCode, float endX, float endY,
            long victimWireId) {
        return new GameplayEdgeEvent(
                simTick, missileEntityId, GameplayEdgeEventKind.MISSILE_END,
                GameplayEdgeEventPhase.ONE_SHOT, endCauseCode, endX, endY, Float.NaN,
                Float.NaN, Float.NaN, victimWireId);
    }

    /**
     * An {@code AUTOFIRE_TARGET} pick marker (schema v9): the server's
     * autofire plugin on {@code (firerEntityId, mountIndex)} changed its engine-accepted
     * target pick at {@code simTick} to the ship {@code targetWireId}
     * ({@link #TARGET_NONE} = pick cleared; the same event re-emits as a slow keepalive
     * while a non-null pick is held — application is idempotent set-semantics like every
     * kind).
     */
    public static GameplayEdgeEvent autofireTarget(
            long simTick, long firerEntityId, int mountIndex, long targetWireId) {
        return new GameplayEdgeEvent(
                simTick, firerEntityId, GameplayEdgeEventKind.AUTOFIRE_TARGET,
                GameplayEdgeEventPhase.ONE_SHOT, mountIndex, Float.NaN, Float.NaN, Float.NaN,
                Float.NaN, Float.NaN, targetWireId);
    }

    /**
     * A {@code PALADIN_DET} marker (schema v10): the server
     * engine's {@code PaladinEffect} script spawned its fingerprint-matched
     * {@code DamagingExplosion} at {@code simTick}; {@code sourceEntityId} = the firing
     * ship's stable network id, {@code mountIndex} = mount ordinal or {@link #NO_MOUNT}
     * (the live case — script dets carry no weapon), {@code detX}/{@code detY} = the det
     * birth position (the two float slots re-purposed; both must be finite).
     */
    public static GameplayEdgeEvent paladinDet(
            long simTick, long sourceEntityId, int mountIndex, float detX, float detY) {
        return new GameplayEdgeEvent(
                simTick, sourceEntityId, GameplayEdgeEventKind.PALADIN_DET,
                GameplayEdgeEventPhase.ONE_SHOT, mountIndex, detX, detY, Float.NaN,
                Float.NaN, Float.NaN, TARGET_ABSENT);
    }

    /** {@code PALADIN_DET} payload: det birth X (the {@code aimFacingDeg} slot). */
    public float detX() {
        return aimFacingDeg;
    }

    /** {@code PALADIN_DET} payload: det birth Y (the {@code flightBearingDeg} slot). */
    public float detY() {
        return flightBearingDeg;
    }

    /**
     * An {@code ARC_HIT} (schema v13): the server engine's {@code EmpArcEntity}
     * damaged the ship {@code targetWireId} at {@code simTick} (its first advance after the
     * spawn); {@code sourceEntityId} = the arc's source ship, {@code arcFlavor} = the
     * {@code ARC_FLAVOR_*} code (the {@code mountIndex} slot), {@code hitX}/{@code hitY} =
     * the arc's node world point (the two float slots, both finite), and the damage triple
     * rides {@code birthSpeed}/{@code muzzleX}/{@code muzzleY} under the shield pair law —
     * pass {@code Float.NaN} for {@code dmgShield} on a hull hit, or for BOTH
     * {@code dmgArmor} and {@code dmgHull} on a shield hit.
     */
    public static GameplayEdgeEvent arcHit(long simTick, long sourceEntityId, long targetWireId, int arcFlavor,
            float hitX, float hitY, float dmgArmor, float dmgHull, float dmgShield) {
        return new GameplayEdgeEvent(
                simTick, sourceEntityId, GameplayEdgeEventKind.ARC_HIT,
                GameplayEdgeEventPhase.ONE_SHOT, arcFlavor, hitX, hitY, dmgArmor,
                dmgHull, dmgShield, targetWireId);
    }

    /** {@code ARC_HIT} payload: the arc's node world X (the {@code aimFacingDeg} slot). */
    public float hitX() {
        return aimFacingDeg;
    }

    /** {@code ARC_HIT} payload: the arc's node world Y (the {@code flightBearingDeg} slot). */
    public float hitY() {
        return flightBearingDeg;
    }

    /** {@code ARC_HIT} payload: the {@code ARC_FLAVOR_*} code (the {@code mountIndex} slot). */
    public int arcFlavor() {
        return mountIndex;
    }

    /** {@code ARC_HIT} payload: applied armor damage (the {@code birthSpeed} slot; NaN on a shield hit). */
    public float dmgArmor() {
        return birthSpeed;
    }

    /** {@code ARC_HIT} payload: applied hull damage (the {@code muzzleX} slot; NaN on a shield hit). */
    public float dmgHull() {
        return muzzleX;
    }

    /** {@code ARC_HIT} payload: applied shield damage (the {@code muzzleY} slot; NaN on a hull hit). */
    public float dmgShield() {
        return muzzleY;
    }

    /** {@code ARC_HIT} payload: true when the arc landed on the shield (≡ {@code !isNaN(dmgShield)}). */
    public boolean isShieldHit() {
        return !Float.isNaN(muzzleY);
    }

    // The CRIT_MALFUNCTION factory + accessors.
    /**
     * A {@code CRIT_MALFUNCTION} (schema v14): the server engine ran its own
     * {@code applyCriticalMalfunction} on the ship {@code victimWireId} at {@code simTick};
     * {@code moduleCode} = {@link #critModuleCode(boolean, int)}, {@code permanent} = the
     * plugin's {@code isOkToPermanentlyDisable} answer, {@code branch} = a
     * {@code CRIT_BRANCH_*} code, {@code amount} = the hull amount (finite, 0..ceil).
     */
    public static GameplayEdgeEvent critMalfunction(long simTick, long victimWireId, int moduleCode,
            boolean permanent, int branch, float amount) {
        return new GameplayEdgeEvent(
                simTick, victimWireId, GameplayEdgeEventKind.CRIT_MALFUNCTION,
                GameplayEdgeEventPhase.ONE_SHOT, moduleCode, permanent ? 1f : 0f, (float) branch,
                amount, Float.NaN, Float.NaN, TARGET_ABSENT);
    }

    /** {@code CRIT_MALFUNCTION} module code: {@code ordinal} (weapon) or
     *  {@link #CRIT_MODULE_ENGINE_BASE} + {@code ordinal} (engine); throws on an out-of-range ordinal. */
    public static int critModuleCode(boolean engine, int ordinal) {
        int max = engine ? CRIT_ENGINE_ORDINAL_MAX : CRIT_WEAPON_ORDINAL_MAX;
        if (ordinal < 0 || ordinal > max) {
            throw new IllegalArgumentException("CRIT_MALFUNCTION " + (engine ? "engine" : "weapon")
                    + " ordinal must be in 0.." + max + "; got " + ordinal);
        }
        return engine ? CRIT_MODULE_ENGINE_BASE + ordinal : ordinal;
    }

    /** {@code CRIT_MALFUNCTION} payload: true when the module code names an ENGINE. */
    public boolean critModuleIsEngine() {
        return mountIndex >= CRIT_MODULE_ENGINE_BASE;
    }

    /** {@code CRIT_MALFUNCTION} payload: the weapon or engine ordinal (base stripped). */
    public int critModuleOrdinal() {
        return critModuleIsEngine() ? mountIndex - CRIT_MODULE_ENGINE_BASE : mountIndex;
    }

    /** {@code CRIT_MALFUNCTION} payload: the permanent-disable flag (the {@code aimFacingDeg} slot). */
    public boolean critPermanent() {
        return aimFacingDeg == 1f;
    }

    /** {@code CRIT_MALFUNCTION} payload: the {@code CRIT_BRANCH_*} code (the {@code flightBearingDeg} slot). */
    public int critBranch() {
        return (int) flightBearingDeg;
    }

    /** {@code CRIT_MALFUNCTION} payload: the hull amount (the {@code birthSpeed} slot). */
    public float critAmount() {
        return birthSpeed;
    }

    /** {@code SYSTEM_JUMP} payload: authoritative post-jump destination X (the {@code aimFacingDeg} slot). */
    public float destX() {
        return aimFacingDeg;
    }

    /** {@code SYSTEM_JUMP} payload: authoritative post-jump destination Y (the {@code flightBearingDeg} slot). */
    public float destY() {
        return flightBearingDeg;
    }

    /** {@code MISSILE_SPLIT} payload: parent's last authoritative X (the {@code aimFacingDeg} slot). */
    public float splitX() {
        return aimFacingDeg;
    }

    /** {@code MISSILE_SPLIT} payload: parent's last authoritative Y (the {@code flightBearingDeg} slot). */
    public float splitY() {
        return flightBearingDeg;
    }

    /** {@code MISSILE_END} payload: the end-cause wire code (the {@code mountIndex} slot). */
    public int endCauseCode() {
        return mountIndex;
    }

    /** {@code MISSILE_END} payload: missile's last authoritative X (the {@code aimFacingDeg} slot). */
    public float endX() {
        return aimFacingDeg;
    }

    /** {@code MISSILE_END} payload: missile's last authoritative Y (the {@code flightBearingDeg} slot). */
    public float endY() {
        return flightBearingDeg;
    }

    /** {@code MISSILE_END} payload (v15): the wire id of the ship the missile's LAST
     *  engine impact hit (the {@code targetWireId} slot), or {@link #TARGET_ABSENT}. */
    public long endVictimWireId() {
        return targetWireId;
    }

    /**
     * {@code WEAPON_DISCHARGE} payload (v16): the declared wait in raw ticks (the
     * {@code targetWireId} slot, {@code 0..}{@link #DECLARED_WAIT_MAX_TICKS}), or -1 when absent. Any
     * other kind answers -1 — its slot is never a wait (the kind-first reader law), and the int cast
     * of a ship wire id would truncate.
     */
    public int declaredWaitTicks() {
        return kind == GameplayEdgeEventKind.WEAPON_DISCHARGE ? (int) targetWireId : (int) TARGET_ABSENT;
    }

    /**
     * A copy of this {@code WEAPON_DISCHARGE} edge with ONLY the declared-wait slot replaced (v16;
     * the serve loop's post-step stamp). {@code waitTicks} = -1 (absent) or
     * {@code 0..}{@link #DECLARED_WAIT_MAX_TICKS} — the constructor's slot law rejects anything else
     * ({@link IllegalArgumentException}); any other kind is rejected too (its slot carries no wait).
     *
     */
    public GameplayEdgeEvent withDeclaredWait(int waitTicks) {
        if (kind != GameplayEdgeEventKind.WEAPON_DISCHARGE) {
            throw new IllegalArgumentException("declared wait is WEAPON_DISCHARGE-only; got " + kind);
        }
        return new GameplayEdgeEvent(simTick, entityId, kind, phase, mountIndex, aimFacingDeg,
                flightBearingDeg, birthSpeed, muzzleX, muzzleY, waitTicks);
    }

    private static void requireWindowPayload(int mountIndex, float aimFacingDeg, float flightBearingDeg,
            float birthSpeed) {
        if (mountIndex != NO_MOUNT) {
            throw new IllegalArgumentException(
                    "window kinds carry mountIndex NO_MOUNT (" + NO_MOUNT + "); got " + mountIndex);
        }
        if (!Float.isNaN(aimFacingDeg)) {
            throw new IllegalArgumentException(
                    "window kinds carry aimFacingDeg NaN; got " + aimFacingDeg);
        }
        if (!Float.isNaN(flightBearingDeg)) {
            throw new IllegalArgumentException(
                    "window kinds carry flightBearingDeg NaN; got " + flightBearingDeg);
        }
        if (!Float.isNaN(birthSpeed)) {
            throw new IllegalArgumentException(
                    "window kinds carry birthSpeed NaN; got " + birthSpeed);
        }
    }

    /** The v8 muzzle pair is {@code WEAPON_DISCHARGE}-only (the {@code birthSpeed} law). */
    private static void requireNaNMuzzle(GameplayEdgeEventKind kind, float muzzleX, float muzzleY) {
        if (!Float.isNaN(muzzleX) || !Float.isNaN(muzzleY)) {
            throw new IllegalArgumentException(
                    kind + " carries muzzleX/muzzleY NaN; got " + muzzleX + "/" + muzzleY);
        }
    }

    /** The v9 long slot carries a payload only on the kinds whose own law names one
     *  ({@code AUTOFIRE_TARGET}, {@code ARC_HIT}, {@code MISSILE_END}, the {@code SYSTEM_WINDOW} mote ON
     *  edge, and the v16 {@code WEAPON_DISCHARGE} declared wait); every caller of this check carries the
     *  canonical {@link #TARGET_ABSENT} sentinel (the {@code birthSpeed} slot law). */
    private static void requireAbsentTarget(GameplayEdgeEventKind kind, long targetWireId) {
        if (targetWireId != TARGET_ABSENT) {
            throw new IllegalArgumentException(
                    kind + " carries targetWireId TARGET_ABSENT (" + TARGET_ABSENT + "); got "
                            + targetWireId);
        }
    }

    /** The v6 slot is {@code WEAPON_DISCHARGE}-only: every other kind carries canonical
     *  {@code NaN} — the two REUSED slots (dest/split/end) never extend to the third. */
    private static void requireNaNBirthSpeed(GameplayEdgeEventKind kind, float birthSpeed) {
        if (!Float.isNaN(birthSpeed)) {
            throw new IllegalArgumentException(
                    kind + " carries birthSpeed NaN; got " + birthSpeed);
        }
    }
}
