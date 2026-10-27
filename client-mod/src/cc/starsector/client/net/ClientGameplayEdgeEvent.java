package cc.starsector.client.net;

/**
 * Client-side mirror of the server's tick-stamped gameplay edge event
 * ({@code cc.starsector.server.protocol.GameplayEdgeEvent}):
 * a weapon discharge,
 * vent-window edge, or ship-system-window edge the server detected post-step from
 * engine-accepted state, stamped with the exact completed {@link #simTick()}.
 *
 * <p>Like {@link ClientEntityDespawn}, the mod deliberately does not bundle the server
 * protocol class — it mirrors exactly the fields the {@code GPEE} wire frame carries
 * ({@link ClientGameplayEdgeEventReader}), with the kind/phase wire-code tables
 * duplicated here (stable codes, never
 * ordinals). The game-free verifier
 *
 * (archived; run its 3 steps from repo root) cross-asserts these tables
 * against the server enums so the mirrors cannot drift silently.
 *
 * <p>{@link #mountIndex()}/{@link #aimFacingDeg()}/{@link #flightBearingDeg()} are the
 * {@code WEAPON_DISCHARGE} payload; window kinds carry {@link #NO_MOUNT}/{@code NaN}
 * canonically. {@code aimFacingDeg} may be {@code NaN} for discharges too (the
 * server's fail-soft "aim read unbound" marker — never a faked value). Since the launch-facing change it is the
 * LAUNCH facing: a MISSILE discharge's fresh projectile facing (mount + barrel offset), the
 * mount angle for every other kind.
 * {@code flightBearingDeg} (schema v2) is the server's TRUE
 * initial flight bearing — the engine's own spawn composition
 * {@code shooterVel + projectileSpeed·û(mountRay)}, normalized [0,360) — {@code NaN}
 * when any input was unreadable (or the frame was legacy v1): the honest-absence
 * marker, never a faked value. {@code birthSpeed} (schema v6) is the
 * {@code |v0|} half of the same truth — the server's birth-velocity magnitude in engine
 * units/sec (measured on the confession lane, composed on the poll lane), so consumers
 * can fly the full server vector {@code birthSpeed·û(flightBearingDeg)} instead of
 * estimating the magnitude locally; {@code NaN} = honest absence, same law.
 * {@code muzzleX}/{@code muzzleY} (schema v8)
 * complete spawn-truth: the server-true world position the projectile was BORN at, so the
 * presenter can spawn at the server's muzzle instead of re-deriving it from a lagged
 * shooter replica. The pair is ATOMIC — both finite or both {@code NaN} (honest absence);
 * a mixed pair is rejected fail-closed. {@code WEAPON_DISCHARGE}-only, like
 * {@code birthSpeed}.
 * {@code targetWireId} (schema v9) is the {@code AUTOFIRE_TARGET}
 * payload — the wire id of the SHIP the server's autofire plugin now tracks on
 * {@code (entityId, mountIndex)}, or {@link #TARGET_NONE} for a cleared pick; a kind with no
 * payload on that slot carries {@link #TARGET_ABSENT} canonically (the {@code birthSpeed} slot
 * law extended to the long tail; the kinds that re-use it are named in their own paragraphs).
 *
 * <p>{@code PALADIN_DET} (schema v10): {@code entityId} = the
 * SOURCE ship, {@code mountIndex} = firing mount ordinal or {@link #NO_MOUNT} (script dets
 * carry no weapon, so {@code NO_MOUNT} is the live case), and the two reused float slots
 * carry the det position {@link #detX()}/{@link #detY()} (both REQUIRED finite — the
 * server producer read the birth pose off the engine object; a miss skips emission,
 * never NaN).
 *
 * <p>{@code ARC_HIT} (schema v13):
 * the server's {@code DamageListener} seam named an EMP-arc hit — {@code entityId} = the SOURCE
 * ship, {@link #targetWireId()} = the VICTIM ship ({@code > 0}, never a sentinel),
 * {@link #arcFlavor()} (the {@code mountIndex} slot) = {@code ARC_FLAVOR_*}, {@link #hitX()}/
 * {@link #hitY()} = the arc's node world point (both REQUIRED finite), and the three damage
 * slots carry the APPLIED split: hull hit = {@link #dmgArmor()} + {@link #dmgHull()} finite
 * {@code ≥ 0} with {@link #dmgShield()} NaN; shield hit = {@link #dmgShield()} finite {@code ≥ 0}
 * with the other two NaN (the v8 pair idiom — any other combination is corruption, rejected).
 * {@link #isShieldHit()} ≡ {@code !isNaN(dmgShield)}. EMP is NOT carried (no client consumer).
 *
 * <p>{@code CRIT_MALFUNCTION} (schema v14):
 * the server observed the engine's critical-malfunction decision ({@code Ship.applyCriticalMalfunction})
 * — {@code entityId} = the VICTIM ship, {@link #targetWireId()} = {@link #TARGET_ABSENT}, the
 * {@code mountIndex} slot = the module code (a weapon ordinal {@code 0..}{@link #CRIT_WEAPON_ORDINAL_MAX}
 * or {@link #CRIT_MODULE_ENGINE_BASE} + an engine ordinal {@code 0..}{@link #CRIT_ENGINE_ORDINAL_MAX};
 * {@link #critModuleIsEngine()}/{@link #critModuleOrdinal()}), {@link #critPermanent()} (the aim
 * slot, EXACTLY 0f/1f), {@link #critBranch()} (the bearing slot, EXACTLY one of the
 * {@code CRIT_BRANCH_*} codes), {@link #critAmount()} (the birthSpeed slot, finite
 * {@code 0..}{@link #CRIT_AMOUNT_CEIL}), and the muzzle pair canonical NaN/NaN. Any other value is
 * corruption, rejected at construction.
 *
 * <p>{@code MISSILE_END} victim form (schema v15): {@link #endVictimWireId()} = the
 * SHIP the missile's LAST server-observed impact hit ({@code > 0}) or {@link #TARGET_ABSENT}; the
 * end-cause vocabulary gains {@link #END_CAUSE_SHIELD_ABSORBED} (3), INTACT-class to every generic
 * consumer ({@link #endedIntact()}) and the devour end-hook's no-mint verdict ({@link #endedOnShield()}).
 * Width unchanged.
 *
 * <p>{@code WEAPON_DISCHARGE} declared wait (schema v16):
 * {@link #declaredWaitTicks()} (the {@code targetWireId} slot) = the RAW server ticks from the
 * declaration's arrival tick ({@code applyTick}) to this discharge's {@code simTick}, in
 * {@code 0..}{@link #DECLARED_WAIT_MAX_TICKS} (a READY gun reads 0; the server clamps a longer wait to
 * the max), or {@link #TARGET_ABSENT} = no declaration was consumed by this discharge, or its arrival
 * is unknown. Any other value is corruption, rejected at construction. The kit's lead drain reads it
 * ({@code OwnFireLockLaw.leadDrainTicks}); {@link #toString()} keeps the slot's {@code targetWireId}
 * token name. Width unchanged.
 */
public final class ClientGameplayEdgeEvent {

    /** Mirror of {@code GameplayEdgeEventKind} (stable wire codes per the replication spec; SYSTEM_JUMP =
     *  schema v3 — the teleport jump marker, dest in the two float slots;
     *  MISSILE_SPLIT = schema v4 — the MIRV split marker, parent's last
     *  position in the two float slots, entityId = the PARENT MISSILE network id ≥ 1e9;
     *  MISSILE_END = schema v5 — the sourceless-missile end-cause marker:
     *  {@code mountIndex} slot carries the {@code END_CAUSE_*} code, floats the last
     *  authoritative position, entityId = the MISSILE network id ≥ 1e9 (v15:
     *  {@code targetWireId} = the VICTIM ship of the last server-observed impact or
     *  {@link #TARGET_ABSENT}; code 3 = {@code SHIELD_ABSORBED}, INTACT-class to all but the
     *  devour end-hook);
     *  AUTOFIRE_TARGET = schema v9 — the server autofire-pick marker:
     *  {@code targetWireId} carries the picked SHIP's wire id or {@link #TARGET_NONE},
     *  {@code mountIndex} the firer's mount ordinal, all five float slots canonical NaN;
     *  PALADIN_DET = schema v10 — the server-side Paladin PD
     *  {@code DamagingExplosion} det marker: det x/y in the two float slots, entityId =
     *  the SOURCE ship, {@code mountIndex} ordinal or {@code NO_MOUNT};
     *  SYSTEM_WINDOW mote-target form = schema v12 — an ON edge of the
     *  Ziggurat's {@code mote_control} press may carry the attractor target world x/y in the
     *  two float slots + the locked ship's wire id or {@link #TARGET_NONE} in
     *  {@code targetWireId}; every window consumer folds it as a plain ON edge, only
     *  {@code MoteAttractorFxLane} reads the payload);
     *  ARC_HIT = schema v13 — the server-named EMP-arc hit marker: entityId =
     *  the SOURCE ship, {@code targetWireId} the VICTIM ship, {@code mountIndex} the
     *  {@code ARC_FLAVOR_*} code, hit x/y in the aim/bearing slots, the applied damage split
     *  in the birthSpeed/muzzle slots (shield law in the ctor);
     *  CRIT_MALFUNCTION = schema v14 — the server-observed critical malfunction:
     *  entityId = the VICTIM ship, module code / perm / branch / hull amount in the
     *  mount / aim / bearing / birthSpeed slots (exact-set law in the ctor). */
    public enum Kind {
        WEAPON_DISCHARGE(1),
        VENT_WINDOW(2),
        SYSTEM_WINDOW(3),
        SYSTEM_JUMP(4),
        MISSILE_SPLIT(5),
        MISSILE_END(6),
        AUTOFIRE_TARGET(7),
        PALADIN_DET(8),
        ARC_HIT(9),
        CRIT_MALFUNCTION(10); // Schema v14

        private final int wireCode;

        Kind(int wireCode) {
            this.wireCode = wireCode;
        }

        public int wireCode() {
            return wireCode;
        }

        /** Resolve a kind from its wire code; fail-closed on an unknown code. */
        public static Kind fromWireCode(int wireCode) {
            for (Kind kind : values()) {
                if (kind.wireCode == wireCode) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unknown gameplay edge event kind wire code: " + wireCode);
        }
    }

    /** Mirror of {@code GameplayEdgeEventPhase} (stable wire codes per the replication spec). */
    public enum Phase {
        ONE_SHOT(1),
        START(2),
        END(3),
        ON(4),
        OFF(5);

        private final int wireCode;

        Phase(int wireCode) {
            this.wireCode = wireCode;
        }

        public int wireCode() {
            return wireCode;
        }

        /** Resolve a phase from its wire code; fail-closed on an unknown code. */
        public static Phase fromWireCode(int wireCode) {
            for (Phase phase : values()) {
                if (phase.wireCode == wireCode) {
                    return phase;
                }
            }
            throw new IllegalArgumentException("unknown gameplay edge event phase wire code: " + wireCode);
        }
    }

    /** Canonical {@link #mountIndex()} for the non-mount (window) kinds. */
    public static final int NO_MOUNT = -1;

    /** Decode-side plausibility ceiling for {@link #birthSpeed()} (engine units/sec):
     *  vanilla birth speeds top out around ~1.8k (fastest bolt + fastest shooter), so 10k
     *  flags only garbage — a frame that passed the codec but carries an absurd magnitude
     *  must not steer a spawn (consumers fall back to their local estimate, fail-soft). */
    public static final float MAX_PLAUSIBLE_BIRTH_SPEED_UPS = 10_000f;

    /** Whether {@link #birthSpeed()} may steer a presentation: finite (never NaN-absence;
     *  negative already rejected at construction) and under the plausibility ceiling. */
    public boolean birthSpeedUsable() {
        return Float.isFinite(birthSpeed) && birthSpeed <= MAX_PLAUSIBLE_BIRTH_SPEED_UPS;
    }

    /** Decode-side plausibility ceiling for {@link #muzzleX()}/{@link #muzzleY()} (engine
     *  units, per axis): the largest certified map half is 9000 u and combat coordinates
     *  stay within a few map-halves of origin, so 100k flags only garbage — the
     *  {@link #MAX_PLAUSIBLE_BIRTH_SPEED_UPS} idiom. */
    public static final float MAX_PLAUSIBLE_MUZZLE_ABS_U = 100_000f;

    /** Whether the v8 muzzle pair may steer a spawn point: both finite (the ctor pair law
     *  already rejects a mixed pair) and both under the plausibility ceiling —
     *  consumers fall back to the local fire point (fail-soft, never a faked value). */
    public boolean muzzleUsable() {
        return Float.isFinite(muzzleX) && Float.isFinite(muzzleY)
                && Math.abs(muzzleX) <= MAX_PLAUSIBLE_MUZZLE_ABS_U
                && Math.abs(muzzleY) <= MAX_PLAUSIBLE_MUZZLE_ABS_U;
    }

    /** {@code MISSILE_END} end-cause wire code: damage kill (post-mortem hp ≤ 0). */
    public static final int END_CAUSE_DESTROYED = 0;
    /** {@code MISSILE_END} end-cause wire code: fizzled out ({@code isFizzling} at delist). */
    public static final int END_CAUSE_FIZZLING = 1;
    /** {@code MISSILE_END} end-cause wire code: clean {@code removeObject} — hp untouched,
     *  not fizzling; an EOL mine's self-destruct detonation delists with THIS signature. */
    public static final int END_CAUSE_INTACT = 2;
    /** {@code MISSILE_END} end-cause wire code (schema v15): the INTACT signature of a
     *  missile whose LAST impact the server engine's own {@code ApplyDamageResultAPI} reported as
     *  SHIELD-absorbed — vanilla's {@code onHit(..., shieldHit=true, ...)} branch, on which a
     *  devouring-swarm carrier mints NOTHING ({@code DevouringSwarmMissileEffect.onHit}). Every
     *  consumer but the devour end-hook treats it as INTACT ({@link #endCauseIntactClass}). */
    public static final int END_CAUSE_SHIELD_ABSORBED = 3;

    /** TRUE for the two clean-{@code removeObject} codes ({@link #END_CAUSE_INTACT} and its v15
     *  shield-absorbed refinement {@link #END_CAUSE_SHIELD_ABSORBED}) — the "impact class" every
     *  generic end consumer keys on (impact puff, mine det, unstable det); only the devour end-hook
     *  separates the two. Mirror of the server's {@code GameplayEdgeEvent.endCauseIntactClass}. */
    public static boolean endCauseIntactClass(int endCauseCode) {
        return endCauseCode == END_CAUSE_INTACT || endCauseCode == END_CAUSE_SHIELD_ABSORBED;
    }

    /** {@code ARC_HIT} flavor code (the {@code mountIndex} slot): an arc the client's local
     *  wrappers already present (ion/tachyon/voltaic-cannon …) — the FX lane skips it. Unknown
     *  codes are legal on the wire and treated as OTHER. */
    public static final int ARC_FLAVOR_OTHER = 0;
    /** {@code ARC_HIT} flavor code: Shrouded {@code inimical_emanation_impact} arc. */
    public static final int ARC_FLAVOR_INIMICAL = 1;
    /** {@code ARC_HIT} flavor code: Threat {@code voltaic_discharge_emp_impact} arc. */
    public static final int ARC_FLAVOR_VOLTAIC = 2;
    /** {@code ARC_HIT} flavor code: the Overseer energy lash's ENEMY branch
     *  ({@code energy_lash_enemy_impact}; one arc per system slot; a phased victim overloads 1 s). */
    public static final int ARC_FLAVOR_LASH = 3;
    /** {@code ARC_HIT} flavor code: the lash's SAME-OWNER branch ({@code energy_lash_friendly_impact}). */
    public static final int ARC_FLAVOR_LASH_FRIENDLY = 4;
    /** {@code ARC_HIT} flavor code: the projectile ion family's 25 % on-hit arc
     *  ({@code IonCannonOnHitEffect}: ion cannon / fighter ion cannon / ion pulser) — the server
     *  rolled it; {@code CcWrapIonCannonOnHitEffect} shuts the replica's own roll, {@code ArcHitFxLane} draws this. */
    public static final int ARC_FLAVOR_ION = 5;
    /** {@code ARC_HIT} flavor code: the sabot MIRV child's 25 % on-hit arc
     *  ({@code SabotOnHitEffect}: the ion recipe at dam 0) — the server rolled it;
     *  {@code CcWrapSabotOnHitEffect} shuts the presented child's own roll, {@code ArcHitFxLane} draws this. */
    public static final int ARC_FLAVOR_SABOT = 6;

    // The CRIT_MALFUNCTION (v14) contract constants —
    // same names as the server record (GameplayEdgeEvent), exact-set law in the ctor.
    /** {@code CRIT_MALFUNCTION} module code base for an ENGINE module: code = base + engine ordinal. */
    public static final int CRIT_MODULE_ENGINE_BASE = 1000;
    /** {@code CRIT_MALFUNCTION} largest legal WEAPON ordinal (index into {@code ship.getAllWeapons()}). */
    public static final int CRIT_WEAPON_ORDINAL_MAX = 255;
    /** {@code CRIT_MALFUNCTION} largest legal ENGINE ordinal (index into {@code getShipEngines()}). */
    public static final int CRIT_ENGINE_ORDINAL_MAX = 31;
    /** {@code CRIT_MALFUNCTION} hull branch: vanilla's direct floaty + {@code setHitpoints} write (75 %). */
    public static final int CRIT_BRANCH_DIRECT = 0;
    /** {@code CRIT_MALFUNCTION} hull branch: vanilla's {@code applyDamage(ENERGY)} path (25 %). */
    public static final int CRIT_BRANCH_DAMAGE = 1;
    /** {@code CRIT_MALFUNCTION} hull branch: the lethal {@code applyDamage(10x max)} write (kill lane owns it). */
    public static final int CRIT_BRANCH_LETHAL = 2;
    /** {@code CRIT_MALFUNCTION} hull-amount decode ceiling (finite, {@code >= 0}, at most this). */
    public static final float CRIT_AMOUNT_CEIL = 1_000_000f;

    /** Canonical {@link #targetWireId()} on every kind except {@code AUTOFIRE_TARGET}
     *  (mirror of the server's {@code GameplayEdgeEvent.TARGET_ABSENT} — the
     *  sentinel family: negatives are free, every real wire id is {@code > 0}). */
    public static final long TARGET_ABSENT = -1L;

    /** {@code AUTOFIRE_TARGET} payload: the server's pick was CLEARED — the client reverts
     *  to its local autofire pick (mirror of the server's {@code TARGET_NONE}). */
    public static final long TARGET_NONE = -2L;

    /** The largest {@link #declaredWaitTicks()} a
     *  {@code WEAPON_DISCHARGE} may carry (schema v16; mirror of the server's constant — the server
     *  clamps a longer wait to it, the decode law rejects anything above it). */
    public static final int DECLARED_WAIT_MAX_TICKS = 255;

    private final long simTick;
    private final long entityId;
    private final Kind kind;
    private final Phase phase;
    private final int mountIndex;
    private final float aimFacingDeg;
    private final float flightBearingDeg;
    private final float birthSpeed;
    private final float muzzleX;
    private final float muzzleY;
    private final long targetWireId;

    /**
     * Fail-closed construction: rejects the same field/payload contradictions the server
     * record rejects (kind↔phase mismatch, non-canonical window payload), so a corrupt
     * frame can never become a plausible-looking event.
     */
    public ClientGameplayEdgeEvent(long simTick, long entityId, Kind kind, Phase phase,
            int mountIndex, float aimFacingDeg, float flightBearingDeg, float birthSpeed,
            float muzzleX, float muzzleY, long targetWireId) {
        if (simTick < 0L) {
            throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
        }
        if (entityId <= 0L) {
            throw new IllegalArgumentException("entityId must be > 0; got " + entityId);
        }
        boolean moteTargetForm = false; // v12: set by the SYSTEM_WINDOW branch below
        if (kind == null || phase == null) {
            throw new IllegalArgumentException("kind/phase must not be null");
        }
        if (kind == Kind.WEAPON_DISCHARGE) {
            if (phase != Phase.ONE_SHOT) {
                throw new IllegalArgumentException("WEAPON_DISCHARGE phase must be ONE_SHOT; got " + phase);
            }
            if (mountIndex < 0) {
                throw new IllegalArgumentException("WEAPON_DISCHARGE mountIndex must be >= 0; got " + mountIndex);
            }
            if (!Float.isFinite(aimFacingDeg) && !Float.isNaN(aimFacingDeg)) {
                throw new IllegalArgumentException("aimFacingDeg must be finite or NaN; got " + aimFacingDeg);
            }
            if (!Float.isFinite(flightBearingDeg) && !Float.isNaN(flightBearingDeg)) {
                throw new IllegalArgumentException("flightBearingDeg must be finite or NaN; got " + flightBearingDeg);
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
            // The v16 declared wait on the long slot —
            // TARGET_ABSENT or 0..DECLARED_WAIT_MAX_TICKS raw ticks; anything else (TARGET_NONE
            // included) is corruption, fail-closed. Exempted from the generic long-slot law below.
            if (targetWireId != TARGET_ABSENT
                    && (targetWireId < 0L || targetWireId > DECLARED_WAIT_MAX_TICKS)) {
                throw new IllegalArgumentException("WEAPON_DISCHARGE declared wait must be TARGET_ABSENT ("
                        + TARGET_ABSENT + ") or 0.." + DECLARED_WAIT_MAX_TICKS + "; got " + targetWireId);
            }
        } else if (kind == Kind.SYSTEM_JUMP) {
            // ONE_SHOT marker whose float slots carry the authoritative
            // post-jump destX/destY — REQUIRED finite (the window-kind NaN law must NOT
            // apply here; the server never emits a jump without a bound dest).
            if (phase != Phase.ONE_SHOT) {
                throw new IllegalArgumentException("SYSTEM_JUMP phase must be ONE_SHOT; got " + phase);
            }
            if (mountIndex != NO_MOUNT) {
                throw new IllegalArgumentException("SYSTEM_JUMP carries mountIndex NO_MOUNT; got " + mountIndex);
            }
            if (!Float.isFinite(aimFacingDeg) || !Float.isFinite(flightBearingDeg)) {
                throw new IllegalArgumentException("SYSTEM_JUMP destX/destY must both be finite; got "
                        + aimFacingDeg + "/" + flightBearingDeg);
            }
        } else if (kind == Kind.MISSILE_SPLIT) {
            // ONE_SHOT marker whose float slots carry the parent's last
            // authoritative splitX/splitY — REQUIRED finite (the detector diffed the
            // record out, so it always had a bound pose; NaN is never honest here).
            if (phase != Phase.ONE_SHOT) {
                throw new IllegalArgumentException("MISSILE_SPLIT phase must be ONE_SHOT; got " + phase);
            }
            if (mountIndex != NO_MOUNT) {
                throw new IllegalArgumentException("MISSILE_SPLIT carries mountIndex NO_MOUNT; got " + mountIndex);
            }
            if (!Float.isFinite(aimFacingDeg) || !Float.isFinite(flightBearingDeg)) {
                throw new IllegalArgumentException("MISSILE_SPLIT splitX/splitY must both be finite; got "
                        + aimFacingDeg + "/" + flightBearingDeg);
            }
        } else if (kind == Kind.MISSILE_END) {
            // ONE_SHOT marker; the mountIndex slot carries the end-cause
            // wire code (the kind's whole point — det-vs-shotdown wire-true), floats the
            // missile's last authoritative endX/endY — REQUIRED finite (same law as split).
            if (phase != Phase.ONE_SHOT) {
                throw new IllegalArgumentException("MISSILE_END phase must be ONE_SHOT; got " + phase);
            }
            if (mountIndex != END_CAUSE_DESTROYED && mountIndex != END_CAUSE_FIZZLING
                    && mountIndex != END_CAUSE_INTACT && mountIndex != END_CAUSE_SHIELD_ABSORBED) {
                throw new IllegalArgumentException(
                        "MISSILE_END mountIndex must be an END_CAUSE_* code (0/1/2/3); got " + mountIndex);
            }
            if (!Float.isFinite(aimFacingDeg) || !Float.isFinite(flightBearingDeg)) {
                throw new IllegalArgumentException("MISSILE_END endX/endY must both be finite; got "
                        + aimFacingDeg + "/" + flightBearingDeg);
            }
            // v15: the long slot names the VICTIM ship of the missile's last
            // server-observed impact (> 0) or stays TARGET_ABSENT = "ended on nothing the server saw
            // hit"; never TARGET_NONE, never 0 (mirror of the server record's MISSILE_END law).
            if (targetWireId != TARGET_ABSENT && targetWireId <= 0L) {
                throw new IllegalArgumentException("MISSILE_END targetWireId must be > 0 or TARGET_ABSENT ("
                        + TARGET_ABSENT + "); got " + targetWireId);
            }
        } else if (kind == Kind.AUTOFIRE_TARGET) {
            // ONE_SHOT pick marker; targetWireId carries the payload
            // (a real ship wire id > 0 or the explicit CLEARED sentinel), mountIndex the
            // firer's mount ordinal, and all five float slots ride canonical NaN (the
            // window-kind slot law — this kind repurposes NO float slot).
            if (phase != Phase.ONE_SHOT) {
                throw new IllegalArgumentException("AUTOFIRE_TARGET phase must be ONE_SHOT; got " + phase);
            }
            if (mountIndex < 0) {
                throw new IllegalArgumentException("AUTOFIRE_TARGET mountIndex must be >= 0; got " + mountIndex);
            }
            if (!Float.isNaN(aimFacingDeg) || !Float.isNaN(flightBearingDeg)) {
                throw new IllegalArgumentException("AUTOFIRE_TARGET carries aimFacingDeg/flightBearingDeg NaN; got "
                        + aimFacingDeg + "/" + flightBearingDeg);
            }
            if (targetWireId != TARGET_NONE && targetWireId < 1L) {
                throw new IllegalArgumentException("AUTOFIRE_TARGET targetWireId must be > 0 or TARGET_NONE ("
                        + TARGET_NONE + "); got " + targetWireId);
            }
        } else if (kind == Kind.PALADIN_DET) {
            // ONE_SHOT marker whose float slots carry the det birth
            // detX/detY — REQUIRED finite (the server producer fingerprinted the live
            // engine object and read its pose; a miss skips emission, NaN never honest).
            // mountIndex = mount ordinal or NO_MOUNT (the live case — script dets carry
            // no weapon).
            if (phase != Phase.ONE_SHOT) {
                throw new IllegalArgumentException("PALADIN_DET phase must be ONE_SHOT; got " + phase);
            }
            if (mountIndex < NO_MOUNT) {
                throw new IllegalArgumentException("PALADIN_DET mountIndex must be a mount ordinal >= 0 or NO_MOUNT ("
                        + NO_MOUNT + "); got " + mountIndex);
            }
            if (!Float.isFinite(aimFacingDeg) || !Float.isFinite(flightBearingDeg)) {
                throw new IllegalArgumentException("PALADIN_DET detX/detY must both be finite; got "
                        + aimFacingDeg + "/" + flightBearingDeg);
            }
        } else if (kind == Kind.ARC_HIT) {
            // Schema v13, mirror of the server ctor law: ONE_SHOT hit marker.
            // targetWireId = the VICTIM ship (> 0 — the producer skips unresolvable ids, never a
            // sentinel), mountIndex = the ARC_FLAVOR_* code (>= 0; unknown codes legal), the
            // aim/bearing slots = the node world point (REQUIRED finite), and the three damage
            // slots obey the shield law: EXACTLY one of {armor+hull finite >= 0, shield NaN}
            // (hull hit) or {shield finite >= 0, armor+hull NaN} (shield hit). Placed BEFORE the
            // window fold below (a known trap).
            if (phase != Phase.ONE_SHOT) {
                throw new IllegalArgumentException("ARC_HIT phase must be ONE_SHOT; got " + phase);
            }
            if (targetWireId <= 0L) {
                throw new IllegalArgumentException("ARC_HIT targetWireId must be > 0; got " + targetWireId);
            }
            if (mountIndex < 0) {
                throw new IllegalArgumentException("ARC_HIT arcFlavor (mountIndex) must be >= 0; got " + mountIndex);
            }
            if (!Float.isFinite(aimFacingDeg) || !Float.isFinite(flightBearingDeg)) {
                throw new IllegalArgumentException("ARC_HIT hitX/hitY must both be finite; got "
                        + aimFacingDeg + "/" + flightBearingDeg);
            }
            boolean hullForm = Float.isFinite(birthSpeed) && birthSpeed >= 0f
                    && Float.isFinite(muzzleX) && muzzleX >= 0f
                    && Float.isNaN(muzzleY);
            boolean shieldForm = Float.isNaN(birthSpeed) && Float.isNaN(muzzleX)
                    && Float.isFinite(muzzleY) && muzzleY >= 0f;
            if (!hullForm && !shieldForm) {
                throw new IllegalArgumentException(
                        "ARC_HIT damage slots must be {armor,hull finite >= 0; shield NaN} or "
                                + "{shield finite >= 0; armor,hull NaN}; got armor=" + birthSpeed
                                + " hull=" + muzzleX + " shield=" + muzzleY);
            }
        } else if (kind == Kind.CRIT_MALFUNCTION) {
            // Schema v14, mirror of the server ctor law.
            // ONE_SHOT marker; exact-set checks on every repurposed slot (module code, perm flag,
            // branch code, bounded hull amount). The muzzle pair + TARGET_ABSENT canonical laws
            // are enforced by the shared slot checks below. Placed BEFORE the window fold (the
            // known trap).
            if (phase != Phase.ONE_SHOT) {
                throw new IllegalArgumentException("CRIT_MALFUNCTION phase must be ONE_SHOT; got " + phase);
            }
            boolean weaponCode = mountIndex >= 0 && mountIndex <= CRIT_WEAPON_ORDINAL_MAX;
            boolean engineCode = mountIndex >= CRIT_MODULE_ENGINE_BASE
                    && mountIndex <= CRIT_MODULE_ENGINE_BASE + CRIT_ENGINE_ORDINAL_MAX;
            if (!weaponCode && !engineCode) {
                throw new IllegalArgumentException("CRIT_MALFUNCTION module code must be 0.."
                        + CRIT_WEAPON_ORDINAL_MAX + " or " + CRIT_MODULE_ENGINE_BASE + ".."
                        + (CRIT_MODULE_ENGINE_BASE + CRIT_ENGINE_ORDINAL_MAX) + "; got " + mountIndex);
            }
            if (aimFacingDeg != 0f && aimFacingDeg != 1f) {
                throw new IllegalArgumentException("CRIT_MALFUNCTION permanent flag must be 0 or 1; got " + aimFacingDeg);
            }
            if (flightBearingDeg != CRIT_BRANCH_DIRECT && flightBearingDeg != CRIT_BRANCH_DAMAGE
                    && flightBearingDeg != CRIT_BRANCH_LETHAL) {
                throw new IllegalArgumentException("CRIT_MALFUNCTION branch must be 0/1/2; got " + flightBearingDeg);
            }
            if (!Float.isFinite(birthSpeed) || birthSpeed < 0f || birthSpeed > CRIT_AMOUNT_CEIL) {
                throw new IllegalArgumentException("CRIT_MALFUNCTION amount must be finite 0.."
                        + CRIT_AMOUNT_CEIL + "; got " + birthSpeed);
            }
        } else {
            boolean windowPhase = kind == Kind.VENT_WINDOW
                    ? (phase == Phase.START || phase == Phase.END)
                    : (phase == Phase.ON || phase == Phase.OFF);
            if (!windowPhase) {
                throw new IllegalArgumentException(kind + " phase invalid: " + phase);
            }
            if (mountIndex != NO_MOUNT) {
                throw new IllegalArgumentException("window kinds carry mountIndex NO_MOUNT; got " + mountIndex);
            }
            // Schema v12 (mirror of the server ctor law): a
            // SYSTEM_WINDOW ON edge may carry the mote-attractor target — world x/y on the two
            // float slots (BOTH finite) + the locked ship's wire id (> 0) or TARGET_NONE on
            // targetWireId. Any other window edge keeps the NaN / TARGET_ABSENT law below.
            moteTargetForm = kind == Kind.SYSTEM_WINDOW
                    && (!Float.isNaN(aimFacingDeg) || !Float.isNaN(flightBearingDeg));
            if (moteTargetForm) {
                if (phase != Phase.ON) {
                    throw new IllegalArgumentException(
                            "SYSTEM_WINDOW mote-target form must be phase ON; got " + phase);
                }
                if (!Float.isFinite(aimFacingDeg) || !Float.isFinite(flightBearingDeg)) {
                    throw new IllegalArgumentException(
                            "SYSTEM_WINDOW mote-target x/y must both be finite; got "
                                    + aimFacingDeg + "/" + flightBearingDeg);
                }
                if (targetWireId != TARGET_NONE && targetWireId < 1L) {
                    throw new IllegalArgumentException(
                            "SYSTEM_WINDOW mote-target targetWireId must be > 0 or TARGET_NONE ("
                                    + TARGET_NONE + "); got " + targetWireId);
                }
            } else {
                if (!Float.isNaN(aimFacingDeg)) {
                    throw new IllegalArgumentException("window kinds carry aimFacingDeg NaN; got " + aimFacingDeg);
                }
                if (!Float.isNaN(flightBearingDeg)) {
                    throw new IllegalArgumentException("window kinds carry flightBearingDeg NaN; got " + flightBearingDeg);
                }
            }
        }
        // v6 slot is WEAPON_DISCHARGE-only (spec: the two REUSED slots never extend to it) —
        // v13: ARC_HIT re-purposes it as dmgArmor (validated in its own branch above).
        boolean damageSlotKind = kind == Kind.WEAPON_DISCHARGE || kind == Kind.ARC_HIT;
        // v14 CRIT_MALFUNCTION re-purposes the birthSpeed
        // slot ONLY (hull amount, validated above); its muzzle pair stays canonical NaN below.
        if (!damageSlotKind && kind != Kind.CRIT_MALFUNCTION && !Float.isNaN(birthSpeed)) {
            throw new IllegalArgumentException(kind + " carries birthSpeed NaN; got " + birthSpeed);
        }
        // v8 muzzle pair is WEAPON_DISCHARGE-only too (the birthSpeed law) — v13: ARC_HIT
        // re-purposes the pair as dmgHull/dmgShield (its shield law validated above).
        if (!damageSlotKind && (!Float.isNaN(muzzleX) || !Float.isNaN(muzzleY))) {
            throw new IllegalArgumentException(
                    kind + " carries muzzleX/muzzleY NaN; got " + muzzleX + "/" + muzzleY);
        }
        // v9 long slot is AUTOFIRE_TARGET-only (v12: + the SYSTEM_WINDOW mote-target form
        // validated above; v13: + ARC_HIT's victim id, validated > 0 above; v15: + MISSILE_END's
        // victim id, validated > 0 | ABSENT above; v16: + WEAPON_DISCHARGE's declared wait, validated
        // ABSENT | 0..255 above): every other kind carries the canonical
        // TARGET_ABSENT sentinel (the birthSpeed slot law extended to the long tail).
        if (kind != Kind.AUTOFIRE_TARGET && kind != Kind.ARC_HIT && kind != Kind.MISSILE_END
                && kind != Kind.WEAPON_DISCHARGE && !moteTargetForm && targetWireId != TARGET_ABSENT) {
            throw new IllegalArgumentException(
                    kind + " carries targetWireId TARGET_ABSENT (" + TARGET_ABSENT + "); got " + targetWireId);
        }
        this.simTick = simTick;
        this.entityId = entityId;
        this.kind = kind;
        this.phase = phase;
        this.mountIndex = mountIndex;
        this.aimFacingDeg = aimFacingDeg;
        this.flightBearingDeg = flightBearingDeg;
        this.birthSpeed = birthSpeed;
        this.muzzleX = muzzleX;
        this.muzzleY = muzzleY;
        this.targetWireId = targetWireId;
    }

    /**
     * A {@code WEAPON_DISCHARGE} edge (the server record's factory mirrored — the client
     * tree's test-authoring door; production events only ever arrive via
     * {@link ClientGameplayEdgeEventReader}). Aim/bearing/|v0| may be {@code NaN} = honest
     * absence; the muzzle pair stays atomic (ctor law); the v16 declared wait is ABSENT
     * ({@link #TARGET_ABSENT}) — a stamped discharge is built through the constructor.
     */
    public static ClientGameplayEdgeEvent weaponDischarge(
            long simTick, long entityId, int mountIndex, float aimFacingDeg, float flightBearingDeg,
            float birthSpeed, float muzzleX, float muzzleY) {
        return new ClientGameplayEdgeEvent(
                simTick, entityId, Kind.WEAPON_DISCHARGE, Phase.ONE_SHOT, mountIndex,
                aimFacingDeg, flightBearingDeg, birthSpeed, muzzleX, muzzleY, TARGET_ABSENT);
    }

    /** Muzzle-ABSENT {@link #weaponDischarge(long, long, int, float, float, float, float, float)}
     *  variant (v8 pair = canonical {@code NaN}/{@code NaN}) — the server factory's twin. */
    public static ClientGameplayEdgeEvent weaponDischarge(
            long simTick, long entityId, int mountIndex, float aimFacingDeg, float flightBearingDeg,
            float birthSpeed) {
        return weaponDischarge(simTick, entityId, mountIndex, aimFacingDeg, flightBearingDeg,
                birthSpeed, Float.NaN, Float.NaN);
    }

    /**
     * A {@code MISSILE_END} marker (v5; v15 victim form) — the
     * server factory's twin (same name/arg order), serving selftest authoring; {@code victimWireId}
     * = the ship the missile's last server-observed impact hit ({@code > 0}) or {@link #TARGET_ABSENT}.
     */
    public static ClientGameplayEdgeEvent missileEnd(long simTick, long missileEntityId, int endCauseCode,
            float endX, float endY, long victimWireId) {
        return new ClientGameplayEdgeEvent(
                simTick, missileEntityId, Kind.MISSILE_END, Phase.ONE_SHOT, endCauseCode,
                endX, endY, Float.NaN, Float.NaN, Float.NaN, victimWireId);
    }

    /**
     * An {@code AUTOFIRE_TARGET} pick marker (v9) — the server factory's
     * twin, serving selftest authoring; {@code targetWireId} = a real ship wire id ({@code > 0})
     * or {@link #TARGET_NONE} for a cleared pick.
     */
    public static ClientGameplayEdgeEvent autofireTarget(
            long simTick, long firerEntityId, int mountIndex, long targetWireId) {
        return new ClientGameplayEdgeEvent(
                simTick, firerEntityId, Kind.AUTOFIRE_TARGET, Phase.ONE_SHOT, mountIndex,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, targetWireId);
    }

    /**
     * A {@code PALADIN_DET} marker (v10) — the server factory's twin,
     * serving selftest authoring; {@code detX}/{@code detY} = the det birth position
     * (both REQUIRED finite), {@code mountIndex} = mount ordinal or {@link #NO_MOUNT}.
     */
    public static ClientGameplayEdgeEvent paladinDet(
            long simTick, long sourceEntityId, int mountIndex, float detX, float detY) {
        return new ClientGameplayEdgeEvent(
                simTick, sourceEntityId, Kind.PALADIN_DET, Phase.ONE_SHOT, mountIndex,
                detX, detY, Float.NaN, Float.NaN, Float.NaN, TARGET_ABSENT);
    }

    /**
     * An {@code ARC_HIT} marker (v13) — the server factory's twin (identical
     * name/arg order), serving selftest authoring; {@code targetWireId} = the VICTIM ship
     * ({@code > 0}), {@code arcFlavor} = an {@code ARC_FLAVOR_*} code, {@code hitX}/{@code hitY}
     * = the node world point (both REQUIRED finite), and the damage trio obeys the shield law
     * (hull hit: armor+hull finite, shield NaN; shield hit: shield finite, armor+hull NaN).
     */
    public static ClientGameplayEdgeEvent arcHit(long simTick, long sourceEntityId, long targetWireId,
            int arcFlavor, float hitX, float hitY, float dmgArmor, float dmgHull, float dmgShield) {
        return new ClientGameplayEdgeEvent(
                simTick, sourceEntityId, Kind.ARC_HIT, Phase.ONE_SHOT, arcFlavor,
                hitX, hitY, dmgArmor, dmgHull, dmgShield, targetWireId);
    }

    public long simTick() {
        return simTick;
    }

    public long entityId() {
        return entityId;
    }

    public Kind kind() {
        return kind;
    }

    public Phase phase() {
        return phase;
    }

    public int mountIndex() {
        return mountIndex;
    }

    /** The launch facing: a MISSILE discharge's projectile facing, else the mount angle; NaN = unbound. */
    public float aimFacingDeg() {
        return aimFacingDeg;
    }

    /**
     * The server's true initial flight bearing at discharge (v2 payload), or {@code NaN}
     * when unbound / legacy-v1 — consumers fall back to {@link #aimFacingDeg()}.
     */
    public float flightBearingDeg() {
        return flightBearingDeg;
    }

    /**
     * The server's birth-velocity magnitude {@code |v0|} at discharge (v6 payload, engine
     * units/sec), or {@code NaN} when unbound — consumers keep their local estimate
     * (fail-soft; the {@link #flightBearingDeg()} NaN law).
     */
    public float birthSpeed() {
        return birthSpeed;
    }

    /**
     * The server-true world X the projectile was born at (v8 payload), or {@code NaN}
     * when absent/legacy — consumers keep the local fire point (fail-soft).
     */
    public float muzzleX() {
        return muzzleX;
    }

    /**
     * The server-true world Y the projectile was born at (v8 payload), or {@code NaN}
     * when absent/legacy — consumers keep the local fire point (fail-soft).
     */
    public float muzzleY() {
        return muzzleY;
    }

    /** True for an edge that OPENS a window ({@code START}/{@code ON}); false otherwise. */
    public boolean opensWindow() {
        return phase == Phase.START || phase == Phase.ON;
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
     *  server-observed impact hit (the {@code targetWireId} slot), or {@link #TARGET_ABSENT} = the
     *  server saw no ship impact (or could not name the victim — fail-closed toward "nothing"). */
    public long endVictimWireId() {
        return targetWireId;
    }

    /** {@code MISSILE_END}: the end is of the impact class — {@link #endCauseIntactClass} on the code
     *  ({@code INTACT} or {@code SHIELD_ABSORBED}); the generic impact-FX / det consumers key on this. */
    public boolean endedIntact() {
        return endCauseIntactClass(mountIndex);
    }

    /** {@code MISSILE_END} (v15): the server's own shield verdict — the last impact was absorbed by
     *  a shield ({@link #END_CAUSE_SHIELD_ABSORBED}); vanilla's devour/disintegrator {@code onHit}
     *  no-ops on exactly this branch. */
    public boolean endedOnShield() {
        return mountIndex == END_CAUSE_SHIELD_ABSORBED;
    }

    /** {@code PALADIN_DET} payload: det birth X (the {@code aimFacingDeg} slot). */
    public float detX() {
        return aimFacingDeg;
    }

    /** {@code PALADIN_DET} payload: det birth Y (the {@code flightBearingDeg} slot). */
    public float detY() {
        return flightBearingDeg;
    }

    /** {@code ARC_HIT} payload (v13): the arc's node world X (the {@code aimFacingDeg} slot). */
    public float hitX() {
        return aimFacingDeg;
    }

    /** {@code ARC_HIT} payload (v13): the arc's node world Y (the {@code flightBearingDeg} slot). */
    public float hitY() {
        return flightBearingDeg;
    }

    /** {@code ARC_HIT} payload (v13): the {@code ARC_FLAVOR_*} code (the {@code mountIndex} slot). */
    public int arcFlavor() {
        return mountIndex;
    }

    /** {@code ARC_HIT} payload (v13): applied armor damage (the {@code birthSpeed} slot);
     *  NaN on a shield hit. */
    public float dmgArmor() {
        return birthSpeed;
    }

    /** {@code ARC_HIT} payload (v13): applied hull damage (the {@code muzzleX} slot);
     *  NaN on a shield hit. */
    public float dmgHull() {
        return muzzleX;
    }

    /** {@code ARC_HIT} payload (v13): applied shield damage (the {@code muzzleY} slot);
     *  NaN on a hull hit. */
    public float dmgShield() {
        return muzzleY;
    }

    /** {@code ARC_HIT} payload (v13): whether the arc landed on a raised shield
     *  ({@code ≡ !isNaN(dmgShield())}, the ctor shield law). */
    public boolean isShieldHit() {
        return !Float.isNaN(muzzleY);
    }

    // The CRIT_MALFUNCTION (v14) accessors.
    /** {@code CRIT_MALFUNCTION} payload (v14): whether the module code names an ENGINE
     *  ({@code >= CRIT_MODULE_ENGINE_BASE}) rather than a weapon. */
    public boolean critModuleIsEngine() {
        return mountIndex >= CRIT_MODULE_ENGINE_BASE;
    }

    /** {@code CRIT_MALFUNCTION} payload (v14): the module ordinal — weapon index into
     *  {@code getAllWeapons()} or engine index into {@code getShipEngines()}. */
    public int critModuleOrdinal() {
        return mountIndex >= CRIT_MODULE_ENGINE_BASE ? mountIndex - CRIT_MODULE_ENGINE_BASE : mountIndex;
    }

    /** {@code CRIT_MALFUNCTION} payload (v14): the permanent flag (the {@code aimFacingDeg} slot, 0f/1f). */
    public boolean critPermanent() {
        return aimFacingDeg == 1f;
    }

    /** {@code CRIT_MALFUNCTION} payload (v14): the {@code CRIT_BRANCH_*} code (the {@code flightBearingDeg} slot). */
    public int critBranch() {
        return (int) flightBearingDeg;
    }

    /** {@code CRIT_MALFUNCTION} payload (v14): the hull amount (the {@code birthSpeed} slot). */
    public float critAmount() {
        return birthSpeed;
    }

    /**
     * {@code WEAPON_DISCHARGE} payload (v16): the server's
     * declared wait (the {@code targetWireId} slot) = raw ticks from the consumed declaration's arrival
     * to this discharge, {@code 0..}{@link #DECLARED_WAIT_MAX_TICKS} (0 = the gun was ready), or
     * {@code -1} = absent (no stamp); {@code -1} on every other kind, whose slot means something else.
     */
    public int declaredWaitTicks() {
        return kind == Kind.WEAPON_DISCHARGE ? (int) targetWireId : -1;
    }

    /**
     * The raw long slot. {@code AUTOFIRE_TARGET} payload (v9): the wire id of the SHIP the server's
     * autofire plugin now tracks on {@code (entityId, mountIndex)}, or {@link #TARGET_NONE} for a
     * cleared pick; {@code ARC_HIT} / {@code MISSILE_END} / the {@code SYSTEM_WINDOW} mote form carry
     * a ship id (or a sentinel) here and {@code WEAPON_DISCHARGE} its declared wait ({@link #declaredWaitTicks()}, v16),
     * so a reader switches on {@link #kind()} first; every other kind carries {@link #TARGET_ABSENT}.
     */
    public long targetWireId() {
        return targetWireId;
    }

    @Override
    public String toString() {
        return "ClientGameplayEdgeEvent{tick=" + simTick + ", entity=" + entityId
                + ", " + kind + "/" + phase + ", mount=" + mountIndex
                + ", aim=" + aimFacingDeg + ", bearing=" + flightBearingDeg
                + ", birthSpeed=" + birthSpeed
                + ", muzzle=" + muzzleX + "/" + muzzleY
                + ", targetWireId=" + targetWireId + "}";
    }
}
