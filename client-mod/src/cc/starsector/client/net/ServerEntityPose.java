package cc.starsector.client.net;

/**
 * One ship entity's authoritative pose, decoded from the server's
 * {@code AWSS} world-snapshot wire frame.
 *
 * <p>This is the client-side projection of the server's
 * {@code AuthoritativeEntitySnapshot}: the mod only needs the fields it writes
 * onto a live combat entity each frame (position, velocity, facing) plus the
 * identity fields used to map a server entity to a local {@code ShipAPI}, and the
 * authoritative {@code hitpoints} (hull) — the one durability scalar the lifecycle
 * tracker ({@link RosterLifecycleTracker}) needs to classify a vanished entity's
 * despawn as a combat {@code DESTROYED} vs a non-lethal {@code DEPARTED} (the same
 * hull-driven kill criterion the server's {@code EntityDespawnEventDetector} uses) —
 * plus the combat-side {@code fleetSide} (friend/foe) the server folds onto schema-v5
 * frames, which is what lets the mod colour a puppet by side.
 * The flux, engine-state, and weapon-mount tiers are attached too ({@link #fluxLevel()},
 * {@link #engineControllerState()}, {@link #weaponMounts()}); the armor
 * fraction rides the AWSD v10 keyframe tier ({@link #armorFrac()}), while the
 * AWSS armor scalars stay decoded past (skipped) by {@link AwssSnapshotReader} —
 * discarded-by-design in v1 (the live lane feeds from AWSD).
 *
 * <p>Pure Java with no Starsector-API dependency, so it (and the rest of the
 * {@code net} package) can be cross-checked against the real server codec in a
 * game-free verifier.
 */
public final class ServerEntityPose {

    /**
     * Sentinel {@link #fleetSide()} for a pose decoded from a frame that does not carry the
     * schema-v5 combat-side tier (a pre-v5 server, or a v5 frame whose allegiance section had
     * no record for this entity). Distinct from the real {@code FleetSide} ordinals
     * ({@code 0}/{@code 1} fighting sides, {@code 100} neutral), so a consumer can tell
     * "side unknown" apart from a genuine side.
     */
    public static final int UNKNOWN_FLEET_SIDE = -1;

    /**
     * Sentinel {@link #fluxLevel()} for a pose decoded from a frame that does not carry the per-ship
     * flux tier (a pre-v3 server frame, or a frame whose flux section had no record for this entity).
     * Negative, so a consumer can tell "flux unknown" apart from a genuine {@code [0,1]} level via
     * {@link #hasFlux()}. The predict feed now folds flux onto the wire so the
     * client can show the server-authoritative flux beside the local-engine flux (the debug
     * instrument: track flux just like velocity for client and server).
     */
    public static final float UNKNOWN_FLUX = -1f;

    /**
     * Sentinel {@link #fluxFlags()} for a pose decoded from a frame that does not carry the
     * per-ship flux tier for this entity. The real value is the server's packed
     * {@code ShipFluxSnapshot} status int — {@code FLAG_OVERLOADED=1}, {@code FLAG_VENTING=2},
     * {@code FLAG_HAS_SHIELD=4}, {@code FLAG_SHIELD_ON=8} (the AWSD {@code fluxFlags} u8 is the
     * same packing) — always {@code >= 0}, so a consumer gates via {@link #hasFluxFlags()}.
     * Minted on both decode lanes for meters/future flag-apply lanes; nothing
     * force-applies the flags in this rung.
     */
    public static final int UNKNOWN_FLUX_FLAGS = -1;

    /**
     * Sentinel {@link #engineControllerState()} for a pose decoded from a frame that does not carry
     * the schema-v6 engine-state tier (a pre-v6 server frame, or a v6 frame with no record for this
     * entity). The real controller-state codes are {@code 0} (ACTIVE), {@code 1} (FLAMING_OUT),
     * {@code 2} (FLAMED_OUT) — the server's {@code ShipEngineStateSnapshot} enum codes.
     * When this sentinel is set the two engine masks are meaningless (left {@code 0}).
     */
    public static final int UNKNOWN_ENGINE_STATE = -1;

    /**
     * Sentinel {@link #maneuverCommandMask()} for a pose decoded from a frame that does not carry
     * the schema-v7 maneuver-command tier (a pre-v7 server frame, or a v7 frame with no record for
     * this entity). The real mask is the server controller's 7 maneuver booleans in the canonical
     * {@code WIRE_BIT_*} order (bit 0 ACCELERATE .. bit 6 STRAFE_RIGHT, ≤ {@code 0x7F} fail-closed
     * at the codec), so any negative value is distinguishable via {@link #hasManeuverCommandMask()}.
     * The foe engine-plume lane — the carried mask is what lets the replica's own engine
     * controller flare plumes through the real command path.
     */
    public static final int UNKNOWN_MANEUVER_MASK = -1;

    /**
     * Sentinel {@link #systemStateCode()} for a pose decoded from a frame that does not carry the
     * schema-v8 ship-system state tier (a pre-v8 server frame, or a v8 frame with no record for
     * this entity — e.g. a ship with no system). The real codes are the server's PROJECT-OWNED
     * {@code ShipSystemStateSnapshot} mapping ({@code 0} IDLE, {@code 1} CHARGEUP, {@code 2}
     * ACTIVE, {@code 3} CHARGEDOWN, {@code 4} COOLDOWN — never an engine ordinal). When this
     * sentinel is set the three companion scalars are meaningless (effect/cooldown left {@code 0},
     * ammo left {@code -1}); gate on {@link #hasSystemState()} first — note ammo {@code -1} is
     * a VALID "no-charge system" value when the tier IS present. Schema v8.
     */
    public static final int UNKNOWN_SYSTEM_STATE = -1;

    /**
     * Sentinel {@link #cloakStateCode()} for a pose decoded from a frame that does not carry the
     * schema-v10 PHASE-cloak state tier (a pre-v10 server frame, or a v10 frame with no record
     * for this entity — e.g. a cloakless hull). The tier reuses the v8 SYSTEM_STATE record
     * layout and code mapping verbatim ({@code 0} IDLE, {@code 1} CHARGEUP, {@code 2} ACTIVE,
     * {@code 3} CHARGEDOWN, {@code 4} COOLDOWN — the server's project-owned
     * {@code ShipPhaseCloakStateSnapshot}), keyed on the ship's {@code getPhaseCloak()} slot —
     * no cloak content id travels; the client re-derives the cloak from its own hull. When this
     * sentinel is set the three companion scalars are meaningless (effect/cooldown left
     * {@code 0}, ammo left {@code -1}); gate on {@link #hasPhaseCloakState()} first — ammo
     * {@code -1} is a VALID "no-charge cloak" value when the tier IS present. Schema
     * v10.
     */
    public static final int UNKNOWN_PHASE_CLOAK_STATE = -1;

    /**
     * Sentinel {@link #cloakTierTick()}: the merger has never folded a keyframe carrying the
     * phase-cloak tier for this entity (= {@code ChunkMergerCore.NEVER_ASSERTED}). Under remedy A
     * the v10 cloak block rides ONLY the entity's keyframe (deltas hold-last),
     * so a consumer that must act on a FRESH tier value — never on a held one — compares this
     * tick against its own memory; a change = a new keyframe landed.
     */
    public static final long UNKNOWN_CLOAK_TIER_TICK = Long.MIN_VALUE;

    /**
     * Sentinel {@link #shieldFacingU8()} for a pose decoded from a frame that does not carry the
     * quantized shield-facing byte for this entity (a pre-v11 AWSS frame — including a v11-capable
     * server whose frame legally stamped ≤v10 because every shielded ship's facing quantized to 0;
     * AWSD v4 always carries it). The real value is the server's {@code ShipFluxSnapshot}
     * {@code shieldFacingU8}: absolute shield facing quantized at {@link #SHIELD_FACING_STEP_DEG}
     * steps ({@code 0..255}, shieldless ships mint {@code 0}) — quantized ONCE at the server mint,
     * carried verbatim on both wires (the no-drift law). Valid only when
     * {@link #hasFluxFlags()} and {@code FLAG_HAS_SHIELD} is set — gate there, then
     * {@link #hasShieldFacing()}. The apply lane treats UNKNOWN as KEEP-LAST-FORCED (sticky):
     * absence means "no new authoritative facing", never "facing zero".
     */
    public static final int UNKNOWN_SHIELD_FACING = -1;

    /**
     * Degrees per {@link #shieldFacingU8()} quantize step ({@code 360/256} — the server's
     * {@code ShipFluxSnapshot.SHIELD_FACING_STEP_DEG} mirrored). Client dequant at apply =
     * {@code u8 * SHIELD_FACING_STEP_DEG}.
     */
    public static final float SHIELD_FACING_STEP_DEG = 360f / 256f;

    /**
     * {@link #fluxFlags()} bit: the ship is PHASED right now (the server's
     * {@code ShipFluxSnapshot.FLAG_PHASED} — the per-frame steady collision
     * truth beside the v10 cloak state-machine tier). Sits above the original four flags
     * ({@code FLAG_OVERLOADED=1}, {@code FLAG_VENTING=2}, {@code FLAG_HAS_SHIELD=4},
     * {@code FLAG_SHIELD_ON=8}); the wire int always crossed raw, so pre-v10 frames simply
     * never set it. Consumed by the {@code cc.predict.foeCloak} lane via {@link #isPhasedBit()}.
     */
    public static final int FLAG_PHASED = 1 << 4;

    /**
     * {@link #fluxFlags()} bit — the server has its god-mode
     * damage-taken mults APPLIED on this hull (twin of the server's {@code ShipFluxSnapshot.FLAG_GOD};
     * parity pinned by {@code GodMirrorLaneSmoke} row 8). Read via {@link #isGodBit()}; the
     * {@code GodMirrorLane} mirrors it onto the local replica.
     */
    public static final int FLAG_GOD = 1 << 5;

    /**
     * {@link #fluxFlags()} bit — the server's TRAVEL DRIVE
     * blocks firing on this seat hull on the publishing frame (the fly-in burn, a retreat burn;
     * twin of the server's {@code ShipFluxSnapshot.FLAG_DRIVE_LOCK}, parity pinned by
     * {@code DriveLockLaneSmoke} row 1). Read via {@link #isDriveLockBit()}; the
     * {@code DriveLockLane} refuses the own trigger while a fresh own pose carries it.
     */
    public static final int FLAG_DRIVE_LOCK = 1 << 6;

    /**
     * Sentinel {@link #cr()} for a pose decoded from a frame that does not carry the
     * schema-v14 combat-readiness (CR) clock tier for this entity (a pre-v14 server frame,
     * or a v14 frame with no record for this ship — the tier is SPARSE per-entity like the
     * cloak tier: absence means "unchanged", never "CR zero"). The real value is the
     * server's {@code ShipCrSnapshot.cr()} — the engine's {@code getCurrentCR()} in
     * {@code [0,1]} (lenient upper bound), always {@code >= 0}, so {@link #hasCr()} gates
     * on the sign. When this sentinel is set the two companion clocks are meaningless
     * ({@link #crDepSec()} / {@link #crPeakSec()} left {@code 0}); gate on {@link #hasCr()}
     * first. Schema v14.
     */
    public static final float UNKNOWN_CR = -1f;

    /**
     * Sentinel {@link #armorFrac()} for a pose decoded from a frame that does not carry the
     * AWSD schema-v10 armor keyframe tier (an AWSS-decoded pose, whose armor
     * scalars are discarded-by-design in v1). The real value is the server's
     * whole-grid armor fraction {@code armorTotal / (cellCount × maxInCell)} quantized u8/255f,
     * legitimately {@code 0} (armor fully ablated — NO min-1 clamp on this family), so
     * {@link #hasArmor()} gates on the SIGN, never on zero.
     */
    public static final float UNKNOWN_ARMOR = -1f;

    /**
     * Sentinel {@link #fighterReplacementPct()} for a pose whose frame carried no fighter-rate
     * byte (an AWSS-decoded pose, or the AWSD v12 0xFF absent sentinel - a CR-less record).
     * Real values are the server's panel-arithmetic percent 0..100.
     */
    public static final int UNKNOWN_FIGHTER_REPLACEMENT_PCT = -1;

    /**
     * Sentinel {@link #maxHp()} for a pose whose frame carried no max-hull field (an AWSS-decoded
     * pose). The real value is the AWSD keyframe's {@code maxHp} i32 = the server
     * ENGINE's {@code getMaxHitpoints()} at encode ({@code round(hitpoints / hullLevel)}), which is
     * NOT a variant constant: vanilla scripts grow it in place ({@code ConstructionSwarmSystemScript},
     * {@code ThreatShipReclamationScript}). Minted for the
     * {@code FoeHullReconcileLane} max-hull mirror; gate on {@link #hasMaxHp()}.
     */
    public static final float UNKNOWN_MAX_HP = -1f;

    private final long entityId;
    private final int contentId;
    private final int ownerConnectionId;
    private final float locX;
    private final float locY;
    private final float velX;
    private final float velY;
    private final float facing;
    private final float angularVelocity;
    private final float hitpoints;
    private final float maxHp;
    private final int fleetSide;
    private final float currFlux;
    private final float maxFlux;
    private final float fluxLevel;
    private final float hardFlux;
    private final int fluxFlags;
    private final int engineDisabledMask;
    private final int enginePermDisabledMask;
    private final int engineControllerState;
    private final int maneuverCommandMask;
    private final java.util.List<ServerWeaponMountState> weaponMounts;
    private final int systemStateCode;
    private final float systemEffectLevel;
    private final float systemCooldownRemaining;
    private final int systemAmmo;
    private final int cloakStateCode;
    private final float cloakEffectLevel;
    private final float cloakCooldownRemaining;
    private final int cloakAmmo;
    private final long cloakTierTick;
    private final int shieldFacingU8;
    private final float cr;
    private final float crDepSec;
    private final float crPeakSec;
    private final float armorFrac;
    private final int fighterReplacementPct;

    /**
     * Back-compat constructor (pre-v5 callers / kinematics-only poses): the combat-side
     * {@code fleetSide} defaults to {@link #UNKNOWN_FLEET_SIDE}. The schema-v5 decode path
     * attaches a real side with {@link #withFleetSide(int)} once it has read the allegiance
     * section.
     */
    public ServerEntityPose(
            long entityId,
            int contentId,
            int ownerConnectionId,
            float locX,
            float locY,
            float velX,
            float velY,
            float facing,
            float angularVelocity,
            float hitpoints) {
        this(entityId, contentId, ownerConnectionId, locX, locY, velX, velY,
                facing, angularVelocity, hitpoints, UNKNOWN_FLEET_SIDE);
    }

    /**
     * Kinematics + combat-side constructor: every other critical-state tier stays at its
     * tier-absent sentinel (the {@link #builder()} defaults). The decode paths attach real
     * tiers with the {@code with*} copies as the trailing sections are read.
     */
    public ServerEntityPose(
            long entityId,
            int contentId,
            int ownerConnectionId,
            float locX,
            float locY,
            float velX,
            float velY,
            float facing,
            float angularVelocity,
            float hitpoints,
            int fleetSide) {
        this(builder()
                .entityId(entityId)
                .contentId(contentId)
                .ownerConnectionId(ownerConnectionId)
                .locX(locX)
                .locY(locY)
                .velX(velX)
                .velY(velY)
                .facing(facing)
                .angularVelocity(angularVelocity)
                .hitpoints(hitpoints)
                .fleetSide(fleetSide));
    }

    /** Canonical all-fields assignment — reachable only through {@link Builder#build()}. */
    private ServerEntityPose(Builder b) {
        this.entityId = b.entityId;
        this.contentId = b.contentId;
        this.ownerConnectionId = b.ownerConnectionId;
        this.locX = b.locX;
        this.locY = b.locY;
        this.velX = b.velX;
        this.velY = b.velY;
        this.facing = b.facing;
        this.angularVelocity = b.angularVelocity;
        this.hitpoints = b.hitpoints;
        this.maxHp = b.maxHp;
        this.fleetSide = b.fleetSide;
        this.currFlux = b.currFlux;
        this.maxFlux = b.maxFlux;
        this.fluxLevel = b.fluxLevel;
        this.hardFlux = b.hardFlux;
        this.fluxFlags = b.fluxFlags;
        this.engineDisabledMask = b.engineDisabledMask;
        this.enginePermDisabledMask = b.enginePermDisabledMask;
        this.engineControllerState = b.engineControllerState;
        this.maneuverCommandMask = b.maneuverCommandMask;
        this.weaponMounts = b.weaponMounts;
        this.systemStateCode = b.systemStateCode;
        this.systemEffectLevel = b.systemEffectLevel;
        this.systemCooldownRemaining = b.systemCooldownRemaining;
        this.systemAmmo = b.systemAmmo;
        this.cloakStateCode = b.cloakStateCode;
        this.cloakEffectLevel = b.cloakEffectLevel;
        this.cloakCooldownRemaining = b.cloakCooldownRemaining;
        this.cloakAmmo = b.cloakAmmo;
        this.cloakTierTick = b.cloakTierTick;
        this.shieldFacingU8 = b.shieldFacingU8;
        this.cr = b.cr;
        this.crDepSec = b.crDepSec;
        this.crPeakSec = b.crPeakSec;
        this.armorFrac = b.armorFrac;
        this.fighterReplacementPct = b.fighterReplacementPct;
    }

    /**
     * Start a builder with every critical-state tier at its tier-absent sentinel default —
     * combat side {@link #UNKNOWN_FLEET_SIDE} (schema v5); the flux trio + hard flux
     * {@link #UNKNOWN_FLUX} with flags {@link #UNKNOWN_FLUX_FLAGS} (v3);
     * engine masks {@code 0} with controller state {@link #UNKNOWN_ENGINE_STATE} (v6);
     * empty weapon mounts (v4); maneuver mask
     * {@link #UNKNOWN_MANEUVER_MASK} (v7); system state
     * {@link #UNKNOWN_SYSTEM_STATE} (v8) and phase-cloak state
     * {@link #UNKNOWN_PHASE_CLOAK_STATE} (v10), each with effect/cooldown {@code 0}
     * and ammo {@code -1}; the CR clock {@link #UNKNOWN_CR} with deployed/peak {@code 0}
     * (v14); the armor fraction {@link #UNKNOWN_ARMOR} (AWSD v10)
     * — exactly the defaults the pre-extraction telescoping constructor chain threaded.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Field-named builder (it retires the constructor tax — the
     * {@code AuthoritativeWorldSnapshot} builder idiom on the client side): the one
     * all-fields construction path. Every tier-local copy in this file ({@code with*}) and
     * every full-tier mint ({@code AwsdSnapshotReader.resolvePose},
     * {@code SnapshotChunkMerger.synthesize}) routes through this instead of a positional
     * canonical constructor, so the NEXT pose-field tier costs one field + one builder line —
     * a positional full-args call elsewhere would instead silently rebind to a shorter
     * back-compat constructor and DROP the new tier (the ctor-fan-out hazard). The two
     * surviving kinematics constructors are deliberate: the decode paths mint a
     * kinematics-only pose first and attach tiers via {@code with*} as sections arrive.
     */
    public static final class Builder {
        private long entityId;
        private int contentId;
        private int ownerConnectionId;
        private float locX;
        private float locY;
        private float velX;
        private float velY;
        private float facing;
        private float angularVelocity;
        private float hitpoints;
        private float maxHp = UNKNOWN_MAX_HP;
        private int fleetSide = UNKNOWN_FLEET_SIDE;
        private float currFlux = UNKNOWN_FLUX;
        private float maxFlux = UNKNOWN_FLUX;
        private float fluxLevel = UNKNOWN_FLUX;
        private float hardFlux = UNKNOWN_FLUX;
        private int fluxFlags = UNKNOWN_FLUX_FLAGS;
        private int engineDisabledMask;
        private int enginePermDisabledMask;
        private int engineControllerState = UNKNOWN_ENGINE_STATE;
        private int maneuverCommandMask = UNKNOWN_MANEUVER_MASK;
        private java.util.List<ServerWeaponMountState> weaponMounts =
                java.util.Collections.emptyList();
        private int systemStateCode = UNKNOWN_SYSTEM_STATE;
        private float systemEffectLevel;
        private float systemCooldownRemaining;
        private int systemAmmo = -1;
        private int cloakStateCode = UNKNOWN_PHASE_CLOAK_STATE;
        private float cloakEffectLevel;
        private float cloakCooldownRemaining;
        private int cloakAmmo = -1;
        private long cloakTierTick = UNKNOWN_CLOAK_TIER_TICK;
        private int shieldFacingU8 = UNKNOWN_SHIELD_FACING;
        private float cr = UNKNOWN_CR;
        private float crDepSec;
        private float crPeakSec;
        private float armorFrac = UNKNOWN_ARMOR;
        private int fighterReplacementPct = UNKNOWN_FIGHTER_REPLACEMENT_PCT;

        public Builder entityId(long entityId) {
            this.entityId = entityId;
            return this;
        }

        public Builder contentId(int contentId) {
            this.contentId = contentId;
            return this;
        }

        public Builder ownerConnectionId(int ownerConnectionId) {
            this.ownerConnectionId = ownerConnectionId;
            return this;
        }

        public Builder locX(float locX) {
            this.locX = locX;
            return this;
        }

        public Builder locY(float locY) {
            this.locY = locY;
            return this;
        }

        public Builder velX(float velX) {
            this.velX = velX;
            return this;
        }

        public Builder velY(float velY) {
            this.velY = velY;
            return this;
        }

        public Builder facing(float facing) {
            this.facing = facing;
            return this;
        }

        public Builder angularVelocity(float angularVelocity) {
            this.angularVelocity = angularVelocity;
            return this;
        }

        public Builder hitpoints(float hitpoints) {
            this.hitpoints = hitpoints;
            return this;
        }

        /** The AWSD keyframe's server-engine max hull (see {@link #UNKNOWN_MAX_HP}). */
        public Builder maxHp(float maxHp) {
            this.maxHp = maxHp;
            return this;
        }

        public Builder fleetSide(int fleetSide) {
            this.fleetSide = fleetSide;
            return this;
        }

        public Builder currFlux(float currFlux) {
            this.currFlux = currFlux;
            return this;
        }

        public Builder maxFlux(float maxFlux) {
            this.maxFlux = maxFlux;
            return this;
        }

        public Builder fluxLevel(float fluxLevel) {
            this.fluxLevel = fluxLevel;
            return this;
        }

        public Builder hardFlux(float hardFlux) {
            this.hardFlux = hardFlux;
            return this;
        }

        public Builder fluxFlags(int fluxFlags) {
            this.fluxFlags = fluxFlags;
            return this;
        }

        public Builder engineDisabledMask(int engineDisabledMask) {
            this.engineDisabledMask = engineDisabledMask;
            return this;
        }

        public Builder enginePermDisabledMask(int enginePermDisabledMask) {
            this.enginePermDisabledMask = enginePermDisabledMask;
            return this;
        }

        public Builder engineControllerState(int engineControllerState) {
            this.engineControllerState = engineControllerState;
            return this;
        }

        public Builder maneuverCommandMask(int maneuverCommandMask) {
            this.maneuverCommandMask = maneuverCommandMask;
            return this;
        }

        public Builder weaponMounts(java.util.List<ServerWeaponMountState> weaponMounts) {
            this.weaponMounts = weaponMounts;
            return this;
        }

        public Builder systemStateCode(int systemStateCode) {
            this.systemStateCode = systemStateCode;
            return this;
        }

        public Builder systemEffectLevel(float systemEffectLevel) {
            this.systemEffectLevel = systemEffectLevel;
            return this;
        }

        public Builder systemCooldownRemaining(float systemCooldownRemaining) {
            this.systemCooldownRemaining = systemCooldownRemaining;
            return this;
        }

        public Builder systemAmmo(int systemAmmo) {
            this.systemAmmo = systemAmmo;
            return this;
        }

        public Builder cloakStateCode(int cloakStateCode) {
            this.cloakStateCode = cloakStateCode;
            return this;
        }

        public Builder cloakEffectLevel(float cloakEffectLevel) {
            this.cloakEffectLevel = cloakEffectLevel;
            return this;
        }

        public Builder cloakCooldownRemaining(float cloakCooldownRemaining) {
            this.cloakCooldownRemaining = cloakCooldownRemaining;
            return this;
        }

        public Builder cloakAmmo(int cloakAmmo) {
            this.cloakAmmo = cloakAmmo;
            return this;
        }

        /** The merger's cloak-tier assert tick (see {@link #cloakTierTick()}); {@link #UNKNOWN_CLOAK_TIER_TICK} = never. */
        public Builder cloakTierTick(long cloakTierTick) {
            this.cloakTierTick = cloakTierTick;
            return this;
        }

        public Builder shieldFacingU8(int shieldFacingU8) {
            this.shieldFacingU8 = shieldFacingU8;
            return this;
        }

        public Builder cr(float cr) {
            this.cr = cr;
            return this;
        }

        public Builder crDepSec(float crDepSec) {
            this.crDepSec = crDepSec;
            return this;
        }

        public Builder crPeakSec(float crPeakSec) {
            this.crPeakSec = crPeakSec;
            return this;
        }

        public Builder armorFrac(float armorFrac) {
            this.armorFrac = armorFrac;
            return this;
        }

        public Builder fighterReplacementPct(int fighterReplacementPct) {
            this.fighterReplacementPct = fighterReplacementPct;
            return this;
        }

        /** The one all-fields construction site (assignment lives in the private constructor). */
        public ServerEntityPose build() {
            return new ServerEntityPose(this);
        }
    }

    /** Builder pre-loaded with every field of this pose (the tier-local copy idiom). */
    public Builder toBuilder() {
        return builder()
                .entityId(entityId)
                .contentId(contentId)
                .ownerConnectionId(ownerConnectionId)
                .locX(locX)
                .locY(locY)
                .velX(velX)
                .velY(velY)
                .facing(facing)
                .angularVelocity(angularVelocity)
                .hitpoints(hitpoints)
                // Seen as predict_maxhull_mirrors=0 on a 4000-max carrier: the maxHp
                // field was minted at resolvePose but NOT copied here, so every derived pose
                // (withFleetSide / merger / chunk copies) lost it and hasMaxHp() read false in the
                // lanes. ServerEntityPoseSelftest round-trips EVERY field through this builder.
                .maxHp(maxHp)
                .fleetSide(fleetSide)
                .currFlux(currFlux)
                .maxFlux(maxFlux)
                .fluxLevel(fluxLevel)
                .hardFlux(hardFlux)
                .fluxFlags(fluxFlags)
                .engineDisabledMask(engineDisabledMask)
                .enginePermDisabledMask(enginePermDisabledMask)
                .engineControllerState(engineControllerState)
                .maneuverCommandMask(maneuverCommandMask)
                .weaponMounts(weaponMounts)
                .systemStateCode(systemStateCode)
                .systemEffectLevel(systemEffectLevel)
                .systemCooldownRemaining(systemCooldownRemaining)
                .systemAmmo(systemAmmo)
                .cloakStateCode(cloakStateCode)
                .cloakEffectLevel(cloakEffectLevel)
                .cloakCooldownRemaining(cloakCooldownRemaining)
                .cloakAmmo(cloakAmmo)
                .cloakTierTick(cloakTierTick)
                .shieldFacingU8(shieldFacingU8)
                .cr(cr)
                .crDepSec(crDepSec)
                .crPeakSec(crPeakSec)
                .armorFrac(armorFrac)
                .fighterReplacementPct(fighterReplacementPct);
    }

    public long entityId() {
        return entityId;
    }

    public int contentId() {
        return contentId;
    }

    public int ownerConnectionId() {
        return ownerConnectionId;
    }

    public float locX() {
        return locX;
    }

    public float locY() {
        return locY;
    }

    public float velX() {
        return velX;
    }

    public float velY() {
        return velY;
    }

    /** Facing, in degrees (Starsector convention), matching the server's {@code facing} field. */
    public float facing() {
        return facing;
    }

    public float angularVelocity() {
        return angularVelocity;
    }

    /**
     * Authoritative hull this tick (the server's {@code AuthoritativeEntitySnapshot.hitpoints()}).
     * Used only by {@link RosterLifecycleTracker} to classify a despawn reason from the
     * entity's last-seen hull; the kinematic puppet path ignores it.
     */
    public float hitpoints() {
        return hitpoints;
    }

    /**
     * The server ENGINE's max hull at encode (the AWSD keyframe {@code maxHp} i32, hold-last on
     * deltas — a change forces a keyframe); meaningless without {@link #hasMaxHp()}. Consumed by the
     * {@code FoeHullReconcileLane} max-hull mirror (script-grown / stat-modded max hulls the
     * variant-minted replica never had).
     */
    public float maxHp() {
        return maxHp;
    }

    /** True when this pose carries the AWSD keyframe max hull (see {@link #maxHp()}). */
    public boolean hasMaxHp() {
        return maxHp > 0f;
    }

    /**
     * This ship's combat {@code FleetSide} ordinal (the server's
     * {@code ShipAllegianceSnapshot.fleetSide()}): {@code 0}/{@code 1} are the two fighting
     * sides, {@code 100} is neutral, and {@link #UNKNOWN_FLEET_SIDE} means the frame carried no
     * allegiance for this entity (pre-v5 server). The mod uses it to colour a puppet friend/foe;
     * the kinematic puppet path ignores it.
     */
    public int fleetSide() {
        return fleetSide;
    }

    /**
     * Authoritative current flux this tick (the server's {@code ShipFluxSnapshot.currFlux()}), or
     * {@link #UNKNOWN_FLUX} when the frame carried no flux tier for this entity.
     */
    public float currFlux() {
        return currFlux;
    }

    /** Authoritative max flux, or {@link #UNKNOWN_FLUX} when absent (see {@link #currFlux()}). */
    public float maxFlux() {
        return maxFlux;
    }

    /**
     * Authoritative flux level in {@code [0,1]} (the server's {@code ShipFluxSnapshot.fluxLevel()}),
     * or {@link #UNKNOWN_FLUX} when the frame carried no flux tier for this entity. This is the
     * scalar that drives the zero-flux speed boost, so it is the one the speed-divergence
     * debug compares between the client's local engine and the authoritative server.
     */
    public float fluxLevel() {
        return fluxLevel;
    }

    /** True when this pose carries an authoritative flux reading (a schema-v3+ frame). */
    public boolean hasFlux() {
        return fluxLevel >= 0f;
    }

    /**
     * Authoritative HARD flux this tick (the server's {@code ShipFluxSnapshot.hardFlux()};
     * on the AWSD lane reconstructed server-exact as {@code (hardFluxFrac/255)*maxFlux}), or
     * {@link #UNKNOWN_FLUX} when the frame carried no flux tier for this entity.
     * the vanilla target-info bar draws its 3px hard-flux tick off the tracker's min-flux,
     * so the foe-flux apply lane sets this beside {@link #currFlux()}.
     */
    public float hardFlux() {
        return hardFlux;
    }

    /** True when this pose carries an authoritative hard-flux reading (see {@link #hardFlux()}). */
    public boolean hasHardFlux() {
        return hardFlux >= 0f;
    }

    /**
     * The packed flux status flags exactly as carried on the wire ({@code FLAG_OVERLOADED=1},
     * {@code FLAG_VENTING=2}, {@code FLAG_HAS_SHIELD=4}, {@code FLAG_SHIELD_ON=8},
     * {@link #FLAG_PHASED}{@code =16}, {@link #FLAG_GOD}{@code =32}, {@link #FLAG_DRIVE_LOCK}{@code =64}
     * — see {@link #UNKNOWN_FLUX_FLAGS}), or
     * {@link #UNKNOWN_FLUX_FLAGS} when the frame carried no flux tier for this entity. Carried
     * for meters/future vent-overload FX lanes; nothing force-applies these in the rung that
     * minted them.
     */
    public int fluxFlags() {
        return fluxFlags;
    }

    /** True when this pose carries the authoritative flux status flags (see {@link #fluxFlags()}). */
    public boolean hasFluxFlags() {
        return fluxFlags >= 0;
    }

    /**
     * True when the carried flux flags say the ship is PHASED this frame
     * ({@link #FLAG_PHASED} — the per-frame collision truth the
     * {@code cc.predict.foeCloak} lane meters against the local replica). {@code false} both
     * for an unphased ship and for a pose with no flux tier — gate on {@link #hasFluxFlags()}
     * when the distinction matters.
     */
    public boolean isPhasedBit() {
        return fluxFlags >= 0 && (fluxFlags & FLAG_PHASED) != 0;
    }

    /**
     * True when the carried flux flags say the server's god mode is applied on this hull
     * ({@link #FLAG_GOD}); {@code false} for a non-god hull AND for a pose with no flux tier —
     * the {@code GodMirrorLane} gates on {@link #hasFluxFlags()} first (unknown = hold).
     */
    public boolean isGodBit() {
        return fluxFlags >= 0 && (fluxFlags & FLAG_GOD) != 0;
    }

    /**
     * true when the carried flux flags say the server's travel
     * drive blocks firing on this hull ({@link #FLAG_DRIVE_LOCK}); {@code false} for an unlocked hull
     * AND for a pose with no flux tier — the {@code DriveLockLane} gates on {@link #hasFluxFlags()}
     * first (unknown = no hold, fail-open).
     */
    public boolean isDriveLockBit() {
        return fluxFlags >= 0 && (fluxFlags & FLAG_DRIVE_LOCK) != 0;
    }

    /**
     * Authoritative per-engine disabled bitset (bit i = engine i of
     * {@code getShipEngines()} spec order, first 32 — the server's
     * {@code ShipEngineStateSnapshot.disabledMask()}), or {@code 0} when the frame
     * carried no engine-state tier (check {@link #hasEngineState()} first).
     */
    public int engineDisabledMask() {
        return engineDisabledMask;
    }

    /** Authoritative per-engine permanently-disabled bitset (see {@link #engineDisabledMask()}). */
    public int enginePermDisabledMask() {
        return enginePermDisabledMask;
    }

    /**
     * The server controller's derived engine state: {@code 0} ACTIVE, {@code 1} FLAMING_OUT,
     * {@code 2} FLAMED_OUT, or {@link #UNKNOWN_ENGINE_STATE} when the frame carried no
     * engine-state tier for this entity. Meters/validation only — the client applies the
     * per-engine masks and lets its local controller derive flameout.
     */
    public int engineControllerState() {
        return engineControllerState;
    }

    /** True when this pose carries the authoritative engine-state tier (a schema-v6+ frame). */
    public boolean hasEngineState() {
        return engineControllerState >= 0;
    }

    /**
     * This ship's authoritative maneuver-command bitmask (the server's schema-v7
     * {@code ShipManeuverCommandSnapshot.maneuverMask()}): the foe server-ship
     * controller's 7 maneuver booleans in {@code WIRE_BIT_*} order (bit 0 ACCELERATE, 1
     * ACCELERATE_BACKWARDS, 2 DECELERATE, 3 TURN_LEFT, 4 TURN_RIGHT, 5 STRAFE_LEFT, 6
     * STRAFE_RIGHT), or {@link #UNKNOWN_MANEUVER_MASK} when the frame carried no record for this
     * entity. The {@code cc.predict.foeManeuver} lane drives the replica's engine controller with
     * it so plumes/contrails/engine sound present through the engine's real command path.
     */
    public int maneuverCommandMask() {
        return maneuverCommandMask;
    }

    /** True when this pose carries the authoritative maneuver-command tier (a schema-v7+ frame). */
    public boolean hasManeuverCommandMask() {
        return maneuverCommandMask >= 0;
    }

    /**
     * This ship's authoritative per-mount weapon-readiness states (the server's schema-v4
     * {@code ShipWeaponMountSnapshot} tier), or an empty list when the frame carried
     * no mount record for this entity (pre-v4 server / a ship with no mounts). The
     * {@code cc.predict.weaponState} reconcile lane compares each carried {@code disabled} bit
     * against the local {@code WeaponAPI.isDisabled()}. Never {@code null}.
     */
    public java.util.List<ServerWeaponMountState> weaponMounts() {
        return weaponMounts;
    }

    /** True when this pose carries at least one authoritative weapon-mount record. */
    public boolean hasWeaponMounts() {
        return !weaponMounts.isEmpty();
    }

    /**
     * This ship's authoritative primary-system phase (the server's schema-v8
     * {@code ShipSystemStateSnapshot.stateCode()}): the PROJECT-OWNED codes {@code 0}
     * IDLE, {@code 1} CHARGEUP, {@code 2} ACTIVE, {@code 3} CHARGEDOWN, {@code 4} COOLDOWN, or
     * {@link #UNKNOWN_SYSTEM_STATE} when the frame carried no record for this entity. The
     * {@code cc.predict.foeSystemState} seed lane uses it (with {@link #systemEffectLevel()} /
     * {@link #systemCooldownRemaining()}) to {@code forceState} a mid-window replica system once
     * per entity per join/round (D4) — steady state stays edge-driven {@code useSystem}.
     */
    public int systemStateCode() {
        return systemStateCode;
    }

    /** Authoritative system effect level in {@code [0,1]}; meaningless without {@link #hasSystemState()}. */
    public float systemEffectLevel() {
        return systemEffectLevel;
    }

    /** Authoritative system cooldown remaining, seconds; meaningless without {@link #hasSystemState()}. */
    public float systemCooldownRemaining() {
        return systemCooldownRemaining;
    }

    /**
     * Authoritative system charge count, or {@code -1} for a no-charge system (the server
     * normalizes the engine's {@code Integer.MAX_VALUE} no-ammo sentinel to {@code -1} before it
     * travels). Meaningless without {@link #hasSystemState()} — the tier-absent default is also
     * {@code -1}, so gate on the state code, not on ammo.
     */
    public int systemAmmo() {
        return systemAmmo;
    }

    /** True when this pose carries the authoritative ship-system state tier (a schema-v8+ frame). */
    public boolean hasSystemState() {
        return systemStateCode >= 0;
    }

    /**
     * This ship's authoritative phase-cloak phase (the server's schema-v10
     * {@code ShipPhaseCloakStateSnapshot.stateCode()}): the same PROJECT-OWNED codes
     * as {@link #systemStateCode()} ({@code 0} IDLE, {@code 1} CHARGEUP, {@code 2} ACTIVE,
     * {@code 3} CHARGEDOWN, {@code 4} COOLDOWN), read off the ship's {@code getPhaseCloak()}
     * slot, or {@link #UNKNOWN_PHASE_CLOAK_STATE} when the frame carried no record for this
     * entity (a cloakless hull / a pre-v10 server). The {@code cc.predict.foeCloak} lane uses
     * it (with {@link #cloakEffectLevel()} / {@link #cloakCooldownRemaining()}) to seed a
     * mid-window replica cloak once per entity — steady state stays mismatch-edge-driven.
     */
    public int cloakStateCode() {
        return cloakStateCode;
    }

    /** Authoritative cloak effect level in {@code [0,1]}; meaningless without {@link #hasPhaseCloakState()}. */
    public float cloakEffectLevel() {
        return cloakEffectLevel;
    }

    /** Authoritative cloak cooldown remaining, seconds; meaningless without {@link #hasPhaseCloakState()}. */
    public float cloakCooldownRemaining() {
        return cloakCooldownRemaining;
    }

    /**
     * Authoritative cloak charge count, or {@code -1} for a no-charge cloak (the server
     * normalizes the engine's {@code Integer.MAX_VALUE} no-ammo sentinel to {@code -1} before
     * it travels, same as {@link #systemAmmo()}). Meaningless without
     * {@link #hasPhaseCloakState()} — the tier-absent default is also {@code -1}, so gate on
     * the state code, not on ammo.
     */
    public int cloakAmmo() {
        return cloakAmmo;
    }

    /** True when this pose carries the authoritative phase-cloak state tier (a schema-v10+ frame). */
    public boolean hasPhaseCloakState() {
        return cloakStateCode >= 0;
    }

    /**
     * The simTick at which the merger last ASSERTED this entity's phase-cloak tier — i.e. the
     * tick of the newest keyframe that carried it (deltas never do: the v10 cloak block is
     * keyframe-only, hold-last between). Stamped by {@code SnapshotChunkMerger.synthesize}
     * from the kernel's per-tier tick; {@link #UNKNOWN_CLOAK_TIER_TICK} when never asserted.
     * Under remedy A the {@code cc.predict.foeCloak} lane treats a CHANGE of this
     * value as "a fresh keyframe landed" and only then lets the tier repair the twin — a held
     * (unchanged-tick) tier value never reverts an edge the per-tick {@link #isPhasedBit()}
     * applied. Meaningless without {@link #hasPhaseCloakState()}.
     */
    public long cloakTierTick() {
        return cloakTierTick;
    }

    /**
     * This ship's authoritative shield facing, quantized ({@code 0..255} at
     * {@link #SHIELD_FACING_STEP_DEG} steps — dequant = {@code u8 * SHIELD_FACING_STEP_DEG}), or
     * {@link #UNKNOWN_SHIELD_FACING} when the frame carried no facing byte for this entity.
     * Meaningful only when {@link #hasFluxFlags()} and the {@code FLAG_HAS_SHIELD}
     * bit is set (shieldless ships mint {@code 0} server-side — never consume it without the
     * flag gate). The {@code cc.predict.foeShield} lane aims the OMNI hold from it and
     * {@code forceFacing}s the replica shield while on; UNKNOWN = KEEP-LAST-FORCED (sticky).
     */
    public int shieldFacingU8() {
        return shieldFacingU8;
    }

    /** True when this pose carries the authoritative shield-facing byte (see {@link #shieldFacingU8()}). */
    public boolean hasShieldFacing() {
        return shieldFacingU8 >= 0;
    }

    /**
     * This ship's authoritative combat-readiness fraction (the server's schema-v14
     * {@code ShipCrSnapshot.cr()} — the engine's {@code getCurrentCR()} in {@code [0,1]},
     * lenient upper bound), or {@link #UNKNOWN_CR} when the frame carried no CR
     * record for this entity (a pre-v14 server / a sparse-absent ship). The
     * {@code cc.predict.ownCr} lane pins the local engine's {@code setCurrentCR} from it —
     * the local CR/PPT clock freezes when the local engine scans a different roster than
     * the authoritative one.
     */
    public float cr() {
        return cr;
    }

    /**
     * Authoritative RAW deployed-seconds accumulator (the engine's
     * {@code getTimeDeployedForCRReduction()} — the HUD subtracts from its LOCAL peak
     * itself, so the wire never carries "PPT remaining"); meaningless without
     * {@link #hasCr()}. The {@code cc.predict.ownCr} lane pins the local engine's
     * {@code setTimeDeployed} from it.
     */
    public float crDepSec() {
        return crDepSec;
    }

    /**
     * Authoritative effective peak-readiness duration, seconds — diagnostic-grade context
     * (the HUD derives peak from LOCAL stats; never-lose-CR hulls carry effective peaks
     * ≥ 1e5 s by design). Meaningless without {@link #hasCr()}.
     */
    public float crPeakSec() {
        return crPeakSec;
    }

    /** True when this pose carries the authoritative schema-v14 CR clock tier (see {@link #cr()}). */
    public boolean hasCr() {
        return cr >= 0f;
    }

    /**
     * Authoritative whole-grid armor fraction in {@code [0,1]} (the AWSD v10 keyframe u8 ÷ 255 —
     * server {@code armorTotal / (cellCount × maxInCell)}); meaningless without
     * {@link #hasArmor()}. Consumed by the {@code cc.predict.foeArmorReconcile} + {@code ownArmorReconcile} lanes ({@code ArmorReconcileLane}).
     */
    public float armorFrac() {
        return armorFrac;
    }

    /**
     * Authoritative SHARED fighter-replacement percent as the vanilla fighter panel prints it
     * ({@code (int)(getSharedFighterReplacementRate() * 100)}, AWSD v12 keyframe u8, hold-last
     * on deltas); meaningless without {@link #hasFighterReplacementPct()}. Consumed by the
     * fighter-HUD lane's rate-line override.
     */
    public int fighterReplacementPct() {
        return fighterReplacementPct;
    }

    /** True when this pose carries the AWSD v12 fighter-replacement percent. */
    public boolean hasFighterReplacementPct() {
        return fighterReplacementPct >= 0;
    }

    /**
     * True when this pose carries the AWSD v10 armor fraction tier — gates on SIGN, because
     * {@code 0} is a REAL value (armor fully ablated; NO min-1 clamp on this family).
     */
    public boolean hasArmor() {
        return armorFrac >= 0f;
    }

    /**
     * Return a copy of this pose with the given combat {@code fleetSide} — what the schema-v5
     * decode path calls after it has read the allegiance section and matched this entity. All
     * other fields (including any flux already attached) are unchanged.
     */
    public ServerEntityPose withFleetSide(int fleetSide) {
        return toBuilder().fleetSide(fleetSide).build();
    }

    /**
     * Return a copy of this pose with the given authoritative flux — what the schema-v3+ decode
     * path calls after it has read the flux section and matched this entity. All other fields
     * (including any {@code fleetSide} already attached) are unchanged.
     */
    public ServerEntityPose withFlux(float currFlux, float maxFlux, float fluxLevel) {
        return toBuilder().currFlux(currFlux).maxFlux(maxFlux).fluxLevel(fluxLevel).build();
    }

    /**
     * {@link #withFlux(float, float, float)} variant additionally attaching the hard-flux and
     * packed status-flags half of the flux tier — what both decode paths call once
     * they have read a flux record for this entity. All other fields are unchanged.
     */
    public ServerEntityPose withFlux(
            float currFlux, float maxFlux, float fluxLevel, float hardFlux, int fluxFlags) {
        return toBuilder().currFlux(currFlux).maxFlux(maxFlux).fluxLevel(fluxLevel)
                .hardFlux(hardFlux).fluxFlags(fluxFlags).build();
    }

    /**
     * {@link #withFlux(float, float, float, float, int)} variant additionally attaching the
     * quantized shield-facing byte — what the AWSS decode path calls for a
     * schema-v11+ flux record (the AWSD v4 lane mints it in {@code resolvePose} instead). The
     * 5-arg variant leaves any facing already on this pose untouched, so a pre-v11 record
     * never clobbers a known facing to {@link #UNKNOWN_SHIELD_FACING}.
     */
    public ServerEntityPose withFlux(
            float currFlux, float maxFlux, float fluxLevel, float hardFlux, int fluxFlags,
            int shieldFacingU8) {
        return toBuilder().currFlux(currFlux).maxFlux(maxFlux).fluxLevel(fluxLevel)
                .hardFlux(hardFlux).fluxFlags(fluxFlags).shieldFacingU8(shieldFacingU8).build();
    }

    /**
     * Return a copy of this pose with the given authoritative engine state — what the schema-v6
     * decode path calls after it has read the engine-state section and matched this entity. All
     * other fields (including any side/flux already attached) are unchanged.
     */
    public ServerEntityPose withEngineState(
            int engineDisabledMask, int enginePermDisabledMask, int engineControllerState) {
        return toBuilder().engineDisabledMask(engineDisabledMask)
                .enginePermDisabledMask(enginePermDisabledMask)
                .engineControllerState(engineControllerState).build();
    }

    /**
     * Return a copy of this pose with the given authoritative maneuver-command mask — what the
     * schema-v7 decode path calls after it has read the maneuver-command section and matched this
     * entity. All other fields are unchanged.
     */
    public ServerEntityPose withManeuverCommandMask(int maneuverCommandMask) {
        return toBuilder().maneuverCommandMask(maneuverCommandMask).build();
    }

    /**
     * Return a copy of this pose with the given authoritative weapon-mount states — what the
     * schema-v4+ decode path calls after it has read the weapon-mount section and grouped this
     * entity's records. All other fields are unchanged.
     */
    public ServerEntityPose withWeaponMounts(java.util.List<ServerWeaponMountState> weaponMounts) {
        return toBuilder().weaponMounts(weaponMounts).build();
    }

    /**
     * Return a copy of this pose with the given authoritative ship-system state — what the
     * schema-v8 decode path calls after it has read the system-state section and matched this
     * entity. All other fields are unchanged.
     */
    public ServerEntityPose withSystemState(
            int systemStateCode, float systemEffectLevel, float systemCooldownRemaining,
            int systemAmmo) {
        return toBuilder().systemStateCode(systemStateCode).systemEffectLevel(systemEffectLevel)
                .systemCooldownRemaining(systemCooldownRemaining).systemAmmo(systemAmmo).build();
    }

    /**
     * Return a copy of this pose with the given authoritative phase-cloak state — what the
     * schema-v10 decode path calls after it has read the phase-cloak section (appended after
     * the fragment-marker section, D-M2-2) and matched this entity. All other
     * fields are unchanged.
     */
    public ServerEntityPose withPhaseCloakState(
            int cloakStateCode, float cloakEffectLevel, float cloakCooldownRemaining,
            int cloakAmmo) {
        return toBuilder().cloakStateCode(cloakStateCode).cloakEffectLevel(cloakEffectLevel)
                .cloakCooldownRemaining(cloakCooldownRemaining).cloakAmmo(cloakAmmo).build();
    }

    /**
     * Return a copy of this pose with the given authoritative CR clock — what the
     * schema-v14 decode path calls after it has read the ship-CR section (appended after
     * the phase-cloak section) and matched this entity. All other fields are
     * unchanged.
     */
    public ServerEntityPose withShipCr(float cr, float crDepSec, float crPeakSec) {
        return toBuilder().cr(cr).crDepSec(crDepSec).crPeakSec(crPeakSec).build();
    }

    /**
     * Return a copy of this pose with the given authoritative armor fraction — the AWSD v10
     * decode path mints it in {@code resolvePose}; nothing on the AWSS lane
     * calls this in v1 (armor scalars discarded-by-design). All other fields are
     * unchanged.
     */
    public ServerEntityPose withArmor(float armorFrac) {
        return toBuilder().armorFrac(armorFrac).build();
    }

    @Override
    public String toString() {
        return "ServerEntityPose{id=" + entityId
                + ",contentId=" + contentId
                + ",owner=" + ownerConnectionId
                + ",loc=(" + locX + "," + locY + ")"
                + ",vel=(" + velX + "," + velY + ")"
                + ",facing=" + facing
                + ",angVel=" + angularVelocity
                + ",hp=" + hitpoints
                + ",side=" + fleetSide
                + ",flux=" + (hasFlux() ? (currFlux + "/" + maxFlux + "@" + fluxLevel) : "n/a")
                + ",hard=" + (hasHardFlux() ? String.valueOf(hardFlux) : "n/a")
                + ",fflags=" + (hasFluxFlags() ? "0x" + Integer.toHexString(fluxFlags) : "n/a")
                + ",shFacing=" + (hasShieldFacing() ? String.valueOf(shieldFacingU8) : "n/a")
                + ",eng=" + (hasEngineState()
                        ? (engineControllerState + ":0x" + Integer.toHexString(engineDisabledMask)
                                + "/0x" + Integer.toHexString(enginePermDisabledMask))
                        : "n/a")
                + ",mnv=" + (hasManeuverCommandMask()
                        ? "0x" + Integer.toHexString(maneuverCommandMask) : "n/a")
                + ",sys=" + (hasSystemState()
                        ? (systemStateCode + "@" + systemEffectLevel + "/"
                                + systemCooldownRemaining + ",ammo=" + systemAmmo)
                        : "n/a")
                + ",cloak=" + (hasPhaseCloakState()
                        ? (cloakStateCode + "@" + cloakEffectLevel + "/"
                                + cloakCooldownRemaining + ",ammo=" + cloakAmmo
                                + ",kf=" + cloakTierTick)
                        : "n/a")
                + ",cr=" + (hasCr()
                        ? (cr + "@" + crDepSec + "/" + crPeakSec) : "n/a")
                + ",armor=" + (hasArmor() ? String.valueOf(armorFrac) : "n/a")
                + ",mounts=" + weaponMounts.size() + "}";
    }
}
