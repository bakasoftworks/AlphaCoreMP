package cc.starsector.server.bridge;

import cc.starsector.server.adapter.CombatEntityView;
import cc.starsector.server.adapter.CombatOutcomeView;
import cc.starsector.server.adapter.ShipAllegianceView;
import cc.starsector.server.adapter.ShipCommandSink;
import cc.starsector.server.adapter.ShipCrView;
import cc.starsector.server.adapter.ShipDurabilityView;
import cc.starsector.server.adapter.ShipEngineStateView;
import cc.starsector.server.adapter.ShipFluxView;
import cc.starsector.server.adapter.ShipIdentityView;
import cc.starsector.server.adapter.ShipLifecycleView;
import cc.starsector.server.adapter.ShipManeuverCommandView;
import cc.starsector.server.adapter.ShipManeuverView;
import cc.starsector.server.adapter.ShipMotionView;
import cc.starsector.server.adapter.ShipPhaseCloakView;
import cc.starsector.server.adapter.ShipPoseSink;
import cc.starsector.server.adapter.ShipProfileView;
import cc.starsector.server.adapter.ShipSystemView;
import cc.starsector.server.adapter.WeaponMountView;
import cc.starsector.server.bridge.FreshSpawnScan.FreshSpawn;
import cc.starsector.server.protocol.NonShipEntitySnapshot;
import cc.starsector.server.runtime.HullSourceLedger;
import cc.starsector.server.runtime.MissileSplitEventDetector;
import cc.starsector.server.runtime.ParityAuditWalker;
import cc.starsector.server.runtime.RosterCombatOutcome;
import cc.starsector.server.runtime.ShipNetworkIdRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Engine-backed reflection binding for the headless combat-step adapters.
 *
 * <p>This is the first <em>real</em> simulation-adapter binding promoted into
 * {@code server/}: it connects the dead adapter interfaces
 * ({@link cc.starsector.server.adapter.CombatStepBoundary} and friends) to a live
 * combat engine. The binding owns the
 * reflection bridge but never renders an engine-internal name in source: the caller
 * (the headless scout) resolves the
 * version-specific {@link Method}/{@link Field}/{@link Class} handles by
 * descriptor and hands them in here. The engine instance and every game object it
 * touches stay opaque {@link Object}s.
 *
 * <p>All reflective crossings funnel through this class and fail closed: any
 * reflection failure is rethrown as an unchecked {@link EngineBindingException} so
 * a partial/inconsistent read can never be mistaken for real state. The adapters
 * built on top of this binding read post-step counts, the player-ship binding, and
 * optional player-ship motion used by the state hash.
 */
public final class EngineCombatBinding {

    private static final long SINGLE_PLAYER_SHIP_ID = 1L;

    /** Unchecked wrapper so adapter/loop code can stay on the clean interfaces. */
    public static final class EngineBindingException extends RuntimeException {
        public EngineBindingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    final Object engine; // pkg-private: PilotCommandApplier
    private final PilotCommandApplier pilotCommands; // the pilot-command apply lane (own file)
    private final Method advanceInner;
    private final Method advanceOuter;
    private final Object stepAmountArg;
    private final Method getObjects;
    private final Method repositoryGetList;
    private final Class<?> shipClass;
    private final Method getFleetManagers;
    private final Method getCombatMap;
    private final Method getPlayerShip;
    private final Method playerShipGetLocation; // nullable: hash falls back to counts
    private final Method playerShipGetVelocity; // nullable: motion view unavailable
    private final Method playerShipGetFacing; // nullable: motion view unavailable
    private final Method playerShipGetAngularVelocity; // nullable: motion view unavailable
    private final Method shipGetHitpoints; // nullable: durability view unavailable
    private final Method shipGetHullLevel; // nullable: durability view unavailable
    private final Method shipGetArmorGrid; // nullable: durability view unavailable
    private final Method armorGridGetGrid; // nullable: durability view unavailable
    // ArmorGridAPI.getMaxArmorInCell — the armorLevel denominator
    // (armorTotal / (cellCount × maxInCell)); engine-exact both sides (every cell
    // mints at getMaxArmorInCell() = rating/15), resolved in the durability group.
    private final Method armorGridGetMaxArmorInCell; // nullable: durability view unavailable
    private final Method shipGetHullSpec; // nullable: identity view unavailable
    private final Method hullSpecGetHullId; // nullable: identity view unavailable
    private final Method shipGetId; // nullable: lifecycle (stable id) view unavailable
    private final Method shipIsAlive; // nullable: lifecycle (liveness) view unavailable
    private final Method shipGetOwner; // nullable: allegiance (side) view unavailable
    private final Method shipGetEngineController; // nullable: engine-state view unavailable
    private final Method controllerGetShipEngines; // nullable: engine-state view unavailable
    private final Method controllerIsFlamingOut; // nullable: engine-state view unavailable
    private final Method controllerIsDisabled; // nullable: engine-state view unavailable
    private final Method engineIsDisabled; // nullable: engine-state view unavailable
    private final Method engineIsPermanentlyDisabled; // nullable: engine-state view unavailable
    private final Method maneuverGetEngineController; // nullable: maneuver-command view unavailable
    private final Method controllerIsAccelerating; // nullable: maneuver-command view unavailable
    private final Method controllerIsAcceleratingBackwards; // nullable: maneuver-command view unavailable
    private final Method controllerIsDecelerating; // nullable: maneuver-command view unavailable
    private final Method controllerIsTurningLeft; // nullable: maneuver-command view unavailable
    private final Method controllerIsTurningRight; // nullable: maneuver-command view unavailable
    private final Method controllerIsStrafingLeft; // nullable: maneuver-command view unavailable
    private final Method controllerIsStrafingRight; // nullable: maneuver-command view unavailable
    private final Method shipGetFluxTracker; // nullable: flux view unavailable
    private final Method fluxGetCurrFlux; // nullable: flux view unavailable
    private final Method fluxGetMaxFlux; // nullable: flux view unavailable
    private final Method fluxGetFluxLevel; // nullable: flux view unavailable
    private final Method fluxGetHardFlux; // nullable: flux view unavailable
    private final Method fluxIsOverloaded; // nullable: flux view unavailable
    private final Method fluxIsVenting; // nullable: flux view unavailable
    private final Method shipGetShield; // nullable: shield read optional (shieldless hull or unbound)
    private final Method shieldIsOn; // nullable: shield read optional
    private final Method shieldGetFacing; // nullable: shield-facing read optional (unbound -> facing 0)
    private final Method shipGetAllWeapons; // nullable: weapon-mount view unavailable
    // BASE-spec fire-class reads (nullable as ONE tier: any null ⇒ the production resolver
    // answers class H, fail-safe): WeaponAPI.getSpec / WeaponSpecAPI.isBeam+getWeaponId /
    // ProjectileWeaponSpecAPI getRefireDelay+getBurstSize+getBurstDelay + the
    // ProjectileWeaponSpecAPI interface for the non-beam instanceof test (beam specs are a
    // SIBLING interface — bytecode-verified). Never the live stat-modified getCooldown().
    private final Method weaponGetSpec;
    private final Method weaponSpecIsBeam;
    private final Method weaponSpecGetWeaponId;
    private final Method projSpecGetRefireDelay;
    private final Method projSpecGetBurstSize;
    private final Method projSpecGetBurstDelay;
    private final Class<?> projectileWeaponSpecApi;
    private final Method weaponGetId; // nullable: weapon-mount view unavailable
    private final Method weaponGetCooldownRemaining; // nullable: weapon-mount view unavailable
    private final Method weaponGetCooldown; // nullable: weapon-mount view unavailable
    private final Method weaponIsFiring; // nullable: weapon-mount view unavailable
    private final Method weaponUsesAmmo; // nullable: weapon-mount view unavailable
    private final Method weaponGetAmmo; // nullable: weapon-mount view unavailable
    private final Method weaponGetMaxAmmo; // nullable: weapon-mount view unavailable
    private final Method weaponIsDisabled; // nullable: weapon-mount view unavailable
    /**
     * Nullable. {@code WeaponAPI.isDisabled()} FOLDS the
     * script force-disable flag (a Threat fragment mount short of
     * members reads disabled every frame, {@code BaseFragmentMissileEffect.advance}); the
     * replica reconciles that bit against its OWN local swarm count, which diverges (launches never
     * drain it) ⇒ the hive's disable()/repair() edge flicker (flight {@code 003637} T1). With this
     * handle bound the wire bit means engine-disabled (EMP / mount damage) only; the replica's own
     * vanilla {@code advance} keeps authoring the force-look from the local count, as vanilla shows.
     */
    private final Method weaponIsForceDisabled;
    private final Method weaponGetChargeLevel; // nullable: weapon-mount view unavailable
    private final Method weaponGetFluxCostToFire; // nullable: the readiness probe skips flux-headroom
    private final Method weaponDistanceFromArc; // nullable: the readiness probe skips the arc gate
    private final Method weaponGetLocation; // nullable: the adopt-serve muzzle-ε read (fail-CLOSED)
    private final Method weaponSetForceFireOneFrame; // nullable: the named-mount serve write (fail-CLOSED)
    private final Method weaponGetCurrAngle; // nullable: mount currAngle reads NaN (tier stays available)
    private final Method weaponGetProjectileSpeed; // nullable: mount projectileSpeed reads NaN (tier stays available)
    private final Method shipGetSystem; // nullable: ship-system view unavailable
    private final Method systemGetId; // nullable: ship-system view unavailable
    private final Method systemGetState; // nullable: ship-system view unavailable
    private final Method systemIsActive; // nullable: ship-system view unavailable
    private final Method systemIsOn; // nullable: ship-system view unavailable
    private final Method systemGetCooldownRemaining; // nullable: ship-system view unavailable
    private final Method systemGetCooldown; // nullable: ship-system view unavailable
    private final Method systemGetEffectLevel; // nullable: ship-system view unavailable
    private final Method systemGetAmmo; // nullable: ship-system view unavailable
    private final Method systemGetMaxAmmo; // nullable: ship-system view unavailable
    private final Method systemIsOutOfAmmo; // nullable: ship-system view unavailable
    private final Method shipGetHullSize; // nullable: ship-profile view unavailable
    private final Method entityGetCollisionRadius; // nullable: ship-profile view unavailable
    private final Method entityGetMass; // nullable: ship-profile view unavailable
    private final Method shipGetMutableStats; // nullable: ship-maneuver view unavailable
    private final Method statsGetMaxSpeed; // nullable: ship-maneuver view unavailable
    private final Method statsGetAcceleration; // nullable: ship-maneuver view unavailable
    private final Method statsGetDeceleration; // nullable: ship-maneuver view unavailable
    private final Method statsGetMaxTurnRate; // nullable: ship-maneuver view unavailable
    private final Method statsGetTurnAcceleration; // nullable: ship-maneuver view unavailable
    private final Method statGetModifiedValue; // nullable: ship-maneuver view unavailable
    private final Method statsGetTimeMult; // nullable: the live per-ship timeMult read unavailable => motion view rides the constant
    private final Method combatGetProjectiles; // nullable: projectile entity view unavailable
    private final Method combatGetMissiles; // nullable: missile entity view unavailable
    private final Method combatGetBeams; // nullable: beam entity view unavailable
    private final Method engineGetWinningSideId; // nullable: combat-outcome view unavailable
    private final Method engineGetCustomData; // nullable: the mote-target read is simply off
    private final Method engineIsCombatOver; // nullable: combat-outcome view unavailable
    private final Method engineIsPaused; // nullable: pause-state read unavailable
    private final Method engineSetPaused; // nullable: pause control unavailable
    private final Method playerShipGiveCommand; // nullable: command sink unavailable
    private final Object movementCommandAccelerate; // nullable: intent unsupported
    private final Object movementCommandDecelerate; // nullable: intent unsupported
    private final Object movementCommandStrafeLeft; // nullable: intent unsupported
    private final Object movementCommandStrafeRight; // nullable: intent unsupported
    private final Object movementCommandTurnLeft; // nullable: intent unsupported
    private final Object movementCommandTurnRight; // nullable: intent unsupported
    private final Object weaponCommandSelectGroup; // nullable: intent unsupported
    private final Object weaponCommandFire; // nullable: intent unsupported; on the real engine = ShipCommand.USE_SELECTED_GROUP (the name predates it)
    private final Object weaponCommandHoldFire; // nullable: intent unsupported
    private final Object weaponCommandVent; // nullable: flux-vent command unsupported
    private final Method shipGetPhaseCloak; // nullable: the phase guard unavailable => shield lane FAILS CLOSED (same verb toggles the cloak)
    private final Method shipIsPhased; // nullable: the phased truth unavailable => phase-cloak state tier unavailable
    private final Method shipGetCurrentCR; // nullable: the CR tier unavailable
    private final Method shipGetSharedFighterReplacementRate; // nullable: the fighter-rate read unbound => CR records carry NO_FIGHTER_REPLACEMENT_PCT
    private final Method shipGetTimeDeployedForCR; // nullable: the CR tier unavailable
    private final Method statsGetPeakCRDuration; // nullable: the CR tier unavailable (StatBonus off MutableShipStatsAPI)
    private final Method statBonusComputeEffective; // nullable: the CR tier unavailable (StatBonus.computeEffective(float))
    private final Method hullSpecGetNoCRLossTime; // nullable: the CR tier unavailable
    private final Method playerShipUseSystem; // nullable: system activation unsupported
    private final Method shipSetLoc; // nullable: the authoritative pose write unavailable
    private final Method shipSetVel; // nullable: the authoritative pose write unavailable
    private final Method shipSetFacing; // nullable: the authoritative facing write unavailable
    private final Constructor<?> vector2fCtor; // nullable: the Vector2f(float,float) arg unavailable
    private final Method shipSetShipAI; // nullable: (B) AI-suppression unavailable (fake binding leaves null => no-op)
    private final Method shipGetShipAI; // nullable: (B) AI-suppression unavailable
    private final Method shipGetWeaponGroupsCopy; // nullable: the group/autofire mirror unavailable
    private final Method shipGetSelectedGroupAPI; // nullable: the group mirror unavailable
    private final Method shipGetWeaponGroupFor; // nullable: the mount->group resolve unavailable
    private final Method weaponGroupIsAutofiring; // nullable: the autofire mirror unavailable
    private final Method weaponGroupToggleOff;
    private final Object weaponCommandToggleAutofire; // nullable: the autofire toggle unsupported (validated here; the applier holds its own copy) // nullable: the build-time autofire disarm unavailable
    private final Method engineIsInFastTimeAdvance; // nullable: the fast-time gate unavailable => DD1 shim never installs (fail-closed)
    final Set<Long> ownedShipIds; // pkg-private: PilotCommandApplier; // server-owned (commandable/pose-writable) repository-order ids
    private final LiveWireShipResolver liveShipResolver; // nullable: fakes/legacy => positional resolve
    // Extraction: non-held-lane reflection families (remnant walk, tube mirror,
    // fog re-mint) live in the collaborator; the tube-mirror group handles moved with them.
    final EngineReflectionBinding reflection; // pkg-private: PilotCommandApplier
    private final FreshSpawnScan freshSpawnScan; // the GPEE fresh-spawn scan (own file)
    private final float timeMult;
    private final float combatSpeedMult;
    private final boolean fogBound;
    volatile float lastEffectiveStepAmount = Float.NaN; // pkg-private: PilotCommandApplier
    // W3 drone-orbit Shape 1: the engine-native ring drive. ALL its
    // reflection lives in the collaborator (the -f shard reflection-family home law — this
    // file is past its size WARN tier and carries only the hook + these pass-throughs).
    final OrbitDroneDrive orbitDroneDrive = new OrbitDroneDrive(); // pkg-private: PilotCommandApplier
    // B2: log the tolerated headless retreat-report NPE once, not every frame.
    private volatile boolean headlessRetreatNpeReported;
    // The motion lane fails closed by design (any per-ship read failure nulls
    // the WHOLE list), which left the V7 probe's no_motion_lane verdict causeless. Name the first
    // failure once, then stay silent (serve-cadence lane).
    private volatile boolean motionLaneNullReported;
    // (B) Option A AI-suppression tally: strip the demo deploy's BasicShipAI from an
    // owned ship the first time a client commands it so client intent is not fought by the engine's
    // BasicEngineAI. Counts one suppression per ship per deployed round (re-suppressed after redeploy).
    private final java.util.concurrent.atomic.AtomicLong aiSuppressions =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean aiSuppressionErrorReported;
    // DD1 fast-time reinject shim registries, sim-thread-only. Keyed by SHIP
    // OBJECT identity. wrapperByShip pins the READBACK of getShipAI() after install — Ship.setShipAI
    // WRAPS a non-engine ShipAIPlugin in Ship.ShipAIWrapper,
    // so the suppression whitelist must key on the wrapper the engine actually holds, never the
    // proxy we handed in. Entries prune on install (dead-ship sweep); a redeployed hull gets a
    // fresh fly-in install (self-healing, the maybeSuppressShipAi redeploy posture).
    private final IdentityHashMap<Object, Object> reinjectWrapperByShip = new IdentityHashMap<>();
    final IdentityHashMap<Object, FastTimeReinjectShim> reinjectShimByShip = new IdentityHashMap<>();
    // The live W5 grace AI per hull (ship-object identity, sim-thread-only) — the
    // maybeSuppressShipAi whitelist pin for grace episodes. The flown self-check proved the
    // "vacant seat ⇒ no command-time path" assumption FALSE (the staged-frame replay strips at
    // command time), and the lane's staged-frame drop closes only the KNOWN path — this pin makes
    // the invariant structural: no command-time touch may reap an AI the grace lane owns. Pinned
    // at attach (getShipAI readback), dropped at detach; dead-hull entries sweep at the next
    // attach (grace is rare — one sweep per episode is nothing).
    private final IdentityHashMap<Object, Object> graceAiByShip = new IdentityHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong fastTimeReinjects =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean fastTimeErrorReported;
    // Piloted hulls' cloak charge tracker ticks wall time (sim thread only;
    // WHY + mechanism + fail-closed law = the lane's javadoc).
    private final PilotedCloakWallClock pilotedCloakWallClock = new PilotedCloakWallClock();

    private EngineCombatBinding(Builder b) {
        this.engine = Objects.requireNonNull(b.engine, "engine");
        this.advanceInner = Objects.requireNonNull(b.advanceInner, "advanceInner");
        this.advanceOuter = b.advanceOuter;
        this.stepAmountArg = b.stepAmountArg; // may legitimately be null for some overloads
        this.getObjects = Objects.requireNonNull(b.getObjects, "getObjects");
        this.repositoryGetList = Objects.requireNonNull(b.repositoryGetList, "repositoryGetList");
        this.shipClass = Objects.requireNonNull(b.shipClass, "shipClass");
        this.getFleetManagers = Objects.requireNonNull(b.getFleetManagers, "getFleetManagers");
        this.getCombatMap = Objects.requireNonNull(b.getCombatMap, "getCombatMap");
        this.getPlayerShip = Objects.requireNonNull(b.getPlayerShip, "getPlayerShip");
        this.playerShipGetLocation = b.playerShipGetLocation;
        this.playerShipGetVelocity = b.playerShipGetVelocity;
        this.playerShipGetFacing = b.playerShipGetFacing;
        this.playerShipGetAngularVelocity = b.playerShipGetAngularVelocity;
        this.shipGetHitpoints = b.shipGetHitpoints;
        this.shipGetHullLevel = b.shipGetHullLevel;
        this.shipGetArmorGrid = b.shipGetArmorGrid;
        this.armorGridGetGrid = b.armorGridGetGrid;
        this.armorGridGetMaxArmorInCell = b.armorGridGetMaxArmorInCell;
        this.shipGetHullSpec = b.shipGetHullSpec;
        this.hullSpecGetHullId = b.hullSpecGetHullId;
        this.shipGetId = b.shipGetId;
        this.shipIsAlive = b.shipIsAlive;
        this.shipGetOwner = b.shipGetOwner;
        this.shipGetEngineController = b.shipGetEngineController;
        this.controllerGetShipEngines = b.controllerGetShipEngines;
        this.controllerIsFlamingOut = b.controllerIsFlamingOut;
        this.controllerIsDisabled = b.controllerIsDisabled;
        this.engineIsDisabled = b.engineIsDisabled;
        this.engineIsPermanentlyDisabled = b.engineIsPermanentlyDisabled;
        this.maneuverGetEngineController = b.maneuverGetEngineController;
        this.controllerIsAccelerating = b.controllerIsAccelerating;
        this.controllerIsAcceleratingBackwards = b.controllerIsAcceleratingBackwards;
        this.controllerIsDecelerating = b.controllerIsDecelerating;
        this.controllerIsTurningLeft = b.controllerIsTurningLeft;
        this.controllerIsTurningRight = b.controllerIsTurningRight;
        this.controllerIsStrafingLeft = b.controllerIsStrafingLeft;
        this.controllerIsStrafingRight = b.controllerIsStrafingRight;
        this.shipGetFluxTracker = b.shipGetFluxTracker;
        this.fluxGetCurrFlux = b.fluxGetCurrFlux;
        this.fluxGetMaxFlux = b.fluxGetMaxFlux;
        this.fluxGetFluxLevel = b.fluxGetFluxLevel;
        this.fluxGetHardFlux = b.fluxGetHardFlux;
        this.fluxIsOverloaded = b.fluxIsOverloaded;
        this.fluxIsVenting = b.fluxIsVenting;
        this.shipGetShield = b.shipGetShield;
        this.shieldIsOn = b.shieldIsOn;
        this.shieldGetFacing = b.shieldGetFacing;
        this.shipGetAllWeapons = b.shipGetAllWeapons;
        this.weaponGetSpec = b.weaponGetSpec;
        this.weaponSpecIsBeam = b.weaponSpecIsBeam;
        this.weaponSpecGetWeaponId = b.weaponSpecGetWeaponId;
        this.projSpecGetRefireDelay = b.projSpecGetRefireDelay;
        this.projSpecGetBurstSize = b.projSpecGetBurstSize;
        this.projSpecGetBurstDelay = b.projSpecGetBurstDelay;
        this.projectileWeaponSpecApi = b.projectileWeaponSpecApi;
        this.weaponGetId = b.weaponGetId;
        this.weaponGetCooldownRemaining = b.weaponGetCooldownRemaining;
        this.weaponGetCooldown = b.weaponGetCooldown;
        this.weaponIsFiring = b.weaponIsFiring;
        this.weaponUsesAmmo = b.weaponUsesAmmo;
        this.weaponGetAmmo = b.weaponGetAmmo;
        this.weaponGetMaxAmmo = b.weaponGetMaxAmmo;
        this.weaponIsDisabled = b.weaponIsDisabled;
        this.weaponIsForceDisabled = b.weaponIsForceDisabled;
        this.weaponGetChargeLevel = b.weaponGetChargeLevel;
        this.weaponGetFluxCostToFire = b.weaponGetFluxCostToFire;
        this.weaponDistanceFromArc = b.weaponDistanceFromArc;
        this.weaponGetLocation = b.weaponGetLocation;
        this.weaponSetForceFireOneFrame = b.weaponSetForceFireOneFrame;
        this.weaponGetCurrAngle = b.weaponGetCurrAngle;
        this.weaponGetProjectileSpeed = b.weaponGetProjectileSpeed;
        this.shipGetSystem = b.shipGetSystem;
        this.systemGetId = b.systemGetId;
        this.systemGetState = b.systemGetState;
        this.systemIsActive = b.systemIsActive;
        this.systemIsOn = b.systemIsOn;
        this.systemGetCooldownRemaining = b.systemGetCooldownRemaining;
        this.systemGetCooldown = b.systemGetCooldown;
        this.systemGetEffectLevel = b.systemGetEffectLevel;
        this.systemGetAmmo = b.systemGetAmmo;
        this.systemGetMaxAmmo = b.systemGetMaxAmmo;
        this.systemIsOutOfAmmo = b.systemIsOutOfAmmo;
        this.shipGetHullSize = b.shipGetHullSize;
        this.entityGetCollisionRadius = b.entityGetCollisionRadius;
        this.entityGetMass = b.entityGetMass;
        this.shipGetMutableStats = b.shipGetMutableStats;
        this.statsGetMaxSpeed = b.statsGetMaxSpeed;
        this.statsGetAcceleration = b.statsGetAcceleration;
        this.statsGetDeceleration = b.statsGetDeceleration;
        this.statsGetMaxTurnRate = b.statsGetMaxTurnRate;
        this.statsGetTurnAcceleration = b.statsGetTurnAcceleration;
        this.statGetModifiedValue = b.statGetModifiedValue;
        this.statsGetTimeMult = b.statsGetTimeMult;
        this.combatGetProjectiles = b.combatGetProjectiles;
        this.combatGetMissiles = b.combatGetMissiles;
        this.combatGetBeams = b.combatGetBeams;
        this.engineGetWinningSideId = b.engineGetWinningSideId;
        this.engineGetCustomData = b.engineGetCustomData;
        this.engineIsCombatOver = b.engineIsCombatOver;
        this.engineIsPaused = b.engineIsPaused;
        this.engineSetPaused = b.engineSetPaused;
        this.playerShipGiveCommand = b.playerShipGiveCommand;
        this.movementCommandAccelerate = b.movementCommandAccelerate;
        this.movementCommandDecelerate = b.movementCommandDecelerate;
        this.movementCommandStrafeLeft = b.movementCommandStrafeLeft;
        this.movementCommandStrafeRight = b.movementCommandStrafeRight;
        this.movementCommandTurnLeft = b.movementCommandTurnLeft;
        this.movementCommandTurnRight = b.movementCommandTurnRight;
        this.weaponCommandSelectGroup = b.weaponCommandSelectGroup;
        this.weaponCommandFire = b.weaponCommandFire;
        this.weaponCommandHoldFire = b.weaponCommandHoldFire;
        this.weaponCommandVent = b.weaponCommandVent;
        this.shipGetPhaseCloak = b.shipGetPhaseCloak;
        this.shipIsPhased = b.shipIsPhased;
        this.shipGetCurrentCR = b.shipGetCurrentCR;
        this.shipGetSharedFighterReplacementRate = b.shipGetSharedFighterReplacementRate;
        this.shipGetTimeDeployedForCR = b.shipGetTimeDeployedForCR;
        this.statsGetPeakCRDuration = b.statsGetPeakCRDuration;
        this.statBonusComputeEffective = b.statBonusComputeEffective;
        this.hullSpecGetNoCRLossTime = b.hullSpecGetNoCRLossTime;
        this.playerShipUseSystem = b.playerShipUseSystem;
        this.shipSetLoc = b.shipSetLoc;
        this.shipSetVel = b.shipSetVel;
        this.shipSetFacing = b.shipSetFacing;
        this.shipGetWeaponGroupsCopy = b.shipGetWeaponGroupsCopy;
        this.shipGetSelectedGroupAPI = b.shipGetSelectedGroupAPI;
        this.shipGetWeaponGroupFor = b.shipGetWeaponGroupFor;
        this.weaponGroupIsAutofiring = b.weaponGroupIsAutofiring;
        this.weaponGroupToggleOff = b.weaponGroupToggleOff;
        this.vector2fCtor = b.vector2fCtor;
        this.shipSetShipAI = b.shipSetShipAI;
        this.shipGetShipAI = b.shipGetShipAI;
        this.engineIsInFastTimeAdvance = b.engineIsInFastTimeAdvance;
        this.ownedShipIds = (b.ownedShipIds == null || b.ownedShipIds.isEmpty())
                ? Collections.singleton(SINGLE_PLAYER_SHIP_ID)
                : Collections.unmodifiableSet(new LinkedHashSet<>(b.ownedShipIds));
        this.liveShipResolver = b.liveShipResolver;
        this.weaponCommandToggleAutofire = b.weaponCommandToggleAutofire;
        validateCommandObject("ACCELERATE", movementCommandAccelerate);
        validateCommandObject("DECELERATE", movementCommandDecelerate);
        validateCommandObject("STRAFE_LEFT", movementCommandStrafeLeft);
        validateCommandObject("STRAFE_RIGHT", movementCommandStrafeRight);
        validateCommandObject("TURN_LEFT", movementCommandTurnLeft);
        validateCommandObject("TURN_RIGHT", movementCommandTurnRight);
        validateCommandObject("SELECT_GROUP", weaponCommandSelectGroup);
        validateCommandObject("TOGGLE_AUTOFIRE", weaponCommandToggleAutofire);
        validateCommandObject("USE_SELECTED_GROUP", weaponCommandFire);
        validateCommandObject("HOLD_FIRE", weaponCommandHoldFire);
        if (!Float.isFinite(b.timeMult)) {
            throw new IllegalArgumentException("timeMult must be finite; got " + b.timeMult);
        }
        if (!Float.isFinite(b.combatSpeedMult)) {
            throw new IllegalArgumentException("combatSpeedMult must be finite; got " + b.combatSpeedMult);
        }
        this.timeMult = b.timeMult;
        this.combatSpeedMult = b.combatSpeedMult;
        this.fogBound = b.fogBound;
        this.reflection = new EngineReflectionBinding(this, engine, shipClass,
                shipGetId, shipIsAlive, shipGetWeaponGroupsCopy, shipGetSelectedGroupAPI,
                b.groupGetType, b.groupGetActiveWeapon, b.groupGetWeaponsCopy,
                b.groupSetCurrentActive, b.weaponGroupTypeAlternating);
        // The carved fresh-spawn scan takes the
        // list-handle finals by SIMPLE name, after their assignment (javac's definite-assignment check guards it).
        this.freshSpawnScan = new FreshSpawnScan(engine, combatGetProjectiles, combatGetMissiles,
                b.projectileGetElapsed, b.projectileGetWeapon, b.projectileGetVelocity,
                b.projectileGetLocation, b.projectileGetFacing);
        this.pilotCommands = new PilotCommandApplier(this, b); // LAST: reads engine + reflection off this binding
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Boot-thread resolve of the engine's interval-timer slots:
     * called once with the RNG re-arm at arm time, so {@link #restartEngineIntervalTimers} at round 0's frame 0
     * is cache hits only. Returns the slot count. A reflective failure THROWS the pin's own fail-closed
     * exception (a renamed timer API refuses the pinned boot, never a silent {@code timers=-1} round).
     */
    public int prelinkEngineIntervalTimers() {
        try {
            return EngineIntervalTimers.prelink(engine.getClass(), EngineIntervalTimers.ENGINE_TIMER_TYPES);
        } catch (ReflectiveOperationException e) {
            throw new cc.starsector.server.platform.HeadlessCombatContext.HeadlessCombatContextException(
                    "interval timers: the engine's timer slots did not resolve at arm time (" + e.getClass().getSimpleName()
                            + ": " + e.getMessage() + ") — refusing a pinned boot whose frame-0 restart would fail", e);
        }
    }

    /**
     * Restarts the engine's own interval timers (the corner-cheese check's {@code IntervalUtil} and the
     * combat-over check's {@code IntervalTracker}) as a fresh vanilla battle would have them at its start —
     * called by the serve once per pinned round at frame 0, right after the RNG re-arm.
     * Returns the count restarted, {@code -1} on a reflective failure (logged once by
     * {@link EngineIntervalTimers}).
     */
    public int restartEngineIntervalTimers() {
        return EngineIntervalTimers.restart(engine, EngineIntervalTimers.ENGINE_TIMER_TYPES);
    }

    /** Drive one direct inner simulation step with the given (already scaled) amount. */
    public void invokeAdvanceInner(float effectiveAmount) {
        if (!Float.isFinite(effectiveAmount)) {
            throw new IllegalArgumentException("effectiveAmount must be finite; got " + effectiveAmount);
        }
        try {
            advanceInner.invoke(engine, effectiveAmount, stepAmountArg);
            lastEffectiveStepAmount = effectiveAmount;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (isHeadlessRetreatReportNpe(cause)) {
                // B2: when a roster member that is the engine player-seat ship
                // retreats, CombatEngine.reportFleetMemberRetreated -> <listener>.fleetMemberRetreated
                // calls getCombatUI().setPlayerShip(...) on the null headless combat UI -> NPE
                // (the engine's retreat listener runs engine.setPlayerShip(null) BEFORE
                // the null-UI call, so the engine's own player-ship state is already
                // consistent and only the cosmetic UI update is lost). A headless retreat must not
                // kill the serve loop — fail soft and continue (the step's advance already ran up to
                // the retreat-report; B1's deploy-CR fix keeps the demo ships from retreating at all).
                lastEffectiveStepAmount = effectiveAmount;
                if (!headlessRetreatNpeReported) {
                    headlessRetreatNpeReported = true;
                    System.out.println("EngineCombatBinding: tolerated headless retreat-report"
                            + " getCombatUI() NPE (player-seat member retreated; UI update is a no-op"
                            + " headless, engine player ship already cleared) — serve loop continues");
                }
                return;
            }
            throw new EngineBindingException("advanceInner threw: " + describe(cause), cause);
        } catch (ReflectiveOperationException e) {
            throw new EngineBindingException("advanceInner invocation failed: " + describe(e), e);
        }
    }

    /**
     * B2: is {@code cause} the known headless retreat-report boundary — an
     * {@link NullPointerException} thrown from the engine's fleet-member-retreated listener path
     * ({@code CombatFleetManager.retreat -> CombatEngine.reportFleetMemberRetreated ->
     * <listener>.fleetMemberRetreated -> getCombatUI()(null).setPlayerShip})? Scoped tightly to
     * that path (NPE + a retained retreat-report method name on the stack) so every other
     * {@code advanceInner} failure stays fail-closed.
     */
    private static boolean isHeadlessRetreatReportNpe(Throwable cause) {
        if (!(cause instanceof NullPointerException)) {
            return false;
        }
        for (StackTraceElement el : cause.getStackTrace()) {
            String method = el.getMethodName();
            if ("reportFleetMemberRetreated".equals(method) || "fleetMemberRetreated".equals(method)) {
                return true;
            }
        }
        return false;
    }

    /** Drive the input-coupled outer step wrapper after its runtime treatment is installed. */
    public void invokeAdvanceOuter(float fixedAmount) {
        if (advanceOuter == null) {
            throw new IllegalStateException("advance outer wrapper handle is not bound");
        }
        if (!Float.isFinite(fixedAmount)) {
            throw new IllegalArgumentException("fixedAmount must be finite; got " + fixedAmount);
        }
        try {
            advanceOuter.invoke(engine, fixedAmount, stepAmountArg);
            lastEffectiveStepAmount = fixedAmount * timeMult * combatSpeedMult;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new EngineBindingException("advance outer wrapper threw: " + describe(cause), cause);
        } catch (ReflectiveOperationException e) {
            throw new EngineBindingException("advance outer wrapper invocation failed: " + describe(e), e);
        }
    }

    /**
     * Transfer-commit orbit arm, evaluated on the seat's freshly re-keyed body —
     * a non-drone target simply clears any prior arm. See {@link OrbitDroneDrive#armIfEligible}.
     */
    public void armOrbitDroneSeatIfEligible(long seatWireId) {
        orbitDroneDrive.armIfEligible(seatWireId, shipForWireIdOrNull(seatWireId));
    }

    /** Every orbit arm dies with the round; the next commit re-arms. */
    public void clearOrbitDroneArms() {
        orbitDroneDrive.clearArms();
    }

    /**
     * The owned-seat hull-log tables die with the
     * round (called from the serve's {@code wrapRound}, serve thread — the same thread as
     * {@link #logHullDrops}). The body map alone is not enough: with only {@code ownedLastBody}
     * cleared, a next-round redeploy at a LOWER hull (a different /ship variant) would fall into
     * {@code observeHull} against the stale baseline and mint a FAKE hulldelta into the
     * audit-dmg-delta seat sum; clearing the hull/armor baselines + the reseed-log tick makes the
     * first post-wrap read the {@code type=reseed prev=null} first seed instead (and releases the
     * outgoing round's engine bodies from the identity map).
     */
    public void clearOwnedBodyRound() {
        ownedLastBody.clear();
        ownedHullBaseline.clear();
        ownedArmorBaseline.clear();
        lastReseedLogTick.clear();
        // The ROSTER (all-ship dmg-taken) twins die
        // with the round too — a wire entity id re-used next round (152022 ship=44: a hound's 8000
        // baseline met a shard at 660 = a FAKE hulldelta dealt=7340) now takes the prev=null first seed.
        rosterHullBaseline.clear();
        rosterArmorBaseline.clear();
        rosterReseedLogTick.clear();
    }

    /**
     * Engine pause-state read ({@code CombatEngineAPI.isPaused()}): {@code TRUE}/{@code FALSE}
     * when the handle is bound and returns a Boolean, else {@code null} (fail-soft) when the
     * handle is unbound or the read fails. Pure read, never throws — this is the VERIFY-FIRST
     * diagnostic the serve path uses to confirm a fresh {@code CombatEngine} advances on
     * {@code advanceInner}'s PAUSED branch (drains pending-adds → ships present, but skips the
     * ship-advance/AI/physics pass → static ships).
     */
    public Boolean isPausedOrNull() {
        if (engineIsPaused == null) {
            return null;
        }
        try {
            return asBoolean(engineIsPaused.invoke(engine));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Drive the engine's OWN pause flag via {@code CombatEngineAPI.setPaused(false)} once, so the
     * next {@code advanceInner} runs the full unpaused ship-advance/AI/physics pass instead of the
     * paused branch (which only drains pending-adds, leaving served ships static). This
     * is the faithful unpause the proven scout loop does before stepping
     * ({@code run-headless-bootstrap-scout.ps1:44642}), NOT a server-side sim reimplementation.
     * Returns whether the {@code setPaused} handle was bound and applied; fails closed
     * ({@link EngineBindingException}) on a reflective failure so a half-applied state can never be
     * mistaken for success.
     */
    public boolean unpause() {
        if (engineSetPaused == null) {
            return false;
        }
        try {
            engineSetPaused.invoke(engine, Boolean.FALSE);
            return true;
        } catch (InvocationTargetException e) {
            throw new EngineBindingException(
                    "CombatEngine.setPaused threw", e.getCause() != null ? e.getCause() : e);
        } catch (ReflectiveOperationException e) {
            throw new EngineBindingException("CombatEngine.setPaused invocation failed", e);
        }
    }

    /** Number of ships in the authoritative object repository. */
    public int shipListSize() {
        return shipList().size();
    }

    /** Number of bound per-side fleet managers. */
    public int fleetManagerCount() {
        Object managers = invoke(getFleetManagers, engine, "getFleetManagers");
        return asList(managers, "fleetManagers").size();
    }

    /** True when a combat space (map) is bound on the engine. */
    public boolean combatMapBound() {
        return invoke(getCombatMap, engine, "getCombatMap") != null;
    }

    /** True when the engine's player-ship reference is bound. */
    public boolean playerShipBound() {
        return invoke(getPlayerShip, engine, "getPlayerShip") != null;
    }

    /** True when fog-of-war was asserted bound by the caller (proven setup). */
    public boolean fogBound() {
        return fogBound;
    }

    public float timeMult() {
        return timeMult;
    }

    public float combatSpeedMult() {
        return combatSpeedMult;
    }

    /**
     * Player-ship location as {@code [x, y]}, or {@code null} when no location
     * handle was supplied or the read failed. Never throws: the state hash treats
     * a missing location as a graceful counts-only fallback.
     */
    public float[] playerShipLocationOrNull() {
        if (playerShipGetLocation == null) {
            return null;
        }
        try {
            Object ship = getPlayerShip.invoke(engine);
            if (ship == null) {
                return null;
            }
            Object loc = playerShipGetLocation.invoke(ship);
            return readXY(loc);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Player-ship motion at the most recent post-step snapshot point, or
     * {@code null} when the required handles are not bound or a read fails.
     */
    public ShipMotionView playerShipMotionOrNull() {
        if (playerShipGetLocation == null
                || playerShipGetVelocity == null
                || playerShipGetFacing == null
                || playerShipGetAngularVelocity == null
                || !Float.isFinite(lastEffectiveStepAmount)) {
            return null;
        }
        try {
            Object ship = getPlayerShip.invoke(engine);
            if (ship == null) {
                return null;
            }
            float[] loc = readXY(playerShipGetLocation.invoke(ship));
            float[] vel = readXY(playerShipGetVelocity.invoke(ship));
            if (loc == null || vel == null) {
                return null;
            }
            float facing = numberAsFloat(playerShipGetFacing.invoke(ship), "getFacing");
            float angularVelocity = numberAsFloat(
                    playerShipGetAngularVelocity.invoke(ship),
                    "getAngularVelocity");
            return new EngineBackedShipMotionView(
                    SINGLE_PLAYER_SHIP_ID,
                    loc[0],
                    loc[1],
                    vel[0],
                    vel[1],
                    facing,
                    angularVelocity,
                    lastEffectiveStepAmount,
                    shipTimeMultOrConstant(ship));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Motion snapshots for every installed ship, in repository order, or
     * {@code null} when the required handles are not bound or any ship read fails.
     */
    public List<ShipMotionView> shipMotionsOrNull() {
        if (playerShipGetLocation == null
                || playerShipGetVelocity == null
                || playerShipGetFacing == null
                || playerShipGetAngularVelocity == null
                || !Float.isFinite(lastEffectiveStepAmount)) {
            return null;
        }
        int repoSize = -1;
        int shipIdx = -1;
        try {
            List<?> ships = rosterShipList();
            repoSize = ships.size();
            List<ShipMotionView> motions = new ArrayList<>(ships.size());
            long shipId = 1L;
            for (Object ship : ships) {
                shipIdx++;
                if (ship == null) {
                    reportMotionLaneNull("null ship entry", shipIdx, repoSize, null);
                    return null;
                }
                float[] loc = readXY(playerShipGetLocation.invoke(ship));
                float[] vel = readXY(playerShipGetVelocity.invoke(ship));
                if (loc == null || vel == null) {
                    reportMotionLaneNull(loc == null ? "null getLocation" : "null getVelocity",
                            shipIdx, repoSize, null);
                    return null;
                }
                float facing = numberAsFloat(playerShipGetFacing.invoke(ship), "getFacing");
                float angularVelocity = numberAsFloat(
                        playerShipGetAngularVelocity.invoke(ship),
                        "getAngularVelocity");
                motions.add(new EngineBackedShipMotionView(
                        shipId++,
                        loc[0],
                        loc[1],
                        vel[0],
                        vel[1],
                        facing,
                        angularVelocity,
                        lastEffectiveStepAmount,
                        shipTimeMultOrConstant(ship)));
            }
            return motions;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reportMotionLaneNull("read threw", shipIdx, repoSize, e);
            return null;
        }
    }

    /** one-shot cause line for the motion lane's fail-closed null. */
    private void reportMotionLaneNull(String what, int shipIdx, int repoSize, Throwable cause) {
        if (motionLaneNullReported) {
            return;
        }
        motionLaneNullReported = true;
        System.out.println("engine-combat-binding motion-lane null (one-shot): " + what
                + " shipIdx=" + shipIdx + " repoSize=" + repoSize
                + (cause != null ? " cause=" + describe(cause) : ""));
    }

    /**
     * Cause receipt for the sibling roster walks' fail-closed nulls
     * (durability/identity/lifecycle/flux — the motion lane has its own (3a) receipt).
     * The crash proved the silent swallow makes the real cause structurally
     * invisible: one failing entry nulls the whole walk and the stable-roster consumer
     * fails closed on the null with no record of WHICH entry or WHAT threw. One line per
     * distinct (walk, site) per JVM — a receipt, never a flood; reflective exceptions are
     * unwrapped to their target cause.
     */
    private static final java.util.Set<String> WALK_NULL_SITES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void reportWalkNull(
            String walk, String what, int shipIdx, Object entry, String stableId, Throwable cause) {
        if (!WALK_NULL_SITES.add(walk + " " + what)) {
            return;
        }
        Throwable unwrapped =
                (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null)
                        ? cause.getCause() : cause;
        System.out.println("engine-combat-binding " + walk + "-walk null (one-shot): " + what
                + " shipIdx=" + shipIdx
                + " entryClass=" + (entry == null ? "-" : entry.getClass().getName())
                + " id=" + (stableId == null ? "-" : stableId)
                + (unwrapped != null ? " cause=" + describe(unwrapped) : ""));
    }

    /**
     * The LIVE per-ship time multiplier —
     * {@code ship.getMutableStats().getTimeMult().getModifiedValue()} — for the motion view's
     * {@code timeMult()} slot, falling back to the constructor constant when the handles are
     * unbound or the read fails. An unphased ship's stat is the unmodified base 1.0f,
     * bit-identical to the constant, so the state hash
     * ({@code EngineBackedCombatStateHashBoundary} motion slot) is unchanged at mult==1 and
     * becomes phase-visible (~3.0 under a phase cloak's {@code shipTimeMult}) exactly when a
     * ship's local clock diverges. Deliberately per-SHIP stats — NOT the engine-global
     * {@code CombatEngine.getTimeMult()} and NOT the world-state/step-size constant, which stay
     * pinned at 1 (shared-time-contract).
     */
    private float shipTimeMultOrConstant(Object ship) {
        if (shipGetMutableStats == null || statsGetTimeMult == null || statGetModifiedValue == null) {
            return timeMult;
        }
        try {
            Object stats = shipGetMutableStats.invoke(ship);
            if (stats == null) {
                return timeMult;
            }
            Object stat = statsGetTimeMult.invoke(stats);
            if (stat == null) {
                return timeMult;
            }
            Object modified = statGetModifiedValue.invoke(stat);
            if (modified instanceof Number) {
                float tm = ((Number) modified).floatValue();
                if (Float.isFinite(tm) && tm > 0f) {
                    return tm;
                }
            }
            return timeMult;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return timeMult;
        }
    }

    /**
     * A diagnostic (default-OFF; gated by {@code -Dcc.server.crReadback=true} on the
     * step boundary): log each live engine ship's combat-readiness for the given {@code phase}.
     *
     * <p>Measures whether the deploy-time CR (set to 0.7 on the deployed ShipAPI right
     * after spawn) survives the first {@code advanceInner}'s {@code executeAdds} drain, or is
     * re-derived back toward 0 off the ship's underlying FleetMember (an earlier measurement found a bare,
     * fleet-less member at CR 0.0 / maxCR 0.2) — the suspected cause of the immediate
     * retreat. Logs the ship's own {@code getCurrentCR()} and, fail-soft, the member's
     * {@code getFleetMember().getRepairTracker().getCR()/getMaxCR()}. Pure reads, no mutation; a
     * read failure prints a marker rather than throwing so it never perturbs the served frame.
     */
    public void logCombatReadinessReadback(String phase, long simTick) {
        try {
            Method getCurrentCR = shipClass.getMethod("getCurrentCR");
            Method shipGetFleetMember;
            try {
                shipGetFleetMember = shipClass.getMethod("getFleetMember");
            } catch (NoSuchMethodException e) {
                shipGetFleetMember = null;
            }
            // controlsLocked() is the retreat trigger (CombatTaskManager hands a locked ship a
            // RETREAT order). Expect false after the faithful crew fix; was true on the bare member.
            Method shipControlsLocked;
            try {
                shipControlsLocked = shipClass.getMethod("controlsLocked");
            } catch (NoSuchMethodException e) {
                shipControlsLocked = null;
            }
            List<?> ships = shipList();
            StringBuilder sb = new StringBuilder("[cc-cr-readback] phase=").append(phase)
                    .append(" tick=").append(simTick).append(" ships=").append(ships.size());
            int idx = 0;
            for (Object ship : ships) {
                float shipCr = ship == null ? Float.NaN
                        : ((Number) getCurrentCR.invoke(ship)).floatValue();
                String locked = "n/a";
                if (ship != null && shipControlsLocked != null) {
                    try {
                        locked = String.valueOf(shipControlsLocked.invoke(ship));
                    } catch (ReflectiveOperationException | RuntimeException lockEx) {
                        locked = "err:" + lockEx.getClass().getSimpleName();
                    }
                }
                String memberCr = "n/a";
                String memberMaxCr = "n/a";
                String crewFrac = "n/a";
                String canDeploy = "n/a";
                if (ship != null && shipGetFleetMember != null) {
                    try {
                        Object member = shipGetFleetMember.invoke(ship);
                        if (member != null) {
                            Object tracker = member.getClass()
                                    .getMethod("getRepairTracker").invoke(member);
                            if (tracker != null) {
                                memberCr = String.valueOf(((Number) tracker.getClass()
                                        .getMethod("getCR").invoke(tracker)).floatValue());
                                memberMaxCr = String.valueOf(((Number) tracker.getClass()
                                        .getMethod("getMaxCR").invoke(tracker)).floatValue());
                            }
                            crewFrac = String.valueOf(((Number) member.getClass()
                                    .getMethod("getCrewFraction").invoke(member)).floatValue());
                            canDeploy = String.valueOf(member.getClass()
                                    .getMethod("canBeDeployedForCombat").invoke(member));
                        }
                    } catch (ReflectiveOperationException | RuntimeException memberEx) {
                        memberCr = "err:" + memberEx.getClass().getSimpleName();
                    }
                }
                sb.append(" | i=").append(idx++).append(" shipCR=").append(shipCr)
                        .append(" controlsLocked=").append(locked)
                        .append(" crewFrac=").append(crewFrac)
                        .append(" canDeploy=").append(canDeploy)
                        .append(" memberCR=").append(memberCr)
                        .append(" memberMaxCR=").append(memberMaxCr);
            }
            System.out.println(sb);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[cc-cr-readback] phase=" + phase + " tick=" + simTick
                    + " FAILED: " + e);
        }
    }

    /**
     * Diagnostic: dump the engine repository {@code Ship.class} list — identity
     * ({@code getId}/{@code getOwner}/{@code getHullSpec().getHullId()}/{@code isAlive}) plus
     * the {@code ShipAPI} classification predicates ({@code isFighter}/{@code isDrone}/
     * {@code isShuttlePod}/{@code isHulk}/{@code isPiece}/{@code isStationModule}/
     * {@code getParentStation}/{@code getHullSize}) — so the "stable-roster size out of bounds"
     * <em>growth</em> can be characterized: what the extra Ship-type entities actually are
     * (a spawned drone/fighter, a shuttle pod, a detached module, a derelict hulk, or a ship
     * piece) before deciding filter-vs-widen. Pure reads, fail-soft per field; never throws,
     * so it cannot perturb the served frame nor mask the bound condition it diagnoses. Intended
     * as a one-shot call right before the snapshot source's roster-bound throw.
     */
    public void logRosterDump(String phase, long simTick) {
        try {
            List<?> ships = shipList();
            StringBuilder sb = new StringBuilder("[cc-roster-dump] phase=").append(phase)
                    .append(" tick=").append(simTick).append(" count=").append(ships.size());
            int idx = 0;
            for (Object ship : ships) {
                sb.append(" | i=").append(idx++);
                if (ship == null) {
                    sb.append(" <null>");
                    continue;
                }
                sb.append(" id=").append(readShipField(ship, "getId"))
                        .append(" owner=").append(readShipField(ship, "getOwner"))
                        .append(" hull=").append(readShipHullId(ship))
                        .append(" alive=").append(readShipField(ship, "isAlive"))
                        .append(" size=").append(readShipField(ship, "getHullSize"))
                        .append(" fighter=").append(readShipField(ship, "isFighter"))
                        .append(" drone=").append(readShipField(ship, "isDrone"))
                        .append(" shuttlePod=").append(readShipField(ship, "isShuttlePod"))
                        .append(" hulk=").append(readShipField(ship, "isHulk"))
                        .append(" piece=").append(readShipField(ship, "isPiece"))
                        .append(" stationModule=").append(readShipField(ship, "isStationModule"))
                        .append(" parentStation=").append(readShipParentStation(ship));
            }
            System.out.println(sb);
        } catch (RuntimeException e) {
            System.out.println("[cc-roster-dump] phase=" + phase + " tick=" + simTick
                    + " FAILED: " + e);
        }
    }

    /**
     *
     * One-line remnant census over the engine repository {@code Ship.class} list — total
     * plus alive/hulk/piece counts — emitted on a fixed simTick cadence by the snapshot
     * source, so unfading-wreck growth (each kill leaves ≥1 dead remnant the engine keeps
     * forever) is measurable over a long serve instead of only inferable from the deaths
     * timeline. Pure reads, fail-soft per field ({@code logRosterDump} discipline): never
     * throws, so it cannot perturb the served frame.
     */
    public void logRemnantCensus(long simTick) {
        try {
            List<?> ships = shipList();
            int alive = 0;
            int hulks = 0;
            int pieces = 0;
            for (Object ship : ships) {
                if (ship == null) {
                    continue;
                }
                if ("true".equals(readShipField(ship, "isAlive"))) {
                    alive++;
                }
                if ("true".equals(readShipField(ship, "isHulk"))) {
                    hulks++;
                }
                if ("true".equals(readShipField(ship, "isPiece"))) {
                    pieces++;
                }
            }
            System.out.println("[cc-remnant-census] tick=" + simTick + " repo=" + ships.size()
                    + " alive=" + alive + " hulk=" + hulks + " piece=" + pieces);
        } catch (RuntimeException e) {
            System.out.println("[cc-remnant-census] tick=" + simTick + " FAILED: " + e);
        }
    }

    /**
     * Per-tick remnant sightings for the hulk-fracture detector — delegate to
     * {@link EngineReflectionBinding#remnantSightingsOrNull()} (the extracted body);
     * contract unchanged (whole-read fail-soft null on ANY reflective failure).
     */
    public List<cc.starsector.server.runtime.HulkFractureDetector.RemnantSighting>
            remnantSightingsOrNull() {
        return reflection.remnantSightingsOrNull();
    }

    /**
     * One remnant's {exact, visual} bound rings for the cut-geometry capture — delegate
     * to {@link EngineReflectionBinding#hulkRingsOrNull(Object)} (whole-read fail-soft null).
     */
    public float[][] hulkRingsOrNull(Object ship) {
        return reflection.hulkRingsOrNull(ship);
    }

    /**
     * Round-arm corpse-read warm — delegate to
     * {@link EngineReflectionBinding#warmRemnantReadPaths()} (read-only by the static-RE
     * verdict; fail-soft receipt string, never throws).
     */
    public String warmRemnantReadPaths() {
        return reflection.warmRemnantReadPaths();
    }

    /**
     * Pure-JVM capture-lane warm — pre-resolves the concrete
     * point-entity classes the per-tick capture discriminates on, against the live engine's
     * classloader, plus the accessor set the lane invokes per element
     * ({@link #readPointEntities} + {@link #firingWeaponContentKey} + the MIRV/pose probes),
     * so the FIRST live sighting of a class pays no mid-frame classload +
     * first-{@code getMethod}-resolution bill (measured: first {@code DamagingExplosion} =
     * the run's only mid-combat >100 ms stall; {@code CustomCombatEntity} was still
     * never-seen). Covers classload + Method resolution only — the JIT receiver-profile
     * residual needs a live instance and stays accept-once. Class names appear in
     * the receipt only (same disclosure class as the {@code content-key-fallback miss}
     * receipt, which already prints {@code entityClass.getName()}); nothing crosses the
     * wire. Fail-soft per class and per accessor: a missing accessor is counted, a failed
     * classload is named, nothing throws into the serve.
     */
    public String warmCaptureLaneClasses() {
        if (engine == null) {
            return "no_engine";
        }
        ClassLoader cl = engine.getClass().getClassLoader();
        String[] classes = { "BallisticProjectile", "PlasmaShot", "Missile",
                "DamagingExplosion", "CustomCombatEntity", "BeamWeaponRay" };
        String[] accessors = { "getLocation", "getOwner", "getProjectileSpecId", "getWeapon",
                "isFromMissile", "getElapsed", "isMirv", "getSource", "getFacing", "isFizzling",
                "getExplosionSpecIfExplosion" };
        long start = System.nanoTime();
        int classesLoaded = 0;
        int methodsResolved = 0;
        int methodsMissing = 0;
        StringBuilder failed = new StringBuilder();
        for (String simpleName : classes) {
            try {
                Class<?> entityClass = Class.forName(
                        "com.fs.starfarer.combat.entities." + simpleName, true, cl);
                classesLoaded++;
                for (String accessor : accessors) {
                    try {
                        entityClass.getMethod(accessor);
                        methodsResolved++;
                    } catch (NoSuchMethodException absent) {
                        methodsMissing++;
                    }
                }
            } catch (Throwable loadFailure) {
                if (failed.length() > 0) {
                    failed.append(',');
                }
                failed.append(simpleName);
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000L;
        return "classes=" + classesLoaded + "/" + classes.length
                + " methods=" + methodsResolved + " miss=" + methodsMissing
                + (failed.length() == 0 ? "" : " failed=" + failed)
                + " ms=" + ms;
    }

    private String readShipField(Object ship, String method) {
        try {
            return String.valueOf(shipClass.getMethod(method).invoke(ship));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "err:" + e.getClass().getSimpleName();
        }
    }

    private String readShipHullId(Object ship) {
        try {
            Object spec = shipClass.getMethod("getHullSpec").invoke(ship);
            if (spec == null) {
                return "null";
            }
            return String.valueOf(spec.getClass().getMethod("getHullId").invoke(spec));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "err:" + e.getClass().getSimpleName();
        }
    }

    private String readShipParentStation(Object ship) {
        try {
            return shipClass.getMethod("getParentStation").invoke(ship) == null ? "null" : "present";
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "err:" + e.getClass().getSimpleName();
        }
    }

    /**
     * Server-side parity-audit walk (the scout-faithful pivot — the "do FIRST" instrument): the
     * faithful port of the scout gate's per-tick server walk
     * ({@code run-headless-bootstrap-scout.ps1:41106-41112},
     * {@code ParityAuditWalker.walkTick("server", i, engine, keys, ships)}) into the product serve loop, so
     * {@link cc.starsector.server.runtime.PersistentPilotIntentCombatServer} finally logs the SAME
     * server-side desync-attribution surface the feel-confirmed scout did. One run's velocity desync
     * was un-attributable precisely because this server half was missing (the client carries the full parity
     * machinery; the server logged ≈0). Without it a render-WATCH can show drift but never say whether the
     * client or the server is wrong.
     *
     * <p>Each LIVE engine ship is walked keyed by its network {@code entityId}, resolved EXACTLY as the
     * published {@link EngineBackedLiveWorldSnapshotSource} resolves it
     * ({@code registry.networkIdFor(getId())}, the {@code !alive} skip), so the client half
     * ({@code cc.starsector.client.ClientParityWalker}, keyed by AWSS entityId) joins the same ships on
     * {@code (tick, subject, key, field)}. The lookup uses
     * {@link ShipNetworkIdRegistry#networkIdForOrNull} (NOT {@code networkIdFor}): the instrument is strictly
     * READ-only and never assigns an id — the snapshot build for this tick already interned every live ship,
     * so a ship missing from the registry is simply skipped rather than minting a new entityId that would
     * diverge from the wire. Reflection-only and fail-soft end to end ({@link ParityAuditWalker#walkTick}
     * swallows every throwable, and this method's own pairing loop is guarded), so it can never break the
     * serve loop. No game identity crosses: {@code ParityAuditWalker} resolves the public-API getters via
     * each ship's own classloader. Caller gates cadence ({@code parity.walkEvery}); a {@code null} registry
     * or unbound id/liveness handles make this a no-op.
     */
    public void walkParityRoster(String side, long tick, ShipNetworkIdRegistry stableIdRegistry) {
        if (stableIdRegistry == null || shipGetId == null || shipIsAlive == null) {
            return;
        }
        try {
            List<?> ships = shipList();
            List<Object> live = new ArrayList<>(ships.size());
            List<String> keys = new ArrayList<>(ships.size());
            for (Object ship : ships) {
                if (ship == null) {
                    continue;
                }
                Object aliveValue = shipIsAlive.invoke(ship);
                if (!(aliveValue instanceof Boolean) || !((Boolean) aliveValue)) {
                    continue; // !alive ships are dropped from the AWSS too — keep keying identical
                }
                Object idValue = shipGetId.invoke(ship);
                if (!(idValue instanceof String)) {
                    continue;
                }
                String stableKey = ((String) idValue).trim();
                if (stableKey.isEmpty()) {
                    continue;
                }
                Long entityId = stableIdRegistry.networkIdForOrNull(stableKey);
                if (entityId == null) {
                    continue; // not yet interned by the published snapshot: skip rather than assign
                }
                live.add(ship);
                keys.add(Long.toString(entityId));
            }
            ParityAuditWalker.walkTick(side, tick, engine,
                    keys.toArray(new String[0]), live.toArray());
        } catch (ReflectiveOperationException | RuntimeException e) {
            // fail-soft: the parity instrument must never break the serve loop
        }
    }

    /**
     * MIRROR: one-call side-totals capture — hands the private {@code engine} to the
     * static fail-soft helper (the {@link #walkParityRoster} delegate idiom). Null means the capture
     * was unavailable this tick (unresolved handles / a read failed) and the caller holds its last
     * block; never throws.
     */
    public cc.starsector.server.runtime.AwsdSnapshotCodec.SideTotalsBlock captureSideTotalsOrNull() {
        return SideTotalsCapture.captureOrNull(engine);
    }

    /** side-1 pool rollup cadence (10 s at 60 Hz; sparse, greppable). */
    private static final long STRENGTH_POOL_EVERY_TICKS = 600L;

    /**
     * D-FABRATE total-count check: one {@code cc-fab-dp} row every
     * {@link #STRENGTH_POOL_EVERY_TICKS} ticks with side 1's deployment-point pool
     * ({@link SideTotalsCapture#strengthPoolOrNull}: max/curr/dpLeft). Vanilla stops a Threat
     * fabricator at {@code dpLeft < 100}; the server never sets the pool, so this row is the
     * measurement — {@code max=100} = the engine's field default (a starved pool), a large or
     * growing max = unbounded. Two public reads per emit, post-step; null read = silent.
     */
    public void strengthPoolRollup(long simTick) {
        if (simTick % STRENGTH_POOL_EVERY_TICKS != 0L) {
            return;
        }
        int[] pool = SideTotalsCapture.strengthPoolOrNull(engine, 1);
        if (pool != null) {
            System.out.println("cc-fab-dp side=1 max=" + pool[0] + " curr=" + pool[1]
                    + " dpLeft=" + (pool[0] - pool[1]) + " tick=" + simTick);
        }
    }

    /** D-FOEARMOR B0: hash-gated quantized-cell retention beside the grid
     *  walk. ECB is past its WARN tier, so the logic lives in the collaborator — ECB pays
     *  only this field + one call inside {@link #armorGridSnapshot}. */
    private final ArmorCellRetainer armorCellRetainer = new ArmorCellRetainer();

    /**
     * Durability snapshots for every installed ship, in repository order, or
     * {@code null} when the required handles are not bound or any ship read fails.
     */
    public List<ShipDurabilityView> shipDurabilitiesOrNull() {
        if (shipGetHitpoints == null
                || shipGetHullLevel == null
                || shipGetArmorGrid == null
                || armorGridGetGrid == null
                || armorGridGetMaxArmorInCell == null) {
            return null;
        }
        int shipIdx = -1;
        Object entry = null;
        try {
            List<?> ships = rosterShipList();
            List<ShipDurabilityView> durabilities = new ArrayList<>(ships.size());
            long shipId = 1L;
            for (Object ship : ships) {
                shipIdx++;
                entry = ship;
                if (ship == null) {
                    reportWalkNull("durability", "null ship entry", shipIdx, null, null, null);
                    return null;
                }
                float hitpoints = numberAsFloat(shipGetHitpoints.invoke(ship), "getHitpoints");
                float hullLevel = numberAsFloat(shipGetHullLevel.invoke(ship), "getHullLevel");
                ArmorGridSnapshot armor = armorGridSnapshot(shipGetArmorGrid.invoke(ship), shipId);
                if (armor == null) {
                    reportWalkNull("durability", "null armor grid snapshot", shipIdx, ship, null, null);
                    return null;
                }
                durabilities.add(new EngineBackedShipDurabilityView(
                        shipId++,
                        hitpoints,
                        hullLevel,
                        armor.level,
                        armor.cellCount,
                        armor.total,
                        armor.hash,
                        armor.cells));
            }
            return durabilities;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reportWalkNull("durability", "read threw", shipIdx, entry, null, e);
            return null;
        }
    }

    /** Last observed hull per human-seat wire id, the own-damage-taken
     *  baseline. A hull RISE (fresh round deploy, heal) re-seeds silently — no reset hook needed:
     *  round boundaries redeploy at full hull, so the first post-boundary read always rises. */
    private final Map<Long, Float> ownedHullBaseline = new HashMap<>();
    /** The engine body each seat wire id resolved to
     *  last pass, compared by IDENTITY ({@code !=}) — a different object = re-body = baseline re-seed. */
    private final Map<Long, Object> ownedLastBody = new HashMap<>();

    /** Sustained hull REGEN steps hull every tick, so an
     *  ungated reseed line is ~12k rows/run. Rate-limit the LINE per entity; the baseline mint
     *  above stays unconditional. First-seed (prev==null) always prints; a respawn rise prints
     *  because no rise can occur while dead (dead ship skips the loop), so the window has lapsed. */
    private static final long RESEED_LOG_MIN_TICKS = 600;
    private final Map<Long, Long> lastReseedLogTick = new HashMap<>();

    /** All-ship lane baselines — keyed by the wire ENTITY id (registry), a separate space
     *  from the seat wire ids above (under compaction a seat wire id and a repo ordinal can collide). */
    private final Map<Long, Float> rosterHullBaseline = new HashMap<>();
    private final Map<Long, Long> rosterReseedLogTick = new HashMap<>();
    /** Per-INSTANCE last compact stat-sig (first sight / CHANGED prints the verbose line);
     *  identity-keyed so a respawn (new engine object) re-prints; cleared past 2048 (wave churn). */
    private final Map<Object, String> lastSigByShip = new IdentityHashMap<>();
    private static final int TSTAT_LINE_MAX = 600;
    private int tstatLines;

    /** per-hit instrument: prev-tick armor POOL per id (the {@code armorPre=} source +
     *  the {@code armordelta} drop gate — armor/shield-only hits were invisible server-side).
     *  Two id spaces mirroring the hull baselines; rises re-seed silently (armor-repair class). */
    private final Map<Long, Float> ownedArmorBaseline = new HashMap<>();
    private final Map<Long, Float> rosterArmorBaseline = new HashMap<>();
    /** Pool sums are deterministic same-order walks — the eps only guards true epsilon churn. */
    private static final float ARMOR_DELTA_EPS = 0.01f;

    /** Dealt-side sig census (the skills-hypothesis test): per-instance last
     *  compact {@link ShipDealtSig}; dealt-side changes don't correlate with being HIT, so a
     *  600-tick recheck lattice per ship replaces the taken-sig's drop-time cadence (bounds
     *  reflection cost; catches the post-spawn-writer class D-DMGDIV proved real). */
    private final Map<Object, String> lastDsigByShip = new IdentityHashMap<>();
    private final Map<Long, Long> lastDsigCheckTick = new HashMap<>();
    private static final long DSIG_RECHECK_TICKS = 600;
    private static final int DSIG_LINE_MAX = 600;
    private int dsigLines;

    /**
     * The own-damage-taken server log, later widened to EVERY roster
     * ship: per-tick hull observation, one line per ship per tick on a hull DROP — the server-truth
     * record a watch debrief reads against the client's DamageWitness dmg-apply lane. Two prefixes,
     * ONE law: human seats ({@code ownedShipIds}, resolver-authoritative) keep {@code pilot-dmg-taken
     * ship=<wireId>} (the {@code audit-dmg-delta.ps1} seat sum — never polluted); every OTHER alive
     * roster ship prints {@code dmg-taken ship=<entityId>} keyed by the SAME stable-id registry
     * entity id the wire carries (the parity walk's keying), so a debrief joins server rows to the
     * client's {@code victim=} ids. Every hulldelta row carries {@code tsig{}} (the
     * {@link ShipStatSig#compact} damage-taken signature = the D-DMGDIV "fixed" gate); the first
     * sight of a ship INSTANCE and any sig change at hit time print one {@code dmg-tstat} verbose line
     * (the client's {@code dmg-tstat first|CHANGED} law). Per-tick delta, not an engine listener:
     * the server registers no engine listeners and the global damage bus is unverified headless.
     * Fail-soft per ship — an unbound handle or throwing read skips the ship, never the serve loop.
     * No knob: rows are drop-gated (debrief-eye volume); the reseed line stays rate-limited.
     */
    public void logHullDrops(long simTick, ShipNetworkIdRegistry stableIdRegistry) {
        logHullDrops(simTick, stableIdRegistry, null);
    }

    /**
     * The same pass with the {@link HullSourceLedger} the arc-hit
     * probe feeds from the engine's {@code DamageListener} callback — every hulldelta/armordelta
     * row gains {@code src=} (see the ledger's grammar); {@code null} = the pre-R4 rows byte-identical.
     * The ledger's window closes at the end of THIS pass whatever branch returned (the diff window
     * and the report window stay one and the same).
     */
    public void logHullDrops(long simTick, ShipNetworkIdRegistry stableIdRegistry,
            HullSourceLedger hullSources) {
        try {
            logHullDropsPass(simTick, stableIdRegistry, hullSources);
        } finally {
            if (hullSources != null) {
                hullSources.endTick();
            }
        }
    }

    private void logHullDropsPass(long simTick, ShipNetworkIdRegistry stableIdRegistry,
            HullSourceLedger hullSources) {
        if (shipGetHitpoints == null || shipGetHullLevel == null) {
            return;
        }
        Set<Object> seatShips = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Long wireId : ownedShipIds) {
            try {
                Object ship = shipForWireIdOrNull(wireId);
                if (ship == null) {
                    continue;
                }
                seatShips.add(ship);
                Object lastBody = ownedLastBody.get(wireId);
                if (lastBody != null && lastBody != ship) {
                    // The wire id resolves to ANOTHER body (transfer/respawn/
                    // module seat) — the old baselines would mint a fake hulldelta/armordelta into the
                    // audit-dmg-delta seat sum. Re-seed both from the new body, one row, no delta.
                    float hull = numberAsFloat(shipGetHitpoints.invoke(ship), "getHitpoints");
                    float armorNow = armorPoolOrNaN(ship);
                    System.out.println("pilot-dmg-taken tick=" + simTick + " ship=" + wireId
                            + " type=reseed prev=" + ownedHullBaseline.get(wireId) + " hull=" + hull
                            + " armorPrev=" + ownedArmorBaseline.get(wireId) + " armor=" + armorNow
                            + " reason=rebody");
                    ownedHullBaseline.put(wireId, hull);
                    if (Float.isNaN(armorNow)) {
                        ownedArmorBaseline.remove(wireId);
                    } else {
                        ownedArmorBaseline.put(wireId, armorNow);
                    }
                    lastReseedLogTick.put(wireId, simTick);
                } else {
                    observeHull(ship, wireId, "pilot-dmg-taken", ownedHullBaseline, ownedArmorBaseline,
                            lastReseedLogTick, simTick, hullSources, stableIdRegistry);
                }
                ownedLastBody.put(wireId, ship); // after the reads: a throwing re-seed retries next pass
                observeDealtSig(ship, wireId, "pilot-dmg-taken", simTick);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // fail-soft: the damage log must never break the serve loop
            }
        }
        if (stableIdRegistry == null || shipGetId == null || shipIsAlive == null) {
            return;
        }
        List<?> roster;
        try {
            roster = rosterShipList();
        } catch (RuntimeException e) {
            return; // fail-soft: the repository read itself failed this tick
        }
        for (Object ship : roster) {
            if (ship == null || seatShips.contains(ship)) {
                continue;
            }
            try {
                Object aliveValue = shipIsAlive.invoke(ship);
                if (!(aliveValue instanceof Boolean) || !((Boolean) aliveValue)) {
                    continue; // !alive ships are dropped from the AWSS too — keep keying identical
                }
                Object idValue = shipGetId.invoke(ship);
                if (!(idValue instanceof String)) {
                    continue;
                }
                String stableKey = ((String) idValue).trim();
                if (stableKey.isEmpty()) {
                    continue;
                }
                Long entityId = stableIdRegistry.networkIdForOrNull(stableKey);
                if (entityId == null) {
                    continue; // not yet interned by the published snapshot: skip rather than assign
                }
                observeHull(ship, entityId, "dmg-taken", rosterHullBaseline, rosterArmorBaseline,
                        rosterReseedLogTick, simTick, hullSources, stableIdRegistry);
                observeDealtSig(ship, entityId, "dmg-taken", simTick);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // fail-soft per ship
            }
        }
    }

    /** ONE ship's hull observation — the shared law behind both prefixes (see {@link #logHullDrops}).
     *  Also retains the prev-tick armor POOL beside the hull baseline — hulldelta rows
     *  gain {@code armorPre=}; a pool drop with NO hull drop mints a {@code type=armordelta} row
     *  (same field law, {@code dealt=} = the pool delta), closing the armor/shield-only blind spot. */
    private void observeHull(Object ship, long id, String prefix, Map<Long, Float> baseline,
            Map<Long, Float> armorBaseline, Map<Long, Long> reseedLogTick, long simTick,
            HullSourceLedger hullSources, ShipNetworkIdRegistry stableIdRegistry)
            throws ReflectiveOperationException {
        float hull = numberAsFloat(shipGetHitpoints.invoke(ship), "getHitpoints");
        Float prev = baseline.get(id);
        float armorNow = armorPoolOrNaN(ship);
        Float prevArmor = armorBaseline.get(id);
        String sig = null;
        if (!lastSigByShip.containsKey(ship)) {
            sig = ShipStatSig.compact(ship);
            printTstat(ship, id, prefix, simTick, "first");
            if (lastSigByShip.size() >= 2048) {
                lastSigByShip.clear();
            }
            lastSigByShip.put(ship, sig);
        }
        if (prev != null && hull < prev) {
            sig = sigWithChangeCheck(ship, id, prefix, simTick, sig);
            System.out.println(prefix + " tick=" + simTick + " ship=" + id
                    + " type=hulldelta dealt=" + (prev - hull) + " hull=" + hull
                    + hullFluxFields(ship) + " tsig{" + sig + "}"
                    + (prevArmor != null ? " armorPre=" + Math.round(prevArmor) : "")
                    + armorPoolFields(ship) // D-OWNARMOR (b) instrument
                    + srcFields(ship, hullSources, stableIdRegistry)); // src= hit-source fields
        } else if (prevArmor != null && !Float.isNaN(armorNow)
                && prevArmor - armorNow >= ARMOR_DELTA_EPS) {
            sig = sigWithChangeCheck(ship, id, prefix, simTick, sig);
            System.out.println(prefix + " tick=" + simTick + " ship=" + id
                    + " type=armordelta dealt=" + (prevArmor - armorNow) + " hull=" + hull
                    + hullFluxFields(ship) + " tsig{" + sig + "}"
                    + " armorPre=" + Math.round(prevArmor)
                    + armorPoolFields(ship)
                    + srcFields(ship, hullSources, stableIdRegistry)); // src= hit-source fields
        } else if (prev != null && hullSources != null && hullSources.reported(ship)) {
            // The listener named a hull/armor-touching source for this ship
            // in the window but NEITHER diff moved — a god-mode seat (hull mult 1e-6, armor mult 0:
            // AdminSeatDebugLane) or a sub-epsilon hit. Without this row a parked god seat under
            // fire prints nothing and a beam at it stays undecidable (the specimen arena's whole
            // point); audit-dmg-delta pre-filters type=hulldelta so its GOD-MODE-LEAK law is
            // untouched. Same field set as the deltas: dealt=0 is the honest diff.
            sig = sigWithChangeCheck(ship, id, prefix, simTick, sig);
            System.out.println(prefix + " tick=" + simTick + " ship=" + id
                    + " type=absorbed dealt=0.0 hull=" + hull
                    + hullFluxFields(ship) + " tsig{" + sig + "}"
                    + (prevArmor != null ? " armorPre=" + Math.round(prevArmor) : "")
                    + armorPoolFields(ship)
                    + srcFields(ship, hullSources, stableIdRegistry));
        } else if (prev != null && hullSources != null && hullSources.shieldReported(ship)) {
            // A shield-touching report named this ship and NOTHING
            // above printed — the shot the v1 arena could not see (shield-absorbed = no diff, no
            // hull report). Knob cc.server.shieldedRow only (the ledger's shield window is empty
            // unarmed); one row per victim per pass by construction; dealt=0 is the honest diff,
            // the shield sum rides as srcShield=. audit-dmg-delta's hulldelta pre-filter is untouched.
            sig = sigWithChangeCheck(ship, id, prefix, simTick, sig);
            System.out.println(prefix + " tick=" + simTick + " ship=" + id
                    + " type=shielded dealt=0.0 hull=" + hull
                    + hullFluxFields(ship) + " tsig{" + sig + "}"
                    + (prevArmor != null ? " armorPre=" + Math.round(prevArmor) : "")
                    + armorPoolFields(ship)
                    + shieldSrcFields(ship, hullSources, stableIdRegistry));
        }
        if (!Float.isNaN(armorNow) && (prevArmor == null || armorNow != prevArmor)) {
            armorBaseline.put(id, armorNow); // rises re-seed silently (armor-repair class)
        }
        if (prev == null || hull != prev) {
            if (prev == null || hull > prev) {
                // The silent re-seed class (first seed / rise) gets one
                // loud line so a debrief can tell "no damage" from "lane blind".
                Long lastLog = reseedLogTick.get(id);
                if (prev == null || lastLog == null
                        || simTick - lastLog >= RESEED_LOG_MIN_TICKS) {
                    System.out.println(prefix + " tick=" + simTick + " ship=" + id
                            + " type=reseed prev=" + prev + " hull=" + hull);
                    reseedLogTick.put(id, simTick);
                }
            }
            baseline.put(id, hull);
        }
    }

    /** The verbose stat-sig line (bounded): {@code dmg-tstat #n tick= ship= lane= first|CHANGED tstat{...}}. */
    private void printTstat(Object ship, long id, String prefix, long simTick, String why) {
        if (tstatLines >= TSTAT_LINE_MAX) {
            return;
        }
        tstatLines++;
        System.out.println("dmg-tstat #" + tstatLines + " tick=" + simTick + " ship=" + id
                + " lane=" + prefix + " inst=" + Integer.toHexString(System.identityHashCode(ship))
                + " " + why + " tstat{" + ShipStatSig.verbose(ship) + "}"
                + (tstatLines == TSTAT_LINE_MAX ? " [line cap reached]" : ""));
    }

    /** Ensure the taken-sig is computed + CHANGED-printed — the drop branches' shared law. */
    private String sigWithChangeCheck(Object ship, long id, String prefix, long simTick, String sig) {
        if (sig == null) {
            sig = ShipStatSig.compact(ship);
            String last = lastSigByShip.get(ship);
            if (last == null || !last.equals(sig)) {
                printTstat(ship, id, prefix, simTick, "CHANGED");
                lastSigByShip.put(ship, sig);
            }
        }
        return sig;
    }

    /** The shared {@code hullFrac=/flux=/hardFlux=} row fragment (hulldelta + armordelta).
     *  <p>The LAW — decorative fields are fail-soft per FIELD, never per ship: the flux
     *  reads print {@code NaN} instead of throwing. WHY: the engine's {@code getFluxLevel()} is
     *  {@code currFlux / maxFlux} with no zero guard,
     *  so every zero-flux hull (the 19 armour/blocker/decor modules of
     *  {@code station3_midline_Standard}, {@code max flux = 0} in {@code ship_data.csv}) returns
     *  0/0 = NaN; the strict {@link #numberAsFloat} threw, the per-ship catch in
     *  {@link #logHullDrops} swallowed it, and {@link #observeHull}'s baseline write sits AFTER
     *  the print — so the throw re-fired every tick and the witness was PERMANENTLY blind to
     *  every fortress armour module (one threat test match: modules 8/16 fell to 50%/9% on the
     *  wire with zero {@code dmg-taken} rows; only the four flux-bearing station members printed). */
    /**
     * The {@code src=…} suffix for one printed hull/armor row — the ledger's window
     * joined on the victim's identity, the source rendered by {@link #sourceLabel}. {@code null}
     * ledger = no field (pre-R4 rows); any throw = {@code src=?} (the row is never lost).
     */
    private String srcFields(Object ship, HullSourceLedger hullSources,
            ShipNetworkIdRegistry stableIdRegistry) {
        if (hullSources == null) {
            return "";
        }
        try {
            return hullSources.describe(ship, source -> sourceLabel(source, stableIdRegistry));
        } catch (RuntimeException failure) {
            return " src=?";
        }
    }

    /** The {@code type=shielded} row's suffix — the ledger's SHIELD window,
     *  same label law as {@link #srcFields}; any throw = {@code src=?} (the row is never lost). */
    private String shieldSrcFields(Object ship, HullSourceLedger hullSources,
            ShipNetworkIdRegistry stableIdRegistry) {
        try {
            return hullSources.describeShield(ship, source -> sourceLabel(source, stableIdRegistry));
        } catch (RuntimeException failure) {
            return " src=?";
        }
    }

    /**
     * The source half of {@code src=}: a seat's wire id (the live resolver map), else the registry
     * entity id the wire carries for a roster ship, else {@code piece} for a hulk clone
     * ({@code Ship.splitShip}), {@code ?} for a ship the wire never named,
     * {@code -} for a null source and the simple class name for a non-ship body (asteroid).
     */
    private String sourceLabel(Object source, ShipNetworkIdRegistry stableIdRegistry) {
        if (source == null) {
            return "-";
        }
        boolean isShip = shipGetId != null && shipGetId.getDeclaringClass().isInstance(source);
        if (!isShip) {
            return source.getClass().getSimpleName();
        }
        for (Long wireId : ownedShipIds) {
            try {
                if (shipForWireIdOrNull(wireId) == source) {
                    return Long.toString(wireId);
                }
            } catch (RuntimeException ignored) {
                // a seat resolve throw never costs the label
            }
        }
        if (stableIdRegistry != null) {
            try {
                Object idValue = shipGetId.invoke(source);
                if (idValue instanceof String) {
                    Long entityId = stableIdRegistry.networkIdForOrNull(((String) idValue).trim());
                    if (entityId != null) {
                        return Long.toString(entityId);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // fall through to the piece / unknown read
            }
        }
        try {
            Method isPiece = source.getClass().getMethod("isPiece");
            isPiece.setAccessible(true);
            if (Boolean.TRUE.equals(isPiece.invoke(source))) {
                return "piece";
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // no piece seam on this build — unknown stays unknown
        }
        return "?";
    }

    private String hullFluxFields(Object ship) throws ReflectiveOperationException {
        float hullFrac = numberAsFloat(shipGetHullLevel.invoke(ship), "getHullLevel");
        float fluxLevel = Float.NaN;
        float hardFlux = Float.NaN;
        if (shipGetFluxTracker != null && fluxGetFluxLevel != null
                && fluxGetHardFlux != null) {
            Object tracker = shipGetFluxTracker.invoke(ship);
            if (tracker != null) {
                fluxLevel = finiteOrNaN(fluxGetFluxLevel.invoke(tracker));
                hardFlux = finiteOrNaN(fluxGetHardFlux.invoke(tracker));
            }
        }
        return " hullFrac=" + hullFrac + " flux=" + fluxLevel + " hardFlux=" + hardFlux;
    }

    /** {@link #numberAsFloat}'s lenient twin for decorative row fields: a non-Number or
     *  non-finite value reads as {@code NaN} (the row's existing "no tracker" sentinel) instead of
     *  throwing — see {@link #hullFluxFields} for the zero-flux-module blind spot this closes. */
    static float finiteOrNaN(Object value) {
        if (!(value instanceof Number)) {
            return Float.NaN;
        }
        float f = ((Number) value).floatValue();
        return Float.isFinite(f) ? f : Float.NaN;
    }

    /** Total armor pool, or NaN — the cheap per-tick read behind {@code armorPre=}/
     *  {@code armordelta} (two reflective calls + an O(cells) sum; no snapshot alloc/hash). */
    private float armorPoolOrNaN(Object ship) {
        if (shipGetArmorGrid == null || armorGridGetGrid == null) {
            return Float.NaN;
        }
        try {
            Object grid = shipGetArmorGrid.invoke(ship);
            Object cellsObj = grid == null ? null : armorGridGetGrid.invoke(grid);
            if (!(cellsObj instanceof float[][])) {
                return Float.NaN;
            }
            float sum = 0f;
            for (float[] col : (float[][]) cellsObj) {
                if (col == null) {
                    return Float.NaN;
                }
                for (float v : col) {
                    sum += v;
                }
            }
            return sum;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Float.NaN;
        }
    }

    /** Dealt-side census walk for one ship — first sight + the 600-tick recheck
     *  lattice, change-gated {@code dmg-dsig} verbose print (see the field block's rationale). */
    private void observeDealtSig(Object ship, long id, String prefix, long simTick) {
        try {
            boolean first = !lastDsigByShip.containsKey(ship);
            Long lastCheck = lastDsigCheckTick.get(id);
            if (!first && lastCheck != null && simTick - lastCheck < DSIG_RECHECK_TICKS) {
                return;
            }
            lastDsigCheckTick.put(id, simTick);
            String sig = ShipDealtSig.compact(ship);
            String prev = lastDsigByShip.get(ship);
            if (prev != null && prev.equals(sig)) {
                return;
            }
            if (lastDsigByShip.size() >= 2048) {
                lastDsigByShip.clear();
            }
            lastDsigByShip.put(ship, sig);
            if (dsigLines >= DSIG_LINE_MAX) {
                return;
            }
            dsigLines++;
            System.out.println("dmg-dsig #" + dsigLines + " tick=" + simTick + " ship=" + id
                    + " lane=" + prefix + " inst=" + Integer.toHexString(System.identityHashCode(ship))
                    + " " + (prev == null ? "first" : "CHANGED")
                    + " dsig{" + ShipDealtSig.verbose(ship) + "}"
                    + (dsigLines == DSIG_LINE_MAX ? " [line cap reached]" : ""));
        } catch (RuntimeException e) {
            // fail-soft: the census must never break the serve loop
        }
    }

    /**
     * Content-identity snapshots for every installed ship, in repository order, or
     * {@code null} when the hull-spec handles are not bound or any read fails or
     * yields a blank/non-string hull id. This is the reflective read:
     * {@code ShipAPI.getHullSpec().getHullId()} on each live engine ship, exposing
     * which hull each entity <em>is</em> (identity), distinct from the motion and
     * durability views which only carry mutable state.
     */
    public List<ShipIdentityView> shipIdentitiesOrNull() {
        if (shipGetHullSpec == null || hullSpecGetHullId == null) {
            return null;
        }
        int shipIdx = -1;
        Object entry = null;
        try {
            List<?> ships = rosterShipList();
            List<ShipIdentityView> identities = new ArrayList<>(ships.size());
            long shipId = 1L;
            for (Object ship : ships) {
                shipIdx++;
                entry = ship;
                if (ship == null) {
                    reportWalkNull("identity", "null ship entry", shipIdx, null, null, null);
                    return null;
                }
                Object hullSpec = shipGetHullSpec.invoke(ship);
                if (hullSpec == null) {
                    reportWalkNull("identity", "null getHullSpec", shipIdx, ship, null, null);
                    return null;
                }
                Object hullId = hullSpecGetHullId.invoke(hullSpec);
                if (!(hullId instanceof String)) {
                    reportWalkNull("identity", "non-string getHullId ("
                            + (hullId == null ? "null" : hullId.getClass().getName()) + ")",
                            shipIdx, ship, null, null);
                    return null;
                }
                String key = ((String) hullId).trim();
                if (key.isEmpty()) {
                    reportWalkNull("identity", "blank getHullId", shipIdx, ship, null, null);
                    return null;
                }
                identities.add(new EngineBackedShipIdentityView(shipId++, key));
            }
            return identities;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reportWalkNull("identity", "read threw", shipIdx, entry, null, e);
            return null;
        }
    }

    /**
     * Lifecycle snapshots for every installed ship, in repository order, or
     * {@code null} when the lifecycle handles are not bound or any read fails or
     * yields a blank/non-string stable id or non-boolean liveness. This is the
     * reflective read: {@code ShipAPI.getId()} (the engine's stable
     * cross-tick entity id) and {@code ShipAPI.isAlive()} (liveness) on each live
     * engine ship. The within-tick {@code shipId} matches the motion/durability/
     * identity views read in the same tick (repository order); the
     * {@link ShipLifecycleView#stableKey() stableKey} is the identity that survives
     * the roster shrinking (a ship destroyed and removed) or growing — the basis for
     * a stable network {@code entityId} that the position-derived {@code shipId}
     * cannot provide.
     */
    public List<ShipLifecycleView> shipLifecyclesOrNull() {
        if (shipGetId == null || shipIsAlive == null) {
            return null;
        }
        int shipIdx = -1;
        Object entry = null;
        String stableId = null;
        try {
            List<?> ships = rosterShipList();
            List<ShipLifecycleView> lifecycles = new ArrayList<>(ships.size());
            long shipId = 1L;
            for (Object ship : ships) {
                shipIdx++;
                entry = ship;
                stableId = null;
                if (ship == null) {
                    reportWalkNull("lifecycle", "null ship entry", shipIdx, null, null, null);
                    return null;
                }
                Object idValue = shipGetId.invoke(ship);
                if (!(idValue instanceof String)) {
                    reportWalkNull("lifecycle", "non-string getId ("
                            + (idValue == null ? "null" : idValue.getClass().getName()) + ")",
                            shipIdx, ship, null, null);
                    return null;
                }
                String key = ((String) idValue).trim();
                if (key.isEmpty()) {
                    reportWalkNull("lifecycle", "blank getId", shipIdx, ship, null, null);
                    return null;
                }
                stableId = key;
                Object aliveValue = shipIsAlive.invoke(ship);
                if (!(aliveValue instanceof Boolean)) {
                    reportWalkNull("lifecycle", "non-boolean isAlive ("
                            + (aliveValue == null ? "null" : aliveValue.getClass().getName()) + ")",
                            shipIdx, ship, key, null);
                    return null;
                }
                lifecycles.add(new EngineBackedShipLifecycleView(shipId++, key, (Boolean) aliveValue));
            }
            return lifecycles;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reportWalkNull("lifecycle", "read threw", shipIdx, entry, stableId, e);
            return null;
        }
    }

    /**
     * Flux + shield status snapshots for every installed ship, in repository order,
     * or {@code null} when the flux handles are not bound or any read fails or
     * yields a non-finite/non-boolean value. This is the reflective read:
     * {@code ShipAPI.getFluxTracker()} (then {@code getCurrFlux()}/{@code getMaxFlux()}/
     * {@code getFluxLevel()}/{@code getHardFlux()}/{@code isOverloaded()}/{@code isVenting()})
     * and the optional {@code ShipAPI.getShield()} raised/absent state on each live
     * engine ship. Flux + shield is the combat-effectiveness tier the snapshot schema
     * lists alongside the already-mapped hull/armor durability — a near-max-flux or
     * overloaded ship cannot fire and drops its shield, which hull/armor cannot tell
     * a client apart.
     *
     * <p>The within-tick {@code shipId} matches the motion/durability/identity/lifecycle
     * views read in the same tick (repository order). The shield read is optional and
     * fails soft <em>per ship</em>: an unbound shield handle, or a {@code getShield()}
     * returning null (a shieldless hull), yields {@code hasShield=false}/{@code shieldOn=false}
     * for that ship rather than nullifying the whole list — flux is the required tier,
     * shield is supplementary status.
     */
    public List<ShipFluxView> shipFluxStatesOrNull() {
        if (shipGetFluxTracker == null
                || fluxGetCurrFlux == null
                || fluxGetMaxFlux == null
                || fluxGetFluxLevel == null
                || fluxGetHardFlux == null
                || fluxIsOverloaded == null
                || fluxIsVenting == null) {
            return null;
        }
        int shipIdx = -1;
        Object entry = null;
        try {
            List<?> ships = rosterShipList();
            List<ShipFluxView> fluxStates = new ArrayList<>(ships.size());
            long shipId = 1L;
            for (Object ship : ships) {
                shipIdx++;
                entry = ship;
                if (ship == null) {
                    reportWalkNull("flux", "null ship entry", shipIdx, null, null, null);
                    return null;
                }
                Object tracker = shipGetFluxTracker.invoke(ship);
                if (tracker == null) {
                    reportWalkNull("flux", "null getFluxTracker", shipIdx, ship, null, null);
                    return null;
                }
                float currFlux = numberAsFloat(fluxGetCurrFlux.invoke(tracker), "getCurrFlux");
                float maxFlux = numberAsFloat(fluxGetMaxFlux.invoke(tracker), "getMaxFlux");
                // A zero-flux-capacity hull (station armor module) makes
                // the engine's getFluxLevel() = currFlux/maxFlux read 0/0 = NaN — a legitimate
                // engine state, not a broken read, so it must not null the WHOLE tier (the
                // fortress watch served all 12959 ticks with no flux/shield tier and awsd_kf=0
                // from exactly this). Zero capacity means zero flux pressure: pin level 0 and
                // skip the engine-field read; maxFlux > 0 keeps the record-field read with
                // its fail-closed finite check.
                float fluxLevel = maxFlux > 0f
                        ? numberAsFloat(fluxGetFluxLevel.invoke(tracker), "getFluxLevel")
                        : 0f;
                float hardFlux = numberAsFloat(fluxGetHardFlux.invoke(tracker), "getHardFlux");
                Boolean overloaded = asBoolean(fluxIsOverloaded.invoke(tracker));
                Boolean venting = asBoolean(fluxIsVenting.invoke(tracker));
                if (overloaded == null || venting == null) {
                    reportWalkNull("flux", (overloaded == null
                            ? "non-boolean isOverloaded" : "non-boolean isVenting"),
                            shipIdx, ship, null, null);
                    return null;
                }
                boolean hasShield = false;
                boolean shieldOn = false;
                float shieldFacing = 0f;
                if (shipGetShield != null && shieldIsOn != null) {
                    Object shield = shipGetShield.invoke(ship);
                    if (shield != null) {
                        Boolean on = asBoolean(shieldIsOn.invoke(shield));
                        if (on == null) {
                            reportWalkNull("flux", "non-boolean shield isOn", shipIdx, ship, null, null);
                            return null;
                        }
                        hasShield = true;
                        shieldOn = on;
                        if (shieldGetFacing != null) {
                            shieldFacing = numberAsFloat(
                                    shieldGetFacing.invoke(shield), "getFacing");
                        }
                    }
                }
                fluxStates.add(new EngineBackedShipFluxView(
                        shipId++,
                        currFlux,
                        maxFlux,
                        fluxLevel,
                        hardFlux,
                        overloaded,
                        venting,
                        hasShield,
                        shieldOn,
                        shieldFacing));
            }
            return fluxStates;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reportWalkNull("flux", "read threw", shipIdx, entry, null, e);
            return null;
        }
    }

    /**
     * The rollback knob for the per-mount {@code FLAG_SELECTED} encode + the
     * {@code serveDeclaredFire} selection restore (one lever for both server halves — OFF =
     * the earlier wire bytes AND serve behavior verbatim, the A/B arm). Default ON; only an
     * explicit {@code false} disables, so a typo cannot silently disable the (114)(d) fix.
     */
    static final boolean MOUNT_SELECTED_FLAG = // pkg-private: PilotCommandApplier
            !"false".equalsIgnoreCase(cc.starsector.server.runtime.ServerConfig.resolve(
                    "cc.server.mountSelectedFlag", "CC_SERVER_MOUNT_SELECTED_FLAG",
                    "server.mountSelectedFlag", "true"));

    /**
     * Per-weapon mount combat state: for each live engine ship,
     * {@code ShipAPI.getAllWeapons()} then per mount {@code WeaponAPI.getId()} +
     * {@code getCooldownRemaining()} / {@code getCooldown()} / {@code isFiring()} /
     * {@code usesAmmo()} / {@code getAmmo()} / {@code getMaxAmmo()} / {@code isDisabled()}
     * / {@code getChargeLevel()}. Returns a flat list across the roster — a ship with
     * several mounts contributes several views, each tagged with its owning
     * {@link WeaponMountView#shipId()} (repository position, matching the other ship
     * reads) and per-ship {@link WeaponMountView#mountIndex()} ordinal.
     *
     * <p>{@code null} (fail-soft) when any weapon handle is unbound or any read fails
     * (non-list weapons, null element/id, non-Number scalar, non-Boolean flag); an
     * empty list when the roster has no weapons. This is the per-mount readiness tier
     * below the ship-level {@link #shipFluxStatesOrNull()} flux/shield read: a healthy
     * ship whose mounts are all on cooldown, out of ammo, or disabled cannot deal
     * damage, which hull/armor/flux alone cannot express.
     */
    public List<WeaponMountView> weaponMountStatesOrNull() {
        if (shipGetAllWeapons == null
                || weaponGetId == null
                || weaponGetCooldownRemaining == null
                || weaponGetCooldown == null
                || weaponIsFiring == null
                || weaponUsesAmmo == null
                || weaponGetAmmo == null
                || weaponGetMaxAmmo == null
                || weaponIsDisabled == null
                || weaponGetChargeLevel == null) {
            return null;
        }
        try {
            List<?> ships = rosterShipList();
            List<WeaponMountView> mounts = new ArrayList<>();
            // Optional additive GPEE-v3 read: the TRUE bearing
            // each weapon's just-spawned projectile actually flies — since v8 with the spawn's
            // own muzzle position — keyed by firing-weapon
            // identity. Empty map (unbound/failed) degrades every freshSpawn* scalar to NaN,
            // never the tier.
            Map<Object, FreshSpawn> freshSpawns = freshSpawnScan.freshSpawnBearingsByWeapon(freshSpawnElapsedMaxSeconds);
            long shipId = 1L;
            for (Object ship : ships) {
                if (ship == null) {
                    return null;
                }
                Object weaponsObj = shipGetAllWeapons.invoke(ship);
                if (!(weaponsObj instanceof List<?>)) {
                    return null;
                }
                List<?> weapons = (List<?>) weaponsObj;
                // Optional additive GPEE-v2 read (flightBearingDeg composition):
                // the shooter's velocity at this same post-step instant, read once per ship and
                // stamped onto each of its mount views. Unbound/failing degrades the pair to NaN,
                // never the tier (same discipline as currAngle below).
                float shipVelX = Float.NaN;
                float shipVelY = Float.NaN;
                if (playerShipGetVelocity != null) {
                    try {
                        float[] vel = readXY(playerShipGetVelocity.invoke(ship));
                        if (Float.isFinite(vel[0]) && Float.isFinite(vel[1])) {
                            shipVelX = vel[0];
                            shipVelY = vel[1];
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // velocity pair stays NaN; the mount tier itself still crosses.
                    }
                }
                // The hull's SELECTED weapon group handle, read once
                // per ship and identity-compared against each mount's owning group below.
                // Gated by the rollback knob (bit never encoded when off = pre-widening wire
                // bytes); unbound/failing degrades to null = every mount "not selected".
                Object selectedGroup = null;
                if (MOUNT_SELECTED_FLAG && shipGetSelectedGroupAPI != null) {
                    try {
                        selectedGroup = shipGetSelectedGroupAPI.invoke(ship);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // selectedGroup stays null; the mount tier itself still crosses.
                    }
                }
                int mountIndex = 0;
                for (Object weapon : weapons) {
                    if (weapon == null) {
                        return null;
                    }
                    Object idValue = weaponGetId.invoke(weapon);
                    if (!(idValue instanceof String)) {
                        return null;
                    }
                    float cooldownRemaining =
                            numberAsFloat(weaponGetCooldownRemaining.invoke(weapon), "getCooldownRemaining");
                    float cooldown = numberAsFloat(weaponGetCooldown.invoke(weapon), "getCooldown");
                    Boolean firing = asBoolean(weaponIsFiring.invoke(weapon));
                    Boolean usesAmmo = asBoolean(weaponUsesAmmo.invoke(weapon));
                    Boolean disabled = asBoolean(weaponIsDisabled.invoke(weapon));
                    if (firing == null || usesAmmo == null || disabled == null) {
                        return null;
                    }
                    if (disabled.booleanValue() && weaponIsForceDisabled != null) {
                        // un-fold the script force-disable (see the field's javadoc)
                        Boolean forced = asBoolean(weaponIsForceDisabled.invoke(weapon));
                        if (Boolean.TRUE.equals(forced)) {
                            disabled = Boolean.FALSE;
                        }
                    }
                    int ammo = numberAsInt(weaponGetAmmo.invoke(weapon), "getAmmo");
                    int maxAmmo = numberAsInt(weaponGetMaxAmmo.invoke(weapon), "getMaxAmmo");
                    float chargeLevel = numberAsFloat(weaponGetChargeLevel.invoke(weapon), "getChargeLevel");
                    // Optional additive read (aimFacingDeg —
                    // every non-missile discharge + the missile fallback, a
                    // missile carries its fresh spawn's facing via LaunchFacingLaw):
                    // unbound or failing degrades this one scalar to NaN, never the tier.
                    float currAngleDeg = Float.NaN;
                    if (weaponGetCurrAngle != null) {
                        try {
                            Object angleValue = weaponGetCurrAngle.invoke(weapon);
                            if (angleValue instanceof Number) {
                                float angle = ((Number) angleValue).floatValue();
                                if (Float.isFinite(angle)) {
                                    currAngleDeg = angle;
                                }
                            }
                        } catch (ReflectiveOperationException | RuntimeException ignored) {
                            // currAngle stays NaN; the mount state itself still crosses.
                        }
                    }
                    // Optional additive GPEE-v2 read: the mounted weapon's
                    // stat-modified muzzle speed — WeaponAPI.getProjectileSpeed(), the exact
                    // value the engine's ballistic spawn composes with the shooter velocity.
                    float projectileSpeed = Float.NaN;
                    if (weaponGetProjectileSpeed != null) {
                        try {
                            Object speedValue = weaponGetProjectileSpeed.invoke(weapon);
                            if (speedValue instanceof Number) {
                                float speed = ((Number) speedValue).floatValue();
                                if (Float.isFinite(speed)) {
                                    projectileSpeed = speed;
                                }
                            }
                        } catch (ReflectiveOperationException | RuntimeException ignored) {
                            // projectileSpeed stays NaN; the mount state itself still crosses.
                        }
                    }
                    // Optional additive read: the owning weapon GROUP's autofire
                    // state (mount->group resolve + WeaponGroupAPI.isAutofiring, the same
                    // handles the B1 UP mirror binds). Unbound, groupless, or failing
                    // degrades this one boolean to false, never the tier.
                    boolean autofire = false;
                    boolean selected = false;
                    if (shipGetWeaponGroupFor != null && weaponGroupIsAutofiring != null) {
                        try {
                            Object group = shipGetWeaponGroupFor.invoke(ship, weapon);
                            if (group != null) {
                                Boolean groupOn = asBoolean(weaponGroupIsAutofiring.invoke(group));
                                autofire = groupOn != null && groupOn.booleanValue();
                                // same-list handles — identity compare vs the
                                // per-ship hoisted selected-group read above.
                                selected = selectedGroup != null && group.equals(selectedGroup);
                            }
                        } catch (ReflectiveOperationException | RuntimeException ignored) {
                            // autofire/selected stay false; the mount state itself still crosses.
                        }
                    }
                    FreshSpawn fs = freshSpawns.get(weapon);
                    mounts.add(EngineBackedWeaponMountView.builder()
                            .shipId(shipId)
                            .mountIndex(mountIndex++)
                            .weaponId((String) idValue)
                            .cooldownRemaining(cooldownRemaining)
                            .cooldown(cooldown)
                            .firing(firing)
                            .usesAmmo(usesAmmo)
                            .ammo(ammo)
                            .maxAmmo(maxAmmo)
                            .disabled(disabled)
                            .chargeLevel(chargeLevel)
                            .currAngleDeg(currAngleDeg)
                            .projectileSpeed(projectileSpeed)
                            .shipVelX(shipVelX)
                            .shipVelY(shipVelY)
                            .freshSpawnBearingDeg(fs != null ? fs.bearingDeg() : Float.NaN)
                            .freshSpawnMuzzleX(fs != null ? fs.muzzleX() : Float.NaN)
                            .freshSpawnMuzzleY(fs != null ? fs.muzzleY() : Float.NaN)
                            .freshSpawnFacingDeg(fs != null ? fs.facingDeg() : Float.NaN)
                            .freshSpawnMissile(fs != null && fs.missile())
                            .autofire(autofire)
                            .selected(selected)
                            .build());
                }
                shipId++;
            }
            return mounts;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Read-only identity map engine-ship-ref → position shipId
     * mirroring the EXACT roster enumeration the view walks above use ({@link #rosterShipList()},
     * {@code shipId = 1L; … shipId++} per slot) — the shadow comparator's translation from a
     * confessed birth's raw ship reference into the same positional key the mount views carry,
     * from which the capture's {@code lastEntityIdByPositionShipId} map reaches the wire id.
     * {@code null} (fail-soft) when the roster read fails — same discipline as the view tiers;
     * the caller counts affected births {@code unresolved}, never guesses.
     */
    public Map<Object, Long> positionShipIdByEngineShip() {
        try {
            List<?> ships = rosterShipList();
            IdentityHashMap<Object, Long> byShip = new IdentityHashMap<>();
            long shipId = 1L;
            for (Object ship : ships) {
                if (ship == null) {
                    return null;
                }
                byShip.put(ship, shipId++);
            }
            return byShip;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Post-step age (seconds) up to which a live projectile still counts as "spawned by the
     * discharge being detected". The engine's {@code executeAdds} lands a mid-advance spawn in
     * the entity list within the same completed tick with {@code elapsed} still 0 (the new
     * entity's own {@code advance} has not run — {@code CombatEngine.advanceInner} drains adds
     * AFTER the object-advance pass), and the measured cooldown-rise signal arrives
     * one tick later when {@code elapsed} is exactly one 1/60 s step — 1.5 steps admits both
     * reads and nothing older (a cooldown-gated mount cannot re-fire within two ticks;
     * burst-internal refire is out of detector scope either way).
     */
    public static final float FRESH_SPAWN_ELAPSED_MAX_SECONDS = 1.5f / 60.0f;

    /**
     * The live per-round fresh-spawn window: 1.5 ticks at the CONFIGURED effective
     * rate, defaulting to the canonical 60 Hz constant above. {@link #configureSimTickRate} keeps
     * the division shape ({@code 1.5f / rate}) so the default stays bit-identical.
     */
    private float freshSpawnElapsedMaxSeconds = FRESH_SPAWN_ELAPSED_MAX_SECONDS;
    /** The configured effective tick rate (the adopt rate-cap ticks/s input); 60 = canonical. */
    private float simTicksPerSecond = 60f;

    /**
     * Size the rate-coupled per-second windows from the serve loop's EFFECTIVE tick
     * rate ({@code Math.round(1.0 / config.fixedDt())} — a {@code --fixed-dt} CLI override wins
     * over the {@code cc.sim.tickRate} knob). Called once per round by each serve lane, next to
     * {@code applyCombatMapBounds}, before tick 0. At 60 the derived values are bit-identical to
     * the historical literals.
     */
    public void configureSimTickRate(float ticksPerSecond) {
        if (!(ticksPerSecond > 0f)) {
            throw new IllegalArgumentException("ticksPerSecond must be > 0; got " + ticksPerSecond);
        }
        this.freshSpawnElapsedMaxSeconds = 1.5f / ticksPerSecond;
        this.simTicksPerSecond = ticksPerSecond;
    }

    /**
     * Primary ship-system readiness state: for each live engine ship
     * that has a primary system, {@code ShipAPI.getSystem()} then the public-API
     * {@code ShipSystemAPI} reads {@code getId()} / {@code getState()} /
     * {@code isActive()} / {@code isOn()} / {@code getCooldownRemaining()} /
     * {@code getCooldown()} / {@code getEffectLevel()} / {@code getAmmo()} /
     * {@code getMaxAmmo()} / {@code isOutOfAmmo()}. Returns a flat list across the
     * roster with one entry per ship that owns a primary system, each tagged with
     * its owning {@link ShipSystemView#shipId()} (repository position, matching the
     * other ship reads).
     *
     * <p>This is the read complement to the activation-only command boundary
     * ({@code ShipAPI.useSystem()}): a fortress shield raised, a burn
     * drive engaged, or fast missile racks reloading is combat-decisive and not
     * expressible by hull/armor/flux/weapon-mount state.
     *
     * <p>{@code null} (fail-soft) when any system handle is unbound or any read on a
     * <em>present</em> system fails (null/non-String id, a state value that is not an
     * {@code Enum}, non-Number scalar, non-Boolean flag). A ship whose
     * {@code getSystem()} returns {@code null} simply has no primary system and
     * contributes <em>no</em> entry (it does not nullify the list); an empty list when
     * no ship in the roster owns a primary system.
     */
    public List<ShipSystemView> shipSystemStatesOrNull() {
        if (shipGetSystem == null
                || systemGetId == null
                || systemGetState == null
                || systemIsActive == null
                || systemIsOn == null
                || systemGetCooldownRemaining == null
                || systemGetCooldown == null
                || systemGetEffectLevel == null
                || systemGetAmmo == null
                || systemGetMaxAmmo == null
                || systemIsOutOfAmmo == null) {
            return null;
        }
        try {
            List<?> ships = rosterShipList();
            List<ShipSystemView> systems = new ArrayList<>();
            Map<Object, Long> positionByShip = null; // Built lazily, first mote press only
            long shipId = 1L;
            for (Object ship : ships) {
                if (ship == null) {
                    return null;
                }
                Object system = shipGetSystem.invoke(ship);
                if (system == null) {
                    shipId++; // ship has no primary system: contributes no entry
                    continue;
                }
                Object idValue = systemGetId.invoke(system);
                if (!(idValue instanceof String)) {
                    return null;
                }
                String stateName = enumName(systemGetState.invoke(system));
                if (stateName == null) {
                    return null;
                }
                Boolean active = asBoolean(systemIsActive.invoke(system));
                Boolean on = asBoolean(systemIsOn.invoke(system));
                Boolean outOfAmmo = asBoolean(systemIsOutOfAmmo.invoke(system));
                if (active == null || on == null || outOfAmmo == null) {
                    return null;
                }
                float cooldownRemaining =
                        numberAsFloat(systemGetCooldownRemaining.invoke(system), "system.getCooldownRemaining");
                float cooldown = numberAsFloat(systemGetCooldown.invoke(system), "system.getCooldown");
                float effectLevel = numberAsFloat(systemGetEffectLevel.invoke(system), "system.getEffectLevel");
                int ammo = numberAsInt(systemGetAmmo.invoke(system), "system.getAmmo");
                int maxAmmo = numberAsInt(systemGetMaxAmmo.invoke(system), "system.getMaxAmmo");
                float moteX = Float.NaN;
                float moteY = Float.NaN;
                long moteLock = 0L;
                if (active && engineGetCustomData != null && MOTE_CONTROL_SYSTEM_ID.equals(idValue)) {
                    if (positionByShip == null) {
                        positionByShip = new IdentityHashMap<>();
                        long pos = 1L;
                        for (Object s : ships) {
                            positionByShip.put(s, pos++);
                        }
                    }
                    float[] mote = moteTargetOrNull(ship, positionByShip);
                    if (mote != null) {
                        moteX = mote[0];
                        moteY = mote[1];
                        moteLock = (long) mote[2];
                    }
                }
                systems.add(new EngineBackedShipSystemView(
                        shipId,
                        (String) idValue,
                        stateName,
                        active,
                        on,
                        cooldownRemaining,
                        cooldown,
                        effectLevel,
                        ammo,
                        maxAmmo,
                        outOfAmmo,
                        moteX,
                        moteY,
                        moteLock));
                shipId++;
            }
            return systems;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** {@code ship_systems.csv} id of the Ziggurat attractor. */
    static final String MOTE_CONTROL_SYSTEM_ID = "mote_control";
    /** {@code MoteControlScript.getSharedData}: the engine customData key suffix. */
    private static final String MOTE_SHARED_SUFFIX = "_mote_AI_shared";
    /** {@code MoteControlScript.ATTRACTOR_DURATION} (10 s; the lock form is 20 s): a target
     *  whose {@code attractorRemaining} sits at or above this was set THIS press (the script
     *  resets it to the full duration in {@code calculateTargetData}); anything lower is
     *  a previous press's target still persisting — never stamped onto a new edge. */
    private static final float MOTE_FRESH_REMAINING_MIN = 9.0f;

    /**
     * The Ziggurat attractor's target for {@code ship}'s press, read READ-ONLY
     * off the engine customData map ({@code MoteControlScript.getSharedData} keys it by
     * {@code source + "_mote_AI_shared"} — never invoked, it CREATES the entry when absent).
     * Returns {@code {x, y, lockPositionShipId}} or {@code null} (no entry, no target, stale
     * target, non-finite pose, any reflective miss) — fail-soft, the caller keeps the plain edge.
     */
    private float[] moteTargetOrNull(Object ship, Map<Object, Long> positionByShip) {
        try {
            Object map = engineGetCustomData.invoke(engine);
            if (!(map instanceof Map<?, ?>)) {
                return null;
            }
            Object shared = ((Map<?, ?>) map).get(ship.toString() + MOTE_SHARED_SUFFIX);
            if (shared == null) {
                return null;
            }
            Class<?> c = shared.getClass();
            Object target = c.getField("attractorTarget").get(shared);
            Object remaining = c.getField("attractorRemaining").get(shared);
            if (target == null || !(remaining instanceof Number)
                    || ((Number) remaining).floatValue() < MOTE_FRESH_REMAINING_MIN) {
                return null;
            }
            float[] xy = readXY(target);
            if (xy == null) {
                return null;
            }
            Object lock = c.getField("attractorLock").get(shared);
            Long lockPos = lock == null ? null : positionByShip.get(lock);
            return new float[] {xy[0], xy[1], lockPos == null ? 0f : lockPos.floatValue()};
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Phase-cloak state for every cloak-bearing ship, in
     * repository order — the {@link #shipSystemStatesOrNull()} clone over the SEPARATE
     * {@code ShipAPI.getPhaseCloak()} slot (a Grendel's {@code getSystem()} is its ammofeed;
     * the cloak never appears in the primary-system tier). The cloak object implements the same
     * {@code ShipSystemAPI} surface, so the shared system read handles invoke on it directly;
     * the one extra read is the ship-level {@code isPhased()} collision-class truth.
     *
     * <p>{@code null} (fail-soft) when the cloak/phased handles or any needed system read
     * handle is unbound, or a read on a <em>present</em> cloak fails. A ship whose
     * {@code getPhaseCloak()} returns {@code null} contributes no entry; an empty list when no
     * ship in the roster carries a cloak. This is the Move-1 probe surface and the pre-paid
     * server half of the DD4 defense-state wire lane (wire subset chosen at Move 2).
     */
    public List<ShipPhaseCloakView> shipPhaseCloakStatesOrNull() {
        if (shipGetPhaseCloak == null
                || shipIsPhased == null
                || systemGetId == null
                || systemGetState == null
                || systemIsActive == null
                || systemIsOn == null
                || systemGetCooldownRemaining == null
                || systemGetCooldown == null
                || systemGetEffectLevel == null) {
            return null;
        }
        try {
            List<?> ships = rosterShipList();
            List<ShipPhaseCloakView> cloaks = new ArrayList<>();
            long shipId = 1L;
            for (Object ship : ships) {
                if (ship == null) {
                    return null;
                }
                Object cloak = shipGetPhaseCloak.invoke(ship);
                if (cloak == null) {
                    shipId++; // ship has no phase cloak: contributes no entry
                    continue;
                }
                Object idValue = systemGetId.invoke(cloak);
                if (!(idValue instanceof String)) {
                    return null;
                }
                String stateName = enumName(systemGetState.invoke(cloak));
                if (stateName == null) {
                    return null;
                }
                Boolean active = asBoolean(systemIsActive.invoke(cloak));
                Boolean on = asBoolean(systemIsOn.invoke(cloak));
                Boolean phased = asBoolean(shipIsPhased.invoke(ship));
                if (active == null || on == null || phased == null) {
                    return null;
                }
                float cooldownRemaining =
                        numberAsFloat(systemGetCooldownRemaining.invoke(cloak), "cloak.getCooldownRemaining");
                float cooldown = numberAsFloat(systemGetCooldown.invoke(cloak), "cloak.getCooldown");
                float effectLevel = numberAsFloat(systemGetEffectLevel.invoke(cloak), "cloak.getEffectLevel");
                // Ammo = the only per-tick press signal on a zero-duration
                // charged defense system; -1 (not a gate) when the nullable handle is absent.
                int ammo = systemGetAmmo == null
                        ? -1 : numberAsInt(systemGetAmmo.invoke(cloak), "cloak.getAmmo");
                cloaks.add(new EngineBackedShipPhaseCloakView(
                        shipId,
                        (String) idValue,
                        stateName,
                        active,
                        on,
                        cooldownRemaining,
                        cooldown,
                        effectLevel,
                        phased,
                        ammo));
                shipId++;
            }
            return cloaks;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Combat-readiness clock for EVERY live ship, in repository
     * order — the authoritative CR facts a predict client pins its frozen local
     * readouts to (the client's local in-combat roster scan differs — locked
     * carriers, absent seats — so its clock idles at full PPT while this ship
     * fatigues). Reads are the PUBLIC ShipAPI surface: {@code getCurrentCR()}, the
     * RAW {@code getTimeDeployedForCRReduction()} accumulator, and effective peak =
     * {@code getMutableStats().getPeakCRDuration().computeEffective(
     * getHullSpec().getNoCRLossTime())} — the engine's own
     * {@code getPeakTimeRemaining()} expression.
     *
     * <p>{@code null} (fail-soft) when any CR/stats/hull-spec handle is unbound or a
     * read fails / yields a non-finite scalar. Every ship contributes exactly one
     * entry (the flux-fold enrollment — every hull owns a CR clock; never-lose-CR
     * hulls simply read huge finite peaks).
     */
    public List<ShipCrView> shipCrStatesOrNull() {
        if (shipGetCurrentCR == null
                || shipGetTimeDeployedForCR == null
                || shipGetMutableStats == null
                || statsGetPeakCRDuration == null
                || statBonusComputeEffective == null
                || shipGetHullSpec == null
                || hullSpecGetNoCRLossTime == null) {
            return null;
        }
        try {
            List<?> ships = rosterShipList();
            List<ShipCrView> crStates = new ArrayList<>(ships.size());
            long shipId = 1L;
            for (Object ship : ships) {
                if (ship == null) {
                    return null;
                }
                float cr = numberAsFloat(shipGetCurrentCR.invoke(ship), "ship.getCurrentCR");
                float timeDeployed = numberAsFloat(
                        shipGetTimeDeployedForCR.invoke(ship), "ship.getTimeDeployedForCRReduction");
                Object stats = shipGetMutableStats.invoke(ship);
                Object spec = shipGetHullSpec.invoke(ship);
                if (stats == null || spec == null) {
                    return null;
                }
                Object peakStat = statsGetPeakCRDuration.invoke(stats);
                if (peakStat == null) {
                    return null;
                }
                float noCrLossTime = numberAsFloat(
                        hullSpecGetNoCRLossTime.invoke(spec), "hullSpec.getNoCRLossTime");
                float peak = numberAsFloat(
                        statBonusComputeEffective.invoke(peakStat, noCrLossTime),
                        "peakCRDuration.computeEffective");
                int fighterPct = ShipCrView.NO_FIGHTER_REPLACEMENT_PCT;
                if (shipGetSharedFighterReplacementRate != null) {
                    // The vanilla fighter panel's own arithmetic
                    // ((int)(rate * 100), truncation) so the served
                    // rate line prints what the local engine would have printed.
                    float rate = numberAsFloat(shipGetSharedFighterReplacementRate.invoke(ship),
                            "ship.getSharedFighterReplacementRate");
                    if (Float.isFinite(rate)) {
                        fighterPct = Math.max(0, Math.min(100, (int) (rate * 100f)));
                    }
                }
                crStates.add(new EngineBackedShipCrView(
                        shipId++, cr, timeDeployed, peak, fighterPct));
            }
            return crStates;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Static physical profile for every installed ship, in repository
     * order, or {@code null} when the profile handles are not bound or any read
     * fails / yields a non-enum hull size or a non-finite scalar. This is the
     * reflective read {@code ShipAPI.getHullSize()} (the {@code HullSize} enum, read
     * by {@link Enum#name()} so no game type crosses) plus the {@code CombatEntityAPI}
     * geometry/physics reads {@code getCollisionRadius()} and {@code getMass()} on
     * each live engine ship.
     *
     * <p>Hull size + collision footprint + mass is the physical-profile tier: it
     * tells a client how big a ship is (for relevance/priority weighting — a capital
     * ship is relevant at a longer range than a frigate) and how it occupies space,
     * which the per-tick motion/durability/flux/weapon-mount tiers do not express.
     * Every ship contributes exactly one profile entry; the within-tick
     * {@code shipId} matches the motion/durability/identity/lifecycle/flux views read
     * in the same tick (repository order, starting at 1).
     */
    public List<ShipProfileView> shipProfilesOrNull() {
        if (shipGetHullSize == null
                || entityGetCollisionRadius == null
                || entityGetMass == null) {
            return null;
        }
        try {
            List<?> ships = rosterShipList();
            List<ShipProfileView> profiles = new ArrayList<>(ships.size());
            long shipId = 1L;
            for (Object ship : ships) {
                if (ship == null) {
                    return null;
                }
                String hullSize = enumName(shipGetHullSize.invoke(ship));
                if (hullSize == null) {
                    return null;
                }
                float collisionRadius =
                        numberAsFloat(entityGetCollisionRadius.invoke(ship), "getCollisionRadius");
                float mass = numberAsFloat(entityGetMass.invoke(ship), "getMass");
                profiles.add(new EngineBackedShipProfileView(
                        shipId++, hullSize, collisionRadius, mass));
            }
            return profiles;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Maneuverability / movement-capability envelope for every
     * installed ship, in repository order, or {@code null} when the maneuver
     * handles are not bound or any read fails / yields a non-finite scalar. This is
     * the reflective read {@code ShipAPI.getMutableStats()} (the
     * {@code MutableShipStatsAPI}) then per stat the {@code MutableStat} getters
     * {@code getMaxSpeed()} / {@code getAcceleration()} / {@code getDeceleration()} /
     * {@code getMaxTurnRate()} / {@code getTurnAcceleration()}, each crossed by its
     * {@code MutableStat.getModifiedValue()} effective float — so no engine-internal /
     * API stat type crosses the adapter boundary.
     *
     * <p>This is the movement-<em>capability</em> tier: how fast a ship can go and
     * how quickly it can change heading, which neither the per-tick motion read
     * (current loc/vel/facing/angular) nor the static physical profile
     * (hull-size/collision-radius/mass) expresses. A server-authoritative
     * sim needs this envelope to validate and dead-reckon client movement intents —
     * a requested velocity past {@code maxSpeed} or a turn faster than
     * {@code maxTurnRate} is state the server must clamp. Every ship contributes
     * exactly one entry; the within-tick {@code shipId} matches the
     * motion/durability/identity/lifecycle/flux/profile views read in the same tick
     * (repository order, starting at 1).
     */
    public List<ShipManeuverView> shipManeuverProfilesOrNull() {
        if (shipGetMutableStats == null
                || statsGetMaxSpeed == null
                || statsGetAcceleration == null
                || statsGetDeceleration == null
                || statsGetMaxTurnRate == null
                || statsGetTurnAcceleration == null
                || statGetModifiedValue == null) {
            return null;
        }
        try {
            List<?> ships = rosterShipList();
            List<ShipManeuverView> maneuvers = new ArrayList<>(ships.size());
            long shipId = 1L;
            for (Object ship : ships) {
                if (ship == null) {
                    return null;
                }
                Object stats = shipGetMutableStats.invoke(ship);
                if (stats == null) {
                    return null;
                }
                float maxSpeed = statModifiedValue(statsGetMaxSpeed.invoke(stats), "getMaxSpeed");
                float acceleration = statModifiedValue(statsGetAcceleration.invoke(stats), "getAcceleration");
                float deceleration = statModifiedValue(statsGetDeceleration.invoke(stats), "getDeceleration");
                float maxTurnRate = statModifiedValue(statsGetMaxTurnRate.invoke(stats), "getMaxTurnRate");
                float turnAcceleration =
                        statModifiedValue(statsGetTurnAcceleration.invoke(stats), "getTurnAcceleration");
                maneuvers.add(new EngineBackedShipManeuverView(
                        shipId++, maxSpeed, acceleration, deceleration, maxTurnRate, turnAcceleration));
            }
            return maneuvers;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Cross one {@code MutableStat}'s effective value: fail closed on a null stat
     * (a maneuver getter that returned no stat), then read its
     * {@code getModifiedValue()} as a finite float. Thrown failures are caught by
     * the per-roster fail-soft handler in {@link #shipManeuverProfilesOrNull()}.
     */
    private float statModifiedValue(Object stat, String label) throws ReflectiveOperationException {
        if (stat == null) {
            throw new EngineBindingException(label + " returned a null MutableStat", null);
        }
        return numberAsFloat(statGetModifiedValue.invoke(stat), label + ".getModifiedValue");
    }

    /**
     * Live projectile entities: {@code CombatEngineAPI.getProjectiles()}
     * → per entity {@code getLocation()} / {@code getOwner()} /
     * {@code getProjectileSpecId()}. {@code null} (fail-soft) when the handle is
     * unbound or any read fails; an empty list when the engine currently has none.
     */
    public List<CombatEntityView> projectileEntitiesOrNull() {
        if (combatGetProjectiles == null) {
            return null;
        }
        return readPointEntities(combatGetProjectiles, CombatEntityView.Kind.PROJECTILE);
    }

    /**
     * Live missile entities: {@code CombatEngineAPI.getMissiles()}.
     * Same {@code CombatEntityAPI} point reads as projectiles ({@code MissileAPI}
     * extends {@code DamagingProjectileAPI}). Fail-soft like
     * {@link #projectileEntitiesOrNull()}.
     */
    public List<CombatEntityView> missileEntitiesOrNull() {
        if (combatGetMissiles == null) {
            return null;
        }
        return readPointEntities(combatGetMissiles, CombatEntityView.Kind.MISSILE);
    }

    /**
     * Live beam entities: {@code CombatEngineAPI.getBeams()} → per beam
     * {@code getTo()} (impact endpoint, the replicated primary position), the owning
     * side via {@code getSource().getOwner()}, and the version-independent content
     * key via {@code getWeapon().getId()} (the firing weapon's spec id — a beam's
     * content identity is its weapon, since a beam carries no spec of its own).
     * Beams are not {@code CombatEntityAPI} (no {@code getLocation}), so they read
     * through their own segment/source/weapon surface. Fail-soft like
     * {@link #projectileEntitiesOrNull()}; the weapon-id read additionally degrades
     * <em>per beam</em> to a blank content key (→ {@code contentId 0}) rather than
     * nullifying the whole list, so a script-spawned beam with no resolvable weapon
     * still crosses with its position and owner.
     */
    public List<CombatEntityView> beamEntitiesOrNull() {
        if (combatGetBeams == null) {
            return null;
        }
        try {
            List<?> beams = asList(invoke(combatGetBeams, engine, "getBeams"), "getBeams");
            List<CombatEntityView> views = new ArrayList<>(beams.size());
            // Source binding: resolved lazily once per read (beams are rare;
            // a beam-less frame pays nothing), extending this method's existing inline
            // beam-surface reflection family. The roster map mirrors the mount tier's
            // enumeration exactly (positionShipIdByEngineShip / shipGetAllWeapons), so
            // the emitted mount index shares the ServerWeaponMountState ordinal contract.
            Map<Object, Long> positionShipIds = null;
            IdentityHashMap<Object, List<?>> weaponsBySourceShip = null;
            // The DEM fx-drone → child-missile reverse index, built at most
            // once per read and only when a BLANK-ID source misses the roster map (a
            // beam-less or all-roster frame pays nothing). Rebuilt per read rather than
            // memoized across frames because the rebuild is simple and measured-cheap —
            // readNonShipEntities calls this method exactly once per published frame
            // (see EngineBackedLiveWorldSnapshotSource), so it is ONE build per frame,
            // not one per beam — and the cross-frame memo it would replace has no measured
            // payoff: unattributed ran 0.055–0.120% across three WATCH runs, and the one
            // case a memo recovers (an EMP overload swapping the child's AI to
            // ErraticMissileAI, orphaning its DEMScript) is
            // structurally unreachable on this stand. NOT for the reason once claimed here
            // ("a cached index would resolve dead drones") — that hazard is FALSE: the
            // stale key would be the removed drone OBJECT, which DEMScript.demDrone holds
            // strongly forever (DEMScript never nulls it), so its
            // identity can never be recycled onto another ship, and the only lookup that
            // can reach that key is a live beam whose getSource() IS that drone — i.e. the
            // mapping it returns is the CORRECT attribution. If the rate ever moves, the
            // memo is a safe build; today it would be unmeasurable machinery.
            IdentityHashMap<Object, Object> demChildByDrone = null;
            long id = 1L;
            for (Object beam : beams) {
                if (beam == null) {
                    return null;
                }
                Class<?> beamClass = beam.getClass();
                Object to = beamClass.getMethod("getTo").invoke(beam);
                float[] impact = readXY(to);
                if (impact == null) {
                    return null;
                }
                int owner = -1;
                Object source = beamClass.getMethod("getSource").invoke(beam);
                if (source != null) {
                    Object ownerValue = source.getClass().getMethod("getOwner").invoke(source);
                    if (ownerValue instanceof Number) {
                        owner = ((Number) ownerValue).intValue();
                    }
                }
                long sourcePositionShipId = 0L;
                int sourceMountIndex = -1;
                boolean demAttributedThisBeam = false;
                if (source != null) {
                    if (positionShipIds == null) {
                        positionShipIds = positionShipIdByEngineShip();
                        weaponsBySourceShip = new IdentityHashMap<>();
                    }
                    try {
                        Long positionShipId =
                                positionShipIds == null ? null : positionShipIds.get(source);
                        // ENGINE-NATIVE DEM ATTRIBUTION. A roster miss on a
                        // non-null source is, as measured, the vanilla DEM payload beam:
                        // its source IS the cosmetic fx drone the child missile's own
                        // DEMScript minted, and that drone is blank-id by construction
                        // (engine Ship ctor writes id="" for an fx_drone variant), so
                        // rosterShipList excludes it and the record used to degrade to the
                        // sentinel pair. The engine holds the truth: walk the live missiles,
                        // unwrap each AI, and the DEMScript whose `demDrone` IS this source
                        // names the owning child. We then carry that child's OWN v13a stable
                        // wire id in the source slot — zero new bytes, no schema change, and
                        // the client stops guessing (the nearest-child exclusivity
                        // heuristic swapped bindings on 15.6% of bound child-tick pairs).
                        // The blank-id gate is what makes "roster miss" mean "fx-drone
                        // mint" instead of "any ship the roster walk did not hand back".
                        if (positionShipId == null && DEM_BEAM_SOURCE
                                && isNonRosterBeamSource(source)) {
                            if (demChildByDrone == null) {
                                demChildByDrone = demChildMissileByDroneShip();
                            }
                            Object child = demChildByDrone.get(source);
                            if (child != null) {
                                // Get-or-mint on the SAME map readPointEntities mints from,
                                // and beams are read BEFORE missiles in the frame, so the
                                // child's own record later this frame carries this exact id
                                // — including on its first beam frame.
                                positionShipId = Long.valueOf(missileStableIdFor(child));
                                demAttributedThisBeam = true;
                                noteDemBeamOnce("attributed drone->child (first)");
                            } else {
                                // METER SEMANTICS (narrowed by the blank-id gate above):
                                // this counts "blank-id fx-drone source that no live child
                                // claimed", NOT the older "any non-roster source that no
                                // child claimed". The narrowing also deletes this meter's
                                // worst inflation path — when positionShipIdByEngineShip()
                                // returns null (roster read failed) EVERY beam used to fall
                                // into this branch, so one failed roster read read out as a
                                // lane-wide attribution collapse.
                                demBeamsUnattributed++;
                                demCensusTick();
                                noteDemBeamOnce(demChildByDrone.isEmpty()
                                        ? "unattributed: no DEM script in the live missile list"
                                        : "unattributed: source drone absent from the DEM index");
                            }
                        }
                        Object weapon = positionShipId == null
                                ? null : beamClass.getMethod("getWeapon").invoke(beam);
                        if (weapon != null) {
                            List<?> weapons = weaponsBySourceShip.get(source);
                            if (weapons == null) {
                                Object weaponsObj = shipGetAllWeapons.invoke(source);
                                weapons = (weaponsObj instanceof List<?>)
                                        ? (List<?>) weaponsObj : List.of();
                                weaponsBySourceShip.put(source, weapons);
                            }
                            for (int i = 0; i < weapons.size(); i++) {
                                if (weapons.get(i) == weapon) {
                                    sourcePositionShipId = positionShipId.longValue();
                                    sourceMountIndex = i;
                                    break;
                                }
                            }
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // Fail-soft per beam: the source binding stays (0, -1) and the
                        // beam itself still crosses — same discipline as the content key.
                        sourcePositionShipId = 0L;
                        sourceMountIndex = -1;
                    }
                }
                if (demAttributedThisBeam) {
                    // Count what actually CROSSES, not what resolved: the mount scan above
                    // can still drop the pair back to the sentinel (no getWeapon, an
                    // unbound shipGetAllWeapons, or the weapon missing from the drone's own
                    // list), and a meter that said "attributed" there would grade a WATCH
                    // GREEN on a lane whose bytes never changed.
                    if (sourcePositionShipId != 0L) {
                        demBeamsAttributed++;
                    } else {
                        demBeamsDropped++;
                        noteDemBeamOnce("resolved the child but the drone mount scan dropped it");
                    }
                    demCensusTick();
                }
                views.add(new EngineBackedCombatEntityView(
                        id++, CombatEntityView.Kind.BEAM, owner, impact[0], impact[1],
                        firingWeaponContentKey(beam, beamClass),
                        sourcePositionShipId, sourceMountIndex));
            }
            return views;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Resolve a point entity's content key as its firing weapon's spec id
     * ({@code getWeapon().getId()}): beams (no spec id of their own) and
     * projectiles/missiles whose {@code getProjectileSpecId()} reads blank on
     * the live lane. Returns {@code ""} fail-soft when the entity exposes no weapon
     * or the weapon exposes no string id, so a weaponless entity still crosses (with
     * {@code contentId 0}) rather than dropping the entire list — this read is
     * supplementary identity, not a required field like position. A blank result
     * prints a one-shot per-cause receipt ({@code content-key-fallback miss …}):
     * the first live census interned 0 on every track while every
     * static read is clear (public class/method, constructor-set weapon field), so
     * a silent {@code ""} here would leave the cause unmeasurable.
     */
    private static String firingWeaponContentKey(Object entity, Class<?> entityClass) {
        try {
            Object weapon = entityClass.getMethod("getWeapon").invoke(entity);
            if (weapon == null) {
                noteContentKeyMiss("null_weapon entity=" + entityClass.getName());
                return "";
            }
            Object weaponId = weapon.getClass().getMethod("getId").invoke(weapon);
            String key = (weaponId instanceof String) ? ((String) weaponId).trim() : "";
            if (key.isEmpty()) {
                noteContentKeyMiss("blank_id weapon=" + weapon.getClass().getName());
            }
            return key;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause =
                    (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null)
                            ? e.getCause() : e;
            noteContentKeyMiss("error=" + cause.getClass().getName()
                    + " entity=" + entityClass.getName());
            return "";
        }
    }

    /** One line per distinct miss cause per JVM — a receipt, never a log flood. */
    private static final java.util.Set<String> CONTENT_KEY_MISSES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void noteContentKeyMiss(String cause) {
        if (CONTENT_KEY_MISSES.add(cause)) {
            System.out.println("content-key-fallback miss " + cause);
        }
    }

    // === Engine-native DEM beam attribution ===============================
    //
    // ROLLBACK KNOB (a design constraint — the fix ships behind a toggle the operator sets per
    // run): {@code -Dcc.server.demBeamSource=false} restores the earlier shape exactly —
    // the roster miss falls straight through to the sentinel pair and the client's
    // nearest-child heuristic drives the presentation as it does today. Server-side only;
    // the client needs no matching flag (an absent source id IS the off state on the wire).
    // The no-damage / no-suppression constraints are untouched by construction: this lane writes only the beam
    // record's source pair — it reads no hull, moves no damage, and adds no suppression to
    // any desync log (it strictly ADDS attribution the desync differs can key on).
    // STRICT bool, sysprop THEN env alias (the project knob convention: every cc.* sysprop
    // also reads its upper-snake env var — arm-watch/npeer-boot ferry SERVER knobs as env,
    // and the pilot lane reads no cc-server.properties, so a sysprop-only knob would be
    // unreachable on exactly the lane the owner arms). Invalid value THROWS rather than
    // silently running ON: a toggle that ignores the operator is worse than no toggle.
    static final boolean DEM_BEAM_SOURCE = resolveDemBeamSource();

    private static boolean resolveDemBeamSource() {
        String raw = System.getProperty("cc.server.demBeamSource");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("CC_SERVER_DEM_BEAM_SOURCE");
        }
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on")) {
            return true;
        }
        if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off")) {
            return false;
        }
        throw new IllegalArgumentException(
                "cc.server.demBeamSource must be true|1|yes|on|false|0|no|off; got " + raw);
    }

    /** Per-JVM one-shot receipts for the DEM attribution lane (never a per-frame flood). */
    private static final java.util.Set<String> DEM_BEAM_RECEIPTS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void noteDemBeamOnce(String cause) {
        if (DEM_BEAM_RECEIPTS.add(cause)) {
            System.out.println("dem-beam-source " + cause);
        }
    }

    /** Beam records that CROSSED carrying a DEM child id (cumulative). */
    private int demBeamsAttributed;
    /** Beam records with a non-null, non-roster source that no DEM child claimed. */
    private int demBeamsUnattributed;
    /** Resolved the child but the drone mount scan dropped it back to the sentinel. */
    private int demBeamsDropped;

    /** The attribution meters (the WATCH's SERVER-side half — the client's own
     *  {@code predict_dem_native_*} row is the other side of the same question). */
    public int demBeamsAttributed() {
        return demBeamsAttributed;
    }

    public int demBeamsUnattributed() {
        return demBeamsUnattributed;
    }

    public int demBeamsDropped() {
        return demBeamsDropped;
    }

    /**
     * Periodic cumulative census so the lane is measurable mid-run without a serve-loop
     * hook: one greppable line per {@link #DEM_CENSUS_EVERY} records this lane touched
     * (the one-shot receipts above name CAUSES; this names RATES). The no-suppression constraint:
     * additive logging only — nothing here suppresses or gates any desync line.
     */
    private static final int DEM_CENSUS_EVERY = 1000;
    private int demCensusNextAt = DEM_CENSUS_EVERY;

    private void demCensusTick() {
        int seen = demBeamsAttributed + demBeamsUnattributed + demBeamsDropped;
        if (seen >= demCensusNextAt) {
            demCensusNextAt = seen + DEM_CENSUS_EVERY;
            System.out.println("dem-beam-source census"
                    + " dem_beams_attributed=" + demBeamsAttributed
                    + " dem_beams_unattributed=" + demBeamsUnattributed
                    + " dem_beams_dropped=" + demBeamsDropped);
        }
    }

    /**
     * The DEM branch's gate: is this beam's source a NON-ROSTER (blank-id) mint — so
     * plausibly a DEM fx drone — rather than a real ship the roster map merely failed to
     * hand back? A DEM drone's id is blank BY CONSTRUCTION: {@code DEMScript} mints it
     * through {@code Global.getCombatEngine().createFXDrone(v)}, {@code createFXDrone} tags
     * the variant {@code "fx_drone"} before minting,
     * and the {@code Ship} ctor writes {@code id = ""} for an {@code fx_drone}-tagged
     * variant with no setter to undo it. So every index build
     * that resolves a child today still runs — the gate can only suppress builds for
     * sources {@link #rosterShipList()} would have KEPT, which by that same predicate
     * already carried a roster id (branch not taken) unless the roster read itself failed,
     * and there skipping IS the fix (see the meter note at the unattributed increment).
     *
     * <p>Deliberately the SAME {@link EngineBornGrowthSeam#isNonRosterMintId} law
     * {@code rosterShipList()} applies at its own predicate — one law, never a second copy
     * that could drift out of agreement with the very map this read misses against.
     *
     * <p>Fails OPEN and swallows its own reflection failures: an unbound {@code getId}
     * handle or a throwing read yields {@code true} (build and attribute exactly as before
     * the gate existed). It must NEVER throw into the enclosing per-beam catch, which would
     * blank the whole source pair AND skip the census for a beam that only failed to be
     * classified.
     */
    private boolean isNonRosterBeamSource(Object source) {
        if (shipGetId == null) {
            return true; // no classification possible — behave as before the gate
        }
        try {
            return EngineBornGrowthSeam.isNonRosterMintId(shipGetId.invoke(source));
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return true; // fail-open: an unreadable id must never cost an attribution
        }
    }

    /**
     * The reverse index the seam dig proved is the ONLY route: the engine exposes
     * no forward drone→missile link (an fx drone's {@code getDroneSource()} is null — it
     * never gets a {@code DroneAI}; its id and fleet-member id are blank/shared; its
     * variant carries no per-missile token), and {@code CombatEngineAPI} cannot enumerate
     * plugins. So walk the live missiles instead: a DEM child's own AI plugin IS its
     * {@code DEMScript} (it calls {@code missile.setMissileAI(this)},
     * before the {@code demDrone} mint), and that script's
     * {@code demDrone} is identity-equal to the beam's {@code getSource()}.
     *
     * <p>Cost: one pass over the live missile list per read that has a non-roster beam
     * source, one cached {@link Field} per distinct AI class. Fail-soft at every level —
     * an unreadable list, AI, or field yields a smaller index, never a dropped beam.
     *
     * <p>{@code getUnwrappedMissileAI} is preferred because the engine wraps a bare
     * {@code MissileAIPlugin} in its own {@code MissileAIWrapper},
     * and {@code DEMScript} implements only that interface;
     * {@code getMissileAI} is the degrade path for an engine that hands back the plugin
     * itself. The field is looked up by walking the class hierarchy so a mod-authored
     * {@code DEMScript} subclass still resolves; a subclass that renames or drops the
     * field simply leaves the beam unattributed and the client falls back to the
     * nearest-child matcher (the caveat (v) degrade).
     */
    private IdentityHashMap<Object, Object> demChildMissileByDroneShip() {
        IdentityHashMap<Object, Object> byDrone = new IdentityHashMap<>();
        if (combatGetMissiles == null) {
            return byDrone;
        }
        try {
            List<?> missiles =
                    asList(invoke(combatGetMissiles, engine, "getMissiles"), "getMissiles");
            for (Object missile : missiles) {
                if (missile == null) {
                    continue;
                }
                try {
                    Object ai = null;
                    try {
                        ai = missile.getClass().getMethod("getUnwrappedMissileAI").invoke(missile);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        ai = null;
                    }
                    if (ai == null) {
                        ai = missile.getClass().getMethod("getMissileAI").invoke(missile);
                    }
                    if (ai == null) {
                        continue;
                    }
                    Field droneField = demDroneField(ai.getClass());
                    if (droneField == null) {
                        continue;
                    }
                    Object drone = droneField.get(ai);
                    if (drone != null) {
                        byDrone.put(drone, missile);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Fail-soft per missile: a non-DEM AI or an unreadable field just
                    // leaves this missile out of the index.
                }
            }
        } catch (RuntimeException ignored) {
            // Fail-soft: no missile list this frame means no DEM attribution this frame.
        }
        return byDrone;
    }

    /** {@code demDrone} per missile-AI class, hierarchy-walked; {@code null} = not DEM. */
    private final Map<Class<?>, Field> demDroneFieldByAiClass = new HashMap<>();

    private Field demDroneField(Class<?> aiClass) {
        if (demDroneFieldByAiClass.containsKey(aiClass)) {
            return demDroneFieldByAiClass.get(aiClass);
        }
        Field found = null;
        for (Class<?> c = aiClass; c != null && found == null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("demDrone");
                f.setAccessible(true);
                found = f;
            } catch (NoSuchFieldException | RuntimeException ignored) {
                // keep walking the hierarchy; a class without the field is simply not DEM
            }
        }
        demDroneFieldByAiClass.put(aiClass, found);
        return found;
    }

    // server-lifetime stable ids for live MISSILE entities, keyed on the
    // RAW engine list elements (engine-object identity persists across frames — the same
    // assumption the reinject maps at the top of this class make). Pruned per SUCCESSFUL
    // read to the entities still listed, so a transient failed read never breaks id
    // stability and the map empties as soon as the engine list does (round end) — there is
    // no ECB-wide teardown to hook (the identity-map family prunes in-line, never clears).
    // The counter never resets: ids are unique per server lifetime, partitioned at
    // MISSILE_NETWORK_ID_BASE so they can never collide with per-frame ordinals.
    private final IdentityHashMap<Object, Long> missileStableIds = new IdentityHashMap<>();
    private long nextMissileStableId = CombatEntityView.MISSILE_NETWORK_ID_BASE;

    /** The two reflective handles behind {@link #readMoteAccelerating},
     *  resolved ONCE on the first mote (public API methods: {@code MissileAPI
     *  .getEngineController()} on the entity class, {@code ShipEngineControllerAPI
     *  .isAccelerating()} on the API interface — invoking through the interface sidesteps
     *  the controller's engine-internal concrete class). A failed resolve latches null: every later
     *  mote reads UNLIT on the wire, logged ONCE at the resolve (the former
     *  {@code moteAccelResolveFailed} boolean was written and never read). */
    private Method moteGetEngineController;
    private Method moteIsAccelerating;
    private boolean moteAccelResolveTried;
    /** {@code MissileAPI.isArmed()} behind {@link #readMissileArmed}, resolved ONCE. */
    private Method missileIsArmed;
    private boolean missileArmedResolveTried;
    private boolean missileArmedInvokeFailLogged;
    /** The shared zero-arg {@code Method.invoke} varargs array —
     *  {@code invoke(entity)} allocates a fresh {@code Object[0]} per call on the sim thread. */
    private static final Object[] NO_ARGS = new Object[0];
    /** Inventory fold: {@code getFacing()} / {@code isFizzling()} behind the
     *  per-missile pose read, resolved ONCE off {@code MissileAPI} (the former per-missile-per-frame
     *  {@code entityClass.getMethod} pair was stub-measured at 32.4 ns each, CodecContractRows (i));
     *  null = that uncached path stays. */
    private Method missileGetFacing;
    private Method missileIsFizzling;
    private boolean missilePoseResolveTried;

    /** Inventory fold: one-shot resolve of the cached pose-read pair; a failure
     *  is logged once and leaves the per-missile {@code getMethod} path in place. */
    private void resolveMissilePoseMethods(Class<?> entityClass) {
        if (missilePoseResolveTried) {
            return;
        }
        missilePoseResolveTried = true;
        try {
            Class<?> missileApi = Class.forName("com.fs.starfarer.api.combat.MissileAPI",
                    false, entityClass.getClassLoader());
            missileGetFacing = missileApi.getMethod("getFacing");
            missileIsFizzling = missileApi.getMethod("isFizzling");
        } catch (ReflectiveOperationException | RuntimeException e) {
            missileGetFacing = null;
            missileIsFizzling = null;
            System.out.println("engine-combat-binding missile-pose resolve FAILED (one-shot; the per-missile"
                    + " getMethod pair stays = today's path): " + e);
        }
    }

    /** The engine's own dud verdict for
     *  {@code NonShipEntitySnapshot#POSE_FLAG_DUD}, resolved ONCE off the API interface (the cached
     *  Method invokes on every implementor; the engine's missile class implements it). Null = no
     *  verdict (resolve failed / the invoke threw) = no bit = the armed/today path. */
    private Boolean readMissileArmed(Class<?> entityClass, Object entity) {
        if (!missileArmedResolveTried) {
            missileArmedResolveTried = true;
            try {
                missileIsArmed = Class.forName("com.fs.starfarer.api.combat.MissileAPI",
                        false, entityClass.getClassLoader()).getMethod("isArmed");
            } catch (ReflectiveOperationException | RuntimeException e) {
                missileIsArmed = null;
                System.out.println("engine-combat-binding missile-armed resolve FAILED (one-shot; every"
                        + " fizzling missile rides ARMED on the wire = today's path): " + e);
            }
        }
        if (missileIsArmed == null) {
            return null;
        }
        try {
            Object armed = missileIsArmed.invoke(entity, NO_ARGS); // no per-call varargs array
            return armed instanceof Boolean ? (Boolean) armed : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!missileArmedInvokeFailLogged) {
                missileArmedInvokeFailLogged = true;
                System.out.println("engine-combat-binding missile-armed invoke FAILED (logged once;"
                        + " that missile rides ARMED = today's path): " + e);
            }
            return null;
        }
    }

    /** Which missile spec keys mint the ACCELERATING bit — the mote
     *  specs + every FLARE spec. A flare's vanilla AI lights its engine on ITS OWN duty
     *  (SeekerFlareAI: a dark 0.5–1 s birth window, then gain-gated thrust while homing;
     *  FlareAI: every powered frame), which no replica can derive from the pose — the client
     *  puppet pinned ACCELERATE on 100 % of frames instead (the earlier mote recipe). The
     *  discriminator is the engine's own {@code MissileAPI.isFlare()}, read ONCE per spec key
     *  (a modded flare rides too; the steady-state cost is one map hit per missile). A blank
     *  key is never cached (it is shared by unrelated specs) and mints nothing. */
    private final HashMap<String, Boolean> accelBitBySpecKey = new HashMap<>();

    private boolean mintsAccelBit(String key, Class<?> entityClass, Object entity) {
        if (key.isEmpty()) {
            return false;
        }
        Boolean cached = accelBitBySpecKey.get(key);
        if (cached != null) {
            return cached;
        }
        boolean mint = key.startsWith("mote");
        if (!mint) {
            try {
                Object flare = entityClass.getMethod("isFlare").invoke(entity);
                mint = (flare instanceof Boolean) && ((Boolean) flare);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                mint = false; // fail-soft: the spec rides unlit-bit, exactly as before flares got the bit
            }
        }
        accelBitBySpecKey.put(key, mint);
        return mint;
    }

    /** The mote's (and the flare's) per-frame ACCELERATE command flag (the vanilla
     *  engine-flame truth, {@code NonShipEntitySnapshot#POSE_FLAG_ACCELERATING}). False on any
     *  failure. */
    private boolean readMoteAccelerating(Class<?> entityClass, Object entity) {
        if (!moteAccelResolveTried) {
            moteAccelResolveTried = true;
            try {
                Method getController = entityClass.getMethod("getEngineController");
                Class<?> controllerApi = Class.forName(
                        "com.fs.starfarer.api.combat.ShipEngineControllerAPI",
                        false, entityClass.getClassLoader());
                moteIsAccelerating = controllerApi.getMethod("isAccelerating");
                moteGetEngineController = getController;
            } catch (ReflectiveOperationException | RuntimeException e) {
                moteGetEngineController = null;
                moteIsAccelerating = null;
                System.out.println("engine-combat-binding mote-accel resolve FAILED (one-shot; every"
                        + " mote rides UNLIT on the wire, pose stays VALID): " + e);
            }
        }
        if (moteGetEngineController == null) {
            return false;
        }
        try {
            Object controller = moteGetEngineController.invoke(entity, NO_ARGS); // no per-call varargs array
            if (controller == null) {
                return false;
            }
            Object lit = moteIsAccelerating.invoke(controller, NO_ARGS);
            return (lit instanceof Boolean) && ((Boolean) lit);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    /**
     * The ONE mint site for a live missile's stable wire id. Called from
     * {@link #readPointEntities} (the missile tier itself) and from the
     * beam read, which runs EARLIER in the same frame: a DEM child's first payload beam
     * therefore mints the id its own missile record carries moments later, so attribution
     * is never one frame late. Callers must only pass entities they read off the LIVE
     * engine list, or the per-read {@code retainAll} prune would drop the entry the same
     * frame and the id would point at nothing.
     */
    private long missileStableIdFor(Object missile) {
        Long stableId = missileStableIds.get(missile);
        if (stableId == null) {
            stableId = nextMissileStableId++;
            missileStableIds.put(missile, stableId);
        }
        return stableId.longValue();
    }

    /**
     * READ-ONLY peek at a missile's stable wire id for the {@code beam-missile-hit}
     * rows ({@code ArcHitServeProbe}'s beam feed): {@code -1} when the missile is unlisted — NEVER
     * mints ({@link #missileStableIdFor} is the missile tier's alone: a mint from a listener callback
     * could hand an id to an entity the next {@code retainAll} prune drops). The map prunes at delist,
     * so the caller captures the id at record time and keeps it. Sim thread (the map's only thread).
     *
     */
    public long missileStableIdOrMinus1(Object missile) {
        Long stableId = missileStableIds.get(missile);
        return stableId == null ? -1L : stableId.longValue();
    }

    // per-content-key MIRV-behavior cache for the missile-split
    // detector — filled in readPointEntities, the ONLY place the live missile object and
    // its content key co-exist. ONE reflective isMirv() per NEW key (fail-soft FALSE);
    // spec behavior is per-key-stable so the entry never needs refreshing.
    private final Map<String, Boolean> mirvContentKeys = new HashMap<>();

    // per-read side channels for the split detector's projectile
    // lane — replaced at the START of each same-kind read (a failed read publishes
    // empty, never stale; worst case under a mid-tick re-read is one MISSED split =
    // the fail-open floor, never a stale false positive).
    private List<float[]> lastMirvChildBirthsXY = List.of();
    private Map<Long, MissileSplitEventDetector.EndCause> lastRemovedMissileEndCauses = Map.of();
    /** per-read side channel — stable id → the delisted missile's last ship impact. */
    private Map<Long, cc.starsector.server.runtime.MissileImpactMemo.Impact> lastRemovedMissileImpacts = Map.of();
    /** The probe-fed memo joined at delist ({@link #setMissileImpactMemo}); null = no join. */
    private cc.starsector.server.runtime.MissileImpactMemo missileImpactMemo;

    // per-read side channel + across-read identity set for the
    // Paladin-det birth probe (PROJECTILE lane). The det list and skip count follow the
    // replaced-at-start law above (never stale). The identity SET persists across
    // reads (a det lives ~6 ticks and must be born exactly once) and is swapped only on
    // a SUCCESSFUL read — a failed read never re-mints still-live dets as "new"; a det
    // born during a failed read emits one tick late instead (fail-open floor).
    private List<PaladinDet> lastPaladinDets = List.of();
    private int lastPaladinDetSkipUnknown;
    private java.util.Set<Object> seenDamagingExplosions =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /** One fingerprint-matched Paladin det BORN during the most recent PROJECTILE read:
     *  resolved source ship wire id, firing mount ordinal or
     *  {@code -1} (the live case — script dets carry no weapon), and the det birth pose. */
    public record PaladinDet(long sourceWireId, int mountIndex, float x, float y) {
    }

    /**
     * Read a list of point entities ({@code CombatEntityAPI}: projectiles or
     * missiles) into {@link CombatEntityView}s. Projectiles carry per-frame ordinal
     * ids (engine list order, 1..n); missiles carry their server-lifetime STABLE id
     * (v13a) plus the resolved firing (ship, mount) source pair (v13b —
     * the beam source family verbatim; {@code getSource}/{@code getWeapon} are
     * {@code DamagingProjectileAPI} methods, present on every live missile).
     * Per-entity API methods are resolved per element (list entries may be distinct
     * concrete engine-internal classes) and never rendered by internal name. Returns
     * {@code null} fail-soft on any null element or read failure; the source pair
     * alone degrades per missile to {@code (0, -1)}, never the record.
     *
     * <p>F1 dedupe: a {@code spawnProjectile}-born mine lives in BOTH
     * engine lists, and the missile tier owns it (stable id, emitted LAST), so the
     * PROJECTILE pass identity-skips entries present in the live missile list — else
     * one hull crosses twice per frame under two id laws, silently (the partitioned
     * {@code MISSILE_NETWORK_ID_BASE} keeps ascending-unique satisfied, nothing throws).
     */
    private List<CombatEntityView> readPointEntities(Method listAccessor, CombatEntityView.Kind kind) {
        try {
            // Side-channel reset FIRST — including before the list read itself, which can
            // throw: a read that fails ANYWHERE in this method leaves the channel EMPTY for this
            // kind, never carrying an earlier tick's births/triage (the
            // reset used to sit after the asList line, so a failed list read kept a stale
            // birth list and could mint false MISSILE_SPLIT FX on the fail-open lane).
            if (kind == CombatEntityView.Kind.PROJECTILE) {
                lastMirvChildBirthsXY = List.of();
                lastPaladinDets = List.of();
                lastPaladinDetSkipUnknown = 0;
            } else if (kind == CombatEntityView.Kind.MISSILE) {
                lastRemovedMissileEndCauses = Map.of();
                lastRemovedMissileImpacts = Map.of();
            }
            List<?> entities = asList(invoke(listAccessor, engine, kind.name()), kind.name());
            List<CombatEntityView> views = new ArrayList<>(entities.size());
            long id = 1L;
            List<float[]> mirvChildBirths = null;
            List<PaladinDet> paladinDets = null;
            int paladinSkipUnknown = 0;
            java.util.Set<Object> currentExplosions = null;
            java.util.Set<Object> liveMissiles = kind == CombatEntityView.Kind.MISSILE
                    ? java.util.Collections.newSetFromMap(new IdentityHashMap<>()) : null;
            java.util.Set<Object> missileIdentity = null;
            if (kind == CombatEntityView.Kind.PROJECTILE && combatGetMissiles != null) {
                missileIdentity = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                missileIdentity.addAll(asList(
                        invoke(combatGetMissiles, engine, "PROJECTILE.f1DedupeMissiles"),
                        "PROJECTILE.f1DedupeMissiles"));
            }
            Map<Object, Long> positionShipIds = null;
            IdentityHashMap<Object, List<?>> weaponsBySourceShip = null;
            for (Object entity : entities) {
                if (entity == null) {
                    return null;
                }
                if (missileIdentity != null && missileIdentity.contains(entity)) {
                    continue;
                }
                Class<?> entityClass = entity.getClass();
                float[] loc = readXY(entityClass.getMethod("getLocation").invoke(entity));
                if (loc == null) {
                    return null;
                }
                Object ownerValue = entityClass.getMethod("getOwner").invoke(entity);
                int owner = (ownerValue instanceof Number) ? ((Number) ownerValue).intValue() : -1;
                Object specId = entityClass.getMethod("getProjectileSpecId").invoke(entity);
                String key = (specId instanceof String) ? ((String) specId).trim() : "";
                if (key.isEmpty()) {
                    // The live lane's
                    // getProjectileSpecId reads blank, so fall back to the firing weapon's
                    // spec id — the same identity a beam carries — making every recorded
                    // .ccwr a per-weapon census.
                    key = firingWeaponContentKey(entity, entityClass);
                }
                if (liveMissiles == null) {
                    // MIRV projectile-child birth probe.
                    // isFromMissile() is set true ONLY at the MirvAI child spawns
                    // (Missile.isFromMissile() hardcoded false), and
                    // getElapsed()==0 marks the birth frame exactly (addObject runs
                    // post-advance) — so this collects the ballistic split children
                    // born this tick, no radius guessing. Fail-soft per element: an
                    // unreadable probe never claims a birth (a missed split degrades
                    // to the client's absence-grace retire).
                    try {
                        Object fromMissile =
                                entityClass.getMethod("isFromMissile").invoke(entity);
                        if (fromMissile instanceof Boolean && ((Boolean) fromMissile)) {
                            Object elapsed =
                                    entityClass.getMethod("getElapsed").invoke(entity);
                            if (elapsed instanceof Number
                                    && ((Number) elapsed).floatValue() <= 0f) {
                                if (mirvChildBirths == null) {
                                    mirvChildBirths = new ArrayList<>();
                                }
                                mirvChildBirths.add(new float[] {loc[0], loc[1]});
                            }
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                    }
                    // Paladin-det birth probe. The det crosses the
                    // snapshot lane content-LESS (script dets carry no firing weapon), so
                    // the classifiable identity exists ONLY here, where the raw engine
                    // object is in hand. Cheap simple-name discriminate first; the spec
                    // fingerprint runs on NEW objects only (identity-set diff across
                    // PROJECTILE reads — object identity, never the per-frame ordinal ids,
                    // which reshuffle every read).
                    if ("DamagingExplosion".equals(entityClass.getSimpleName())) {
                        if (currentExplosions == null) {
                            currentExplosions =
                                    java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                        }
                        currentExplosions.add(entity);
                        if (!seenDamagingExplosions.contains(entity)) {
                            try {
                                long sourceWireId = 0L;
                                Object source = entityClass.getMethod("getSource").invoke(entity);
                                if (source != null) {
                                    if (positionShipIds == null) {
                                        positionShipIds = positionShipIdByEngineShip();
                                        weaponsBySourceShip = new IdentityHashMap<>();
                                    }
                                    Long positionShipId = positionShipIds == null
                                            ? null : positionShipIds.get(source);
                                    if (positionShipId != null) {
                                        sourceWireId = positionShipId.longValue();
                                    }
                                }
                                if (sourceWireId > 0L
                                        && Float.isFinite(loc[0]) && Float.isFinite(loc[1])
                                        && isPaladinDetSpec(entity, entityClass)) {
                                    // getWeapon() is null for script-spawned dets (the live
                                    // case); a non-null weapon still resolves its ordinal so
                                    // the mount slot never lies.
                                    int mountIndex = -1;
                                    Object weapon =
                                            entityClass.getMethod("getWeapon").invoke(entity);
                                    if (weapon != null) {
                                        List<?> weapons = weaponsBySourceShip.get(source);
                                        if (weapons == null) {
                                            Object weaponsObj = shipGetAllWeapons.invoke(source);
                                            weapons = (weaponsObj instanceof List<?>)
                                                    ? (List<?>) weaponsObj : List.of();
                                            weaponsBySourceShip.put(source, weapons);
                                        }
                                        for (int i = 0; i < weapons.size(); i++) {
                                            if (weapons.get(i) == weapon) {
                                                mountIndex = i;
                                                break;
                                            }
                                        }
                                    }
                                    if (paladinDets == null) {
                                        paladinDets = new ArrayList<>();
                                    }
                                    paladinDets.add(new PaladinDet(
                                            sourceWireId, mountIndex, loc[0], loc[1]));
                                } else {
                                    // Whitelist law: any other NEW script det — wrong
                                    // fingerprint, unresolvable source ship, or non-finite
                                    // pose — is counted+skipped, never guessed.
                                    paladinSkipUnknown++;
                                }
                            } catch (ReflectiveOperationException | RuntimeException ignored) {
                                // Fail-soft per det: an unreadable probe never claims a
                                // det; the count keeps the miss visible.
                                paladinSkipUnknown++;
                            }
                        }
                    }
                    views.add(new EngineBackedCombatEntityView(id++, kind, owner, loc[0], loc[1], key));
                    continue;
                }
                liveMissiles.add(entity);
                if (!key.isEmpty() && !mirvContentKeys.containsKey(key)) {
                    Boolean mirv = Boolean.FALSE;
                    try {
                        Object isMirv = entityClass.getMethod("isMirv").invoke(entity);
                        mirv = (isMirv instanceof Boolean) && ((Boolean) isMirv);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // Fail-soft FALSE: an unreadable spec never claims MIRV behavior
                        // (a missed split degrades to the absence-grace retire, never FX
                        // on a non-splitting missile).
                    }
                    mirvContentKeys.put(key, mirv);
                }
                // Capture freshness BEFORE the mint so the
                // weaponless-mine spawn row below fires ONE-PER-LIFETIME (first read only).
                boolean freshMissile = !missileStableIds.containsKey(entity);
                long stableId = missileStableIdFor(entity);
                long sourcePositionShipId = 0L;
                int sourceMountIndex = -1;
                try {
                    Object source = entityClass.getMethod("getSource").invoke(entity);
                    if (source != null) {
                        if (positionShipIds == null) {
                            positionShipIds = positionShipIdByEngineShip();
                            weaponsBySourceShip = new IdentityHashMap<>();
                        }
                        Long positionShipId =
                                positionShipIds == null ? null : positionShipIds.get(source);
                        Object weapon = positionShipId == null
                                ? null : entityClass.getMethod("getWeapon").invoke(entity);
                        if (weapon != null) {
                            List<?> weapons = weaponsBySourceShip.get(source);
                            if (weapons == null) {
                                Object weaponsObj = shipGetAllWeapons.invoke(source);
                                weapons = (weaponsObj instanceof List<?>)
                                        ? (List<?>) weaponsObj : List.of();
                                weaponsBySourceShip.put(source, weapons);
                            }
                            for (int i = 0; i < weapons.size(); i++) {
                                if (weapons.get(i) == weapon) {
                                    sourcePositionShipId = positionShipId.longValue();
                                    sourceMountIndex = i;
                                    break;
                                }
                            }
                        } else if (positionShipId != null) {
                            // WEAPONLESS-SOURCED — a ship-system script
                            // spawn (MineStrikeStats calls spawnProjectile(ship, null, "minelayer2",
                            // ...)) has a live source ship and no mount. A test match measured
                            // the (0, -1) degrade here as the reported "double mines": the wire
                            // mine crossed sourceless, so the firer's own client adopted it as a
                            // second body beside its local newborn. Emit (ship, -1): the own
                            // client links its newborn by the pair, other clients route mount
                            // -1 to the sourceless arm exactly as before.
                            sourcePositionShipId = positionShipId.longValue();
                            sourceMountIndex = -1;
                            // Grade the placement divergence
                            // (client ~1000 su ahead vs server ~420 off-nose) — emit the mine's loc,
                            // the source ship's loc/facing/getMouseTarget() aim + derived geometry,
                            // ONE-PER-LIFETIME (event-rate; own fail-soft, never throws
                            // into the source-pair catch below).
                            if (freshMissile) {
                                emitMineSpawnRow(stableId, sourcePositionShipId, key, loc, source, entity);
                            }
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Fail-soft per missile: the source binding stays (0, -1) and the
                    // missile itself still crosses — the beam family's discipline.
                    sourcePositionShipId = 0L;
                    sourceMountIndex = -1;
                }
                // The v16 pose read: CombatEntityAPI.getFacing() +
                // MissileAPI.isFizzling(), quantized ONCE here onto the u16 grid
                // (never re-derived downstream). Whole-or-not-at-all fail-soft per
                // missile — any failure leaves the (0, 0) sentinel pose and the
                // missile still crosses (the source-pair discipline above).
                int missileFacingU16 = 0;
                int missilePoseFlags = 0;
                try {
                    // Inventory fold: the pair is resolved ONCE off MissileAPI
                    // (a non-missile entity fails the invoke the same way it failed the lookup).
                    resolveMissilePoseMethods(entityClass);
                    Object facingValue = (missileGetFacing != null ? missileGetFacing
                            : entityClass.getMethod("getFacing")).invoke(entity, NO_ARGS);
                    Object fizzlingValue = (missileIsFizzling != null ? missileIsFizzling
                            : entityClass.getMethod("isFizzling")).invoke(entity, NO_ARGS);
                    if (facingValue instanceof Number && fizzlingValue instanceof Boolean) {
                        float facingDeg = ((Number) facingValue).floatValue();
                        if (Float.isFinite(facingDeg)) {
                            missileFacingU16 = Math.floorMod(
                                    Math.round(facingDeg
                                            / NonShipEntitySnapshot.MISSILE_POSE_FACING_STEP_DEG),
                                    65536);
                            missilePoseFlags = NonShipEntitySnapshot.POSE_FLAG_VALID
                                    | (((Boolean) fizzlingValue)
                                            ? NonShipEntitySnapshot.POSE_FLAG_FIZZLING : 0);
                            // The DUD bit, read on FIZZLING frames ONLY (else one bit test;
                            // minimum engine read, on-thread: the bit IS engine state).
                            // The mint takes the REAL fizzling bit (honest to its input —
                            // inside this gate it is always true; row (g) pins dudFlag itself, not this site).
                            boolean fizzlingFrame =
                                    (missilePoseFlags & NonShipEntitySnapshot.POSE_FLAG_FIZZLING) != 0;
                            if (fizzlingFrame) {
                                Boolean armed = readMissileArmed(entityClass, entity);
                                if (armed != null) {
                                    missilePoseFlags |= NonShipEntitySnapshot.dudFlag(fizzlingFrame, armed);
                                }
                            }
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    missileFacingU16 = 0;
                    missilePoseFlags = 0;
                }
                // The mote engine-flame bit — MOTE specs, + FLARE specs
                // later (the per-missile reflective read stays off every other
                // missile: per-shot cost multiplies by fire rate × players). Fail-soft per
                // missile: an unreadable controller leaves the bit 0 (unlit), pose stays VALID.
                if (missilePoseFlags != 0 && mintsAccelBit(key, entityClass, entity)
                        && readMoteAccelerating(entityClass, entity)) {
                    missilePoseFlags |= NonShipEntitySnapshot.POSE_FLAG_ACCELERATING;
                }
                views.add(new EngineBackedCombatEntityView(
                        stableId, kind, owner, loc[0], loc[1], key,
                        sourcePositionShipId, sourceMountIndex,
                        missileFacingU16, missilePoseFlags));
            }
            if (liveMissiles != null) {
                // end-cause triage of every missile delisted this
                // read, BEFORE the prune drops its entry — the retained entity stays
                // readable post-mortem (delist only drops list membership). Fail-soft
                // per missile: an unreadable post-mortem stays ABSENT from the map,
                // which the detector's projectile lane treats as a veto (never false
                // split FX).
                Map<Long, MissileSplitEventDetector.EndCause> endCauses = null;
                Map<Long, cc.starsector.server.runtime.MissileImpactMemo.Impact> impacts = null;
                for (Map.Entry<Object, Long> removed : missileStableIds.entrySet()) {
                    Object missile = removed.getKey();
                    if (liveMissiles.contains(missile)) {
                        continue;
                    }
                    try {
                        Class<?> missileClass = missile.getClass();
                        Object hp = missileClass.getMethod("getHitpoints").invoke(missile);
                        Object fizzling = missileClass.getMethod("isFizzling").invoke(missile);
                        if (hp instanceof Number && fizzling instanceof Boolean) {
                            MissileSplitEventDetector.EndCause cause =
                                    ((Number) hp).floatValue() <= 0f
                                            ? MissileSplitEventDetector.EndCause.DESTROYED
                                            : ((Boolean) fizzling)
                                                    ? MissileSplitEventDetector.EndCause.FIZZLING
                                                    : MissileSplitEventDetector.EndCause.INTACT;
                            if (endCauses == null) {
                                endCauses = new HashMap<>();
                            }
                            endCauses.put(removed.getValue(), cause);
                            // Join the probe's last-impact memo onto the
                            // delisted id (take-and-clear); no memo = the server saw no ship hit.
                            cc.starsector.server.runtime.MissileImpactMemo.Impact impact =
                                    missileImpactMemo == null ? null : missileImpactMemo.take(missile);
                            if (impact != null) {
                                if (impacts == null) {
                                    impacts = new HashMap<>();
                                }
                                impacts.put(removed.getValue(), impact);
                            }
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                    }
                }
                if (endCauses != null) {
                    lastRemovedMissileEndCauses = Map.copyOf(endCauses);
                }
                if (impacts != null) {
                    lastRemovedMissileImpacts = Map.copyOf(impacts);
                }
                missileStableIds.keySet().retainAll(liveMissiles);
                if (missileImpactMemo != null) {
                    missileImpactMemo.retainLive(liveMissiles); // the leak guard: unconsumed memos of dead missiles
                }
            }
            if (kind == CombatEntityView.Kind.PROJECTILE) {
                if (mirvChildBirths != null) {
                    lastMirvChildBirthsXY = List.copyOf(mirvChildBirths);
                }
                if (paladinDets != null) {
                    lastPaladinDets = List.copyOf(paladinDets);
                }
                lastPaladinDetSkipUnknown = paladinSkipUnknown;
                // Swap the across-read identity set ONLY on a successful read (field doc).
                seenDamagingExplosions = currentExplosions != null
                        ? currentExplosions
                        : java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            }
            return views;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A nulled read is fail-closed for the whole kind (the
            // client sees no bodies of it) and used to leave NO receipt — the EngineBridgeSmoke
            // missile rows failed silently until this line named the throwing read. Once per
            // kind per binding: never a per-tick line.
            if (pointReadFailLogged.add(kind)) {
                System.out.println("engine-binding point-read FAILED kind=" + kind
                        + " (fail-closed null for every read until it resolves): " + e);
            }
            return null;
        }
    }

    /** {@link #readPointEntities} once-per-kind failure receipt latch. */
    private final java.util.Set<CombatEntityView.Kind> pointReadFailLogged =
            java.util.EnumSet.noneOf(CombatEntityView.Kind.class);

    /**
     * Whitelist fingerprint: the immutable
     * {@code DamagingExplosionSpec} the det was spawned with reads radius 75 /
     * maxDamage 100 / FRAGMENTATION — {@code PaladinEffect.createExplosionSpec}
     * verbatim (the spec route, NOT the live {@code getDamageAmount()}, which mutates
     * per frag hit; ship-death explosions read HIGH_EXPLOSIVE + ship-sized radius, so
     * the triple cleanly excludes them). Any read failure answers {@code false}
     * (fail-soft skip — the caller counts it).
     */
    private static boolean isPaladinDetSpec(Object entity, Class<?> entityClass) {
        try {
            Object spec = entityClass.getMethod("getExplosionSpecIfExplosion").invoke(entity);
            if (spec == null) {
                return false;
            }
            Class<?> specClass = spec.getClass();
            Object radius = specClass.getMethod("getRadius").invoke(spec);
            Object maxDamage = specClass.getMethod("getMaxDamage").invoke(spec);
            Object damageType = specClass.getMethod("getDamageType").invoke(spec);
            return radius instanceof Number
                    && Math.abs(((Number) radius).floatValue() - 75f) <= 0.01f
                    && maxDamage instanceof Number
                    && Math.abs(((Number) maxDamage).floatValue() - 100f) <= 0.01f
                    && damageType instanceof Enum<?>
                    && "FRAGMENTATION".equals(((Enum<?>) damageType).name());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether the given non-ship content key names a MIRV-behaviored missile spec
     * ({@code MissileAPI.isMirv()}), per the cache {@code readPointEntities} fills —
     * the missile-split detector's resolver. {@code null}, unknown,
     * and never-observed keys answer {@code false} (fail-soft).
     */
    public boolean isMirvContentKey(String contentKey) {
        return contentKey != null && Boolean.TRUE.equals(mirvContentKeys.get(contentKey));
    }

    /**
     * (x,y) of every MIRV projectile-child BORN during the most recent PROJECTILE
     * read ({@code isFromMissile() ∧ getElapsed()<=0}) — the split detector's
     * sabot-lane side channel. Empty when none were born or the read
     * failed (never stale).
     */
    public List<float[]> lastMirvChildBirthsXY() {
        return lastMirvChildBirthsXY;
    }

    /**
     * Stable id → post-mortem {@link MissileSplitEventDetector.EndCause} for every
     * missile DELISTED during the most recent MISSILE read (triaged before the
     * {@code retainAll} prune) — the split detector's false-FX veto
     * side channel. A missile absent here was unreadable (the detector vetoes it).
     */
    public Map<Long, MissileSplitEventDetector.EndCause> lastRemovedMissileEndCauses() {
        return lastRemovedMissileEndCauses;
    }

    /**
     * Stable id → the delisted missile's last ship impact (engine victim object +
     * shield verdict) for every id in {@link #lastRemovedMissileEndCauses()} whose memo existed —
     * resolved to wire ids by the serve loop ({@code MissileImpactMemo.toContacts}).
     */
    public Map<Long, cc.starsector.server.runtime.MissileImpactMemo.Impact> lastRemovedMissileImpacts() {
        return lastRemovedMissileImpacts;
    }

    /** Install the probe-fed last-impact memo the missile read joins at delist. */
    public void setMissileImpactMemo(cc.starsector.server.runtime.MissileImpactMemo memo) {
        this.missileImpactMemo = memo;
    }

    /**
     * Every fingerprint-matched Paladin det BORN during the most recent PROJECTILE read
     * (the GPEE v10 {@code PALADIN_DET} producer's side channel).
     * Empty when none were born or the read failed (never stale).
     */
    public List<PaladinDet> lastPaladinDets() {
        return lastPaladinDets;
    }

    /**
     * How many NEW {@code DamagingExplosion}s the most recent PROJECTILE read
     * counted+skipped under the whitelist law (non-Paladin fingerprint,
     * unresolvable source ship, non-finite pose, or unreadable probe — never guessed,
     * never emitted). Zero when the read failed (the reset law).
     */
    public int lastPaladinDetSkipUnknown() {
        return lastPaladinDetSkipUnknown;
    }

    /**
     * Combat-wide OUTCOME / end-condition read off the engine field: the
     * public {@code CombatEngineAPI.getWinningSideId()} ({@code -1} until a side is
     * eliminated, then a concrete {@code FleetSide} ordinal) and {@code isCombatOver()}
     * (the engine's own combat-over flag, render-coupled in the engine — see
     * {@link CombatOutcomeView}). Returns {@code null} (fail-soft) when either handle is
     * unbound or a read yields a non-Number side id / non-Boolean flag.
     *
     * <p><b>Diagnostic/parity only on the headless path.</b> This field's writer
     * computes it entirely from
     * {@code CombatFleetManager} deployment bookkeeping, which the headless roster install
     * deliberately empties — so headless it latches a <em>spurious early
     * winner</em> blind to the ships actually fighting in the object repository. A headless
     * server therefore derives the authoritative outcome from its own roster via
     * {@link #combatOutcomeFromRosterOrNull()}; this engine read is retained only as a
     * diagnostic/parity signal (e.g. a rendered-run cross-check), never as the producer of
     * the authoritative headless verdict.
     */
    public CombatOutcomeView combatOutcomeOrNull() {
        if (engineGetWinningSideId == null || engineIsCombatOver == null) {
            return null;
        }
        try {
            Object sideValue = engineGetWinningSideId.invoke(engine);
            if (!(sideValue instanceof Number)) {
                return null;
            }
            Boolean over = asBoolean(engineIsCombatOver.invoke(engine));
            if (over == null) {
                return null;
            }
            return new EngineBackedCombatOutcomeView(((Number) sideValue).intValue(), over);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Per-ship combat ALLEGIANCE snapshots (side + liveness) for every installed ship,
     * in repository order, or {@code null} when the allegiance handles are not bound or
     * any read fails / yields a non-Number owner or non-Boolean liveness. The reads are
     * {@code CombatEntityAPI.getOwner()} (the owning {@code FleetSide} ordinal) and the
     * {@code ShipAPI.isAlive()} liveness, on each engine ship.
     *
     * <p>This is the roster tier the headless server folds into the authoritative combat
     * OUTCOME: {@link #combatOutcomeFromRosterOrNull()} derives "which side
     * still has live ships" from it, instead of trusting the engine's
     * {@code getWinningSideId()} field (which is fleet-manager-derived and blind to the
     * object-repository roster headless). The within-tick {@code shipId}
     * matches the motion/durability/lifecycle views read in the same tick.
     */
    public List<ShipAllegianceView> shipAllegiancesOrNull() {
        return shipAllegiancesOrNull(-1);
    }

    /**
     * The same read, with {@link ShipAllegianceView#seat()} FLAGGED on every
     * player seat's live body + parts that fights for {@code seatFlagSide} (negative = no flag
     * asked, the plain read). Side-scoped on purpose: the one consumer (the wave-clear count)
     * only cares about seats on the WAVE side, and {@link SeatHullParts#seatOwnedOnSide} answers
     * the shared empty set — one {@code getOwner} per mounted seat, no parts walk, no
     * allocation — on every tick nobody stands there (the frame-residency law).
     */
    public List<ShipAllegianceView> shipAllegiancesOrNull(int seatFlagSide) {
        if (shipGetOwner == null || shipIsAlive == null) {
            return null;
        }
        try {
            List<?> ships = rosterShipList();
            java.util.Set<Object> seatOwned = seatFlagSide < 0
                    ? java.util.Collections.<Object>emptySet()
                    : SeatHullParts.seatOwnedOnSide(seatFlagSide);
            List<ShipAllegianceView> allegiances = new ArrayList<>(ships.size());
            long shipId = 1L;
            for (Object ship : ships) {
                if (ship == null) {
                    return null;
                }
                Object ownerValue = shipGetOwner.invoke(ship);
                if (!(ownerValue instanceof Number)) {
                    return null;
                }
                Object aliveValue = shipIsAlive.invoke(ship);
                if (!(aliveValue instanceof Boolean)) {
                    return null;
                }
                allegiances.add(new EngineBackedShipAllegianceView(
                        shipId++, ((Number) ownerValue).intValue(), (Boolean) aliveValue,
                        !seatOwned.isEmpty() && seatOwned.contains(ship)));
            }
            return allegiances;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * One W3 transfer-census hull: the ship's engine stable id (the registry
     * join key — the census caller resolves wire ids, the binding never sees them) plus its
     * display name ({@code getName()} is nullable engine-side; the census label sanitizer owns
     * the fallback).
     */
    public record TransferCensusRow(String stableId, String shipNameOrNull) {}

    /** Lazily-resolved handles for {@link #transferCensusOrNull()} (resolved once; a resolve
     * failure latches null and the census stays honestly unavailable). */
    private Method[] transferCensusHandles;
    private boolean transferCensusHandlesTried;

    /**
     * W3 transfer-census read: every ALIVE hull whose
     * live owner is the asked side, non-fighter/hulk/stationModule/shuttlePod/station, in roster order — exactly the roster
     * verb's eligibility filter ({@code SeatRebind.transferSeatToHull}), so the
     * census never lists what the verb would deny. One bounded reflective pass; the caller
     * gates cadence (dirty-flag + publish belt — NEVER per-tick, the frame-residency law).
     * Whole-read fail-soft null on ANY anomaly (the {@link #shipAllegiancesOrNull} discipline).
     */
    /** Lazy one-shot resolve for {@link #transferCensusHandles} (shared by the census and the
     * W3-G flight-phase alive read); {@code true} when the handle set is usable. */
    private boolean ensureTransferCensusHandles() {
        if (!transferCensusHandlesTried) {
            transferCensusHandlesTried = true;
            try {
                // isStation excludes the station ROOT hull (which answers
                // isStationModule FALSE — only its child armor modules are modules); without
                // it the root rode the census as an ordinary row (the "01 (free)" listing;
                // owner oracle: vanilla transfer offers allied SHIPS only).
                transferCensusHandles = new Method[] {
                        shipClass.getMethod("isFighter"), shipClass.getMethod("isHulk"),
                        shipClass.getMethod("isStationModule"),
                        shipClass.getMethod("isShuttlePod"), shipClass.getMethod("isStation"),
                        shipClass.getMethod("getName") };
            } catch (ReflectiveOperationException | RuntimeException e) {
                transferCensusHandles = null;
            }
        }
        return transferCensusHandles != null;
    }

    /** The side-0 census (the fortress / 1v1 table): {@link #transferCensusOrNull(int)} at 0. */
    public List<TransferCensusRow> transferCensusOrNull() {
        return transferCensusOrNull(0);
    }

    /**
     *
     * The census for ONE team: the hulls whose LIVE owner reads {@code side} (0 = south, 1 =
     * north). A seat may only take a hull of its own team, so each side gets its own table
     * and the other team's hulls never list. Side 0 lists exactly what the pre-team census
     * listed. THROWS on a side outside 0..1 (a wiring bug, never a per-read anomaly).
     */
    public List<TransferCensusRow> transferCensusOrNull(int side) {
        if (side != 0 && side != 1) {
            throw new IllegalArgumentException("transfer census side must be 0 or 1, got " + side);
        }
        if (shipGetOwner == null || shipIsAlive == null || shipGetId == null) {
            return null;
        }
        if (!ensureTransferCensusHandles()) {
            return null;
        }
        try {
            List<?> ships = rosterShipList();
            List<TransferCensusRow> rows = new ArrayList<>();
            for (Object ship : ships) {
                if (ship == null) {
                    return null;
                }
                Object ownerValue = shipGetOwner.invoke(ship);
                Object aliveValue = shipIsAlive.invoke(ship);
                if (!(ownerValue instanceof Number) || !(aliveValue instanceof Boolean)) {
                    return null;
                }
                if (((Number) ownerValue).intValue() != side || !((Boolean) aliveValue)) {
                    continue;
                }
                boolean excluded = false;
                for (int i = 0; i < 5; i++) {
                    Object flag = transferCensusHandles[i].invoke(ship);
                    if (!(flag instanceof Boolean)) {
                        return null;
                    }
                    excluded |= (Boolean) flag;
                }
                if (excluded) {
                    continue;
                }
                Object id = shipGetId.invoke(ship);
                if (!(id instanceof String) || ((String) id).isEmpty()) {
                    return null;
                }
                Object name = transferCensusHandles[5].invoke(ship);
                rows.add(new TransferCensusRow((String) id,
                        name instanceof String ? (String) name : null));
            }
            return rows;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * W3-G flight-phase alive re-check: is the hull with this engine
     * stable id still pilotable-alive — {@code isAlive() && !isHulk()}, the census's own
     * aliveness law — so the transfer drain can abort a pod whose target died-to-hulk
     * mid-flight (a hulk stays in the world and emits NO despawn, so the despawn-keyed
     * abort never fires for it; the registry-presence drain check keeps a hulk's row
     * marching to a dock-time deny seconds later). Called once per in-flight row per tick
     * (rare — frame-residency law satisfied). Fail-CLOSED: a null/absent id, unresolved
     * handles, or any reflective anomaly answers {@code false} (= abort the flight).
     */
    public boolean isTransferTargetAlive(String stableId) {
        if (stableId == null || stableId.isEmpty() || shipIsAlive == null || shipGetId == null
                || !ensureTransferCensusHandles()) {
            return false;
        }
        try {
            for (Object ship : rosterShipList()) {
                if (ship == null) {
                    return false;
                }
                if (!stableId.equals(shipGetId.invoke(ship))) {
                    continue;
                }
                Object alive = shipIsAlive.invoke(ship);
                Object hulk = transferCensusHandles[1].invoke(ship);
                return alive instanceof Boolean && hulk instanceof Boolean
                        && ((Boolean) alive) && !((Boolean) hulk);
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * vanilla-parity floor: is the hull with this
     * stable id a LINGERING HULK — {@code isHulk()} and still on the engine's ship list (a
     * fully destroyed / exploded wreck leaves it) — so a dead seat may still launch its
     * command shuttle FROM the wreck. Vanilla lets a dead player transfer out of an intact
     * hulk (the engine's transfer check guards the target only); the "you have no ship" refusal was ours.
     * Called once per unmounted in-flight row per tick (rare). Fail-CLOSED like
     * {@link #isTransferTargetAlive}: unknown / unresolvable ⇒ {@code false} (= abort).
     */
    public boolean isSeatHulkInPlay(String stableId) {
        if (stableId == null || stableId.isEmpty() || shipGetId == null
                || !ensureTransferCensusHandles()) {
            return false;
        }
        try {
            for (Object ship : rosterShipList()) {
                if (ship == null || !stableId.equals(shipGetId.invoke(ship))) {
                    continue;
                }
                Object hulk = transferCensusHandles[1].invoke(ship);
                return hulk instanceof Boolean && ((Boolean) hulk);
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Per-ship engine-disabled/flameout state (schema v6 source) for every
     * installed ship, in repository order, or {@code null} when the engine-state handles
     * are not bound or any read fails. All reads are public API surface invoked
     * reflectively on the engine objects: {@code ShipAPI.getEngineController()}, the
     * controller's {@code getShipEngines()} / {@code isFlamingOut()} / {@code isDisabled()},
     * and per engine {@code isDisabled()} / {@code isPermanentlyDisabled()}.
     *
     * <p>Engine bit {@code i} is the {@code getShipEngines()} list index — spec order,
     * deterministic per variant on both sides; engines beyond index 31 are not carried
     * (fail-soft, per the design). The controller's derived 3-state code is
     * carried for METERS/validation only: {@code isFlamingOut()} ⇒ FLAMING_OUT, else
     * controller {@code isDisabled()} ⇒ FLAMED_OUT, else ACTIVE (the controller's
     * {@code isFlamedOut()} is the OR of the two non-ACTIVE states). The within-tick
     * {@code shipId} matches the motion/durability/flux views read in the same tick.
     */
    public List<ShipEngineStateView> shipEngineStatesOrNull() {
        if (shipGetEngineController == null || controllerGetShipEngines == null
                || controllerIsFlamingOut == null || controllerIsDisabled == null
                || engineIsDisabled == null || engineIsPermanentlyDisabled == null) {
            return null;
        }
        try {
            List<?> ships = rosterShipList();
            List<ShipEngineStateView> states = new ArrayList<>(ships.size());
            long shipId = 1L;
            for (Object ship : ships) {
                if (ship == null) {
                    return null;
                }
                Object controller = shipGetEngineController.invoke(ship);
                if (controller == null) {
                    return null;
                }
                Object enginesValue = controllerGetShipEngines.invoke(controller);
                if (!(enginesValue instanceof List)) {
                    return null;
                }
                List<?> engines = (List<?>) enginesValue;
                int disabledMask = 0;
                int permDisabledMask = 0;
                int carried = Math.min(engines.size(), 32);
                for (int i = 0; i < carried; i++) {
                    Object engine = engines.get(i);
                    if (engine == null) {
                        return null;
                    }
                    Object disabledValue = engineIsDisabled.invoke(engine);
                    Object permValue = engineIsPermanentlyDisabled.invoke(engine);
                    if (!(disabledValue instanceof Boolean) || !(permValue instanceof Boolean)) {
                        return null;
                    }
                    if ((Boolean) disabledValue) {
                        disabledMask |= 1 << i;
                    }
                    if ((Boolean) permValue) {
                        permDisabledMask |= 1 << i;
                    }
                }
                Object flamingOutValue = controllerIsFlamingOut.invoke(controller);
                Object flamedOutValue = controllerIsDisabled.invoke(controller);
                if (!(flamingOutValue instanceof Boolean) || !(flamedOutValue instanceof Boolean)) {
                    return null;
                }
                // 0/1/2 = ShipEngineStateSnapshot CONTROLLER_ACTIVE/FLAMING_OUT/FLAMED_OUT
                int controllerState = (Boolean) flamingOutValue
                        ? 1
                        : ((Boolean) flamedOutValue ? 2 : 0);
                states.add(new EngineBackedShipEngineStateView(
                        shipId++, disabledMask, permDisabledMask, controllerState));
            }
            return states;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Per-ship maneuver-COMMAND state (schema v7 source) for every installed
     * ship, in repository order, or {@code null} when the maneuver-command handles are
     * not bound or any read fails. All reads are public API surface invoked reflectively
     * on the engine objects: {@code ShipAPI.getEngineController()} then the controller's
     * 7 maneuver booleans ({@code isAccelerating} … {@code isStrafingRight}), plus — as the
     * byte's free bit 7 ({@link ShipManeuverCommandView#BIT_ENGINE_FLARE}) — the engine
     * glow's own "flaring now" flag read by {@link #readEngineFlareFlag(Object)} in the
     * SAME per-ship pass: a command-less
     * glow (vanilla's drone follower pokes the glow directly)
     * otherwise never reaches the wire. The flare read is fail-soft per ship (unreadable ⇒
     * bit clear), never a tier-wide null.
     *
     * <p>Bit {@code i} of each mask is the canonical wire-bit order the UP-lane
     * {@code PilotIntentFrame.heldControls} uses ({@code WIRE_BIT_ACCELERATE} …
     * {@code WIRE_BIT_STRAFE_RIGHT}, bits 0..6) — mirror-symmetric with the client
     * mod's capture of its own pilot mask. Reading the controller (not echoing the
     * staged UP mask) keeps the source UNIFORM across seat-piloted and AI ships (D4
     * gauntlet foes have no UP frames); a seat ship's TURN bits read false because the
     * server replay drops turn — harmless, TURN never flares and facing is
     * pose-carried. The within-tick {@code shipId} matches the motion/durability/
     * engine-state views read in the same tick.
     */
    public List<ShipManeuverCommandView> shipManeuverCommandsOrNull() {
        if (maneuverGetEngineController == null || controllerIsAccelerating == null
                || controllerIsAcceleratingBackwards == null || controllerIsDecelerating == null
                || controllerIsTurningLeft == null || controllerIsTurningRight == null
                || controllerIsStrafingLeft == null || controllerIsStrafingRight == null) {
            return null;
        }
        // Index = wire bit: WIRE_BIT_ACCELERATE(0), ACCELERATE_BACKWARDS(1), DECELERATE(2),
        // TURN_LEFT(3), TURN_RIGHT(4), STRAFE_LEFT(5), STRAFE_RIGHT(6).
        Method[] bitReads = {
                controllerIsAccelerating,
                controllerIsAcceleratingBackwards,
                controllerIsDecelerating,
                controllerIsTurningLeft,
                controllerIsTurningRight,
                controllerIsStrafingLeft,
                controllerIsStrafingRight
        };
        try {
            List<?> ships = rosterShipList();
            List<ShipManeuverCommandView> commands = new ArrayList<>(ships.size());
            long shipId = 1L;
            for (Object ship : ships) {
                if (ship == null) {
                    return null;
                }
                Object controller = maneuverGetEngineController.invoke(ship);
                if (controller == null) {
                    return null;
                }
                int maneuverMask = 0;
                for (int bit = 0; bit < bitReads.length; bit++) {
                    Object value = bitReads[bit].invoke(controller);
                    if (!(value instanceof Boolean)) {
                        return null;
                    }
                    if ((Boolean) value) {
                        maneuverMask |= 1 << bit;
                    }
                }
                if (readEngineFlareFlag(controller)) {
                    maneuverMask |= 1 << ShipManeuverCommandView.BIT_ENGINE_FLARE;
                }
                commands.add(new EngineBackedShipManeuverCommandView(shipId++, maneuverMask));
            }
            return commands;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Engine-internal name of the glow's "flaring now" getter (zero-arg boolean).
     * The name is
     * pinned to the installed engine jar like every engine-internal identity; a rename resolves to nothing and
     * the bit simply never sets (see {@link #readEngineFlareFlag(Object)}).
     */
    private static final String GLOW_IS_FLARING_OBF =
            Character.toString(0xD3) + "00000"; // U+00D3 then five zeros, pure-ASCII source
    private Method flareGetEngineGlow;   // impl-controller public getEngineGlow()
    private Method flareIsFlaring;       // the glow's flaring getter, lazily bound from the live instance
    private boolean flareUnresolvable;   // one resolve attempt; failure ⇒ bit 7 never set

    /**
     * Read the engine's REAL flare flag for one
     * ship — the flaring getter on {@code controller.getEngineGlow()}, reading the boolean the
     * glow's own per-frame integration sets while a show call is live
     * (that integration runs headless). Bound lazily from the first live controller
     * (the impl class, not the API interface — {@code getEngineGlow} is engine-internal class surface,
     * legal-name reflection like {@code TransferPodSpawner}'s map edges); resolve failure
     * logs once and disables ONLY this bit, an invoke failure clears the bit for that ship
     * this tick. Sim-thread resident by construction: called inside the existing per-entity
     * maneuver read, one extra invoke pair per ship, no off-frame compute.
     */
    private boolean readEngineFlareFlag(Object controller) {
        if (flareUnresolvable || controller == null) {
            return false;
        }
        try {
            if (flareIsFlaring == null) {
                Method getGlow = controller.getClass().getMethod("getEngineGlow");
                Object glow = getGlow.invoke(controller);
                if (glow == null) {
                    return false; // no glow yet on this ship; retry next read
                }
                Method isFlaring = glow.getClass().getMethod(GLOW_IS_FLARING_OBF);
                if (isFlaring.getReturnType() != boolean.class) {
                    throw new NoSuchMethodException(GLOW_IS_FLARING_OBF + " is not boolean()");
                }
                flareGetEngineGlow = getGlow;
                flareIsFlaring = isFlaring;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            flareUnresolvable = true;
            System.out.println("maneuver-flare bit 7 OFF (glow getter unresolvable; commands"
                    + " still carry): " + e.getClass().getName() + ": "
                    + (e.getMessage() == null ? "" : e.getMessage()));
            return false;
        }
        try {
            Object glow = flareGetEngineGlow.invoke(controller);
            return glow != null && Boolean.TRUE.equals(flareIsFlaring.invoke(glow));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The authoritative headless combat OUTCOME, derived server-side from the per-ship
     * allegiance roster: which {@code FleetSide} still has at least one live
     * ship once the other has none. Returns {@code null} (fail-soft) when the allegiance
     * roster read ({@link #shipAllegiancesOrNull()}) is unavailable.
     *
     * <p>This — not {@link #combatOutcomeOrNull()} — is the producer a headless server
     * folds through the {@link cc.starsector.server.runtime.CombatOutcomeEventDetector
     * CombatOutcomeEventDetector} to end a session and declare a winner. The two share the
     * same {@link CombatOutcomeView} contract, so the outcome wire / detector /
     * codec / consumer are unchanged — only the producer's data source moves from the
     * engine field to the roster the server authoritatively owns. The engine read is kept
     * only for diagnostic/parity (see {@link #combatOutcomeOrNull()}).
     */
    public CombatOutcomeView combatOutcomeFromRosterOrNull() {
        List<ShipAllegianceView> roster = shipAllegiancesOrNull();
        if (roster == null) {
            return null;
        }
        return RosterCombatOutcome.decide(roster);
    }

    ShipCommandSink.Result submitPlayerShipMovement(
            long shipId,
            ShipCommandSink.Source source,
            long simTick,
            ShipCommandSink.Movement intent) {
        ShipCommandSink.Result validation = validatePlayerShipCommand(shipId, source, simTick);
        if (validation != ShipCommandSink.Result.APPLIED || intent == null) {
            return intent == null ? ShipCommandSink.Result.REJECTED_INVALID : validation;
        }
        return invokePlayerShipCommand(shipId, movementCommandFor(intent), null, 0);
    }

    ShipCommandSink.Result selectPlayerShipWeaponGroup(
            long shipId,
            ShipCommandSink.Source source,
            long simTick,
            int group) {
        ShipCommandSink.Result validation = validatePlayerShipCommand(shipId, source, simTick);
        if (validation != ShipCommandSink.Result.APPLIED) {
            return validation;
        }
        if (group < 0) {
            return ShipCommandSink.Result.REJECTED_INVALID;
        }
        return invokePlayerShipCommand(shipId, weaponCommandSelectGroup, null, group);
    }

    ShipCommandSink.Result submitPlayerShipFire(
            long shipId,
            ShipCommandSink.Source source,
            long simTick,
            boolean fire) {
        ShipCommandSink.Result validation = validatePlayerShipCommand(shipId, source, simTick);
        if (validation != ShipCommandSink.Result.APPLIED) {
            return validation;
        }
        return invokePlayerShipCommand(shipId, fire ? weaponCommandFire : weaponCommandHoldFire, null, 0);
    }

    /**
     * Issue the engine's own {@code ShipCommand.VENT_FLUX} on the owned ship when
     * {@code vent} is set. Mirrors {@link #submitPlayerShipFire}: the engine's
     * {@code FluxTracker.ventFlux()} self-guards (no-op while overloaded / vent rate &le; 0 / already
     * venting) and derives the vent duration from the SERVER's own authoritative flux — faithful,
     * NOT a client-flux write. When {@code vent} is false this is a no-op {@code APPLIED}
     * (there is no "stop venting" command — venting runs its engine-owned course). The rising-edge
     * gating lives in {@link #applyPilotIntent}; this raw entry just issues the command.
     */
    ShipCommandSink.Result submitPlayerShipVent(
            long shipId,
            ShipCommandSink.Source source,
            long simTick,
            boolean vent) {
        ShipCommandSink.Result validation = validatePlayerShipCommand(shipId, source, simTick);
        if (validation != ShipCommandSink.Result.APPLIED) {
            return validation;
        }
        if (!vent) {
            return ShipCommandSink.Result.APPLIED;
        }
        return invokePlayerShipCommand(shipId, weaponCommandVent, null, 0);
    }

    ShipCommandSink.Result activatePlayerShipSystem(
            long shipId,
            ShipCommandSink.Source source,
            long simTick,
            int systemId) {
        ShipCommandSink.Result validation = validatePlayerShip(shipId, source, simTick);
        if (validation != ShipCommandSink.Result.APPLIED) {
            return validation;
        }
        if (systemId < 0) {
            return ShipCommandSink.Result.REJECTED_INVALID;
        }
        if (systemId != 0 || playerShipUseSystem == null) {
            return ShipCommandSink.Result.REJECTED_UNSUPPORTED;
        }
        return invokePlayerShipUseSystem(shipId);
    }

    /**
     * The outcome of one {@link #applyPilotIntent} call (diagnostics + game-free assertions): whether the
     * frame was accepted (owned + resolvable), how many translation-movement commands were replayed this
     * tick, and whether the facing pin / system activation / vent command issued. A fake binding (null
     * handles) returns {@code accepted} with all-zero/false sub-results, so its contract stays stable.
     */
    public record PilotApplyResult(
            boolean accepted,
            int movementCommandsIssued,
            boolean facingPinned,
            boolean aimPinned,
            boolean systemActivated,
            boolean ventIssued,
            boolean fireIssued,
            boolean groupMirrored,
            int autofireTogglesIssued,
            boolean retreatIssued,
            boolean targetApplied) {
        static PilotApplyResult rejected() {
            return new PilotApplyResult(false, 0, false, false, false, false, false, false, 0, false, false);
        }
    }

    // ---- The faithful PilotIntentFrame -> engine apply-path (applyPilotIntent + the
    // applyXBit fan-out, the wire-bit constants, the group-identity / mount-readiness / fire-driver factories
    // and their log counters) lives in PilotCommandApplier, its own file.
    // The delegators below keep this binding's public surface, so callers and contract rows are
    // untouched; the applier reads its command handles straight from the Builder and reaches the shared
    // seams (ownedShipIds, orbitDroneDrive, lastEffectiveStepAmount, reinjectShimByShip, invoke, ...) here.

    /** @see PilotCommandApplier#applyPilotIntent */
    public PilotApplyResult applyPilotIntent(
            cc.starsector.server.runtime.PilotIntentFrame frame, long simTick, PilotApplyState state) {
        return pilotCommands.applyPilotIntent(frame, simTick, state);
    }

    /** @see PilotCommandApplier#groupIdentitySnapshotForShip */
    public String groupIdentitySnapshotForShip(long shipId) {
        return pilotCommands.groupIdentitySnapshotForShip(shipId);
    }

    /** @see PilotCommandApplier#weaponGroupOrdinalForMount */
    public int weaponGroupOrdinalForMount(long shipId, int mountIndex) {
        return pilotCommands.weaponGroupOrdinalForMount(shipId, mountIndex);
    }

    /** @see PilotCommandApplier#fireClassResolverForShip */
    public PilotApplyState.FireClassResolver fireClassResolverForShip(final long shipId,
            final double rateMax,
            final Map<String, cc.starsector.server.runtime.FireClassLaw.FireClass> overrides) {
        return pilotCommands.fireClassResolverForShip(shipId, rateMax, overrides);
    }

    /** @see PilotCommandApplier#mountReadinessProbeForShip */
    public PilotApplyState.MountReadinessProbe mountReadinessProbeForShip(final long shipId) {
        return pilotCommands.mountReadinessProbeForShip(shipId);
    }

    /** @see PilotCommandApplier#mountFireDriverForShip */
    public PilotApplyState.MountFireDriver mountFireDriverForShip(final long shipId) {
        return pilotCommands.mountFireDriverForShip(shipId);
    }

    /** @see PilotCommandApplier#fireLockProbeForShip */
    public PilotApplyState.FireLockProbe fireLockProbeForShip(final long shipId) {
        return pilotCommands.fireLockProbeForShip(shipId);
    }

    /** @see PilotCommandApplier#driveLockWirePredicateOrNull */
    public java.util.function.LongPredicate driveLockWirePredicateOrNull() {
        return pilotCommands.driveLockWirePredicateOrNull();
    }

    /** @see PilotCommandApplier#fireLockReadErrors */
    public int fireLockReadErrors() {
        return pilotCommands.fireLockReadErrors();
    }

    /** @see PilotCommandApplier#fireClassSnapshotForShip */
    public String fireClassSnapshotForShip(long shipId, double rateMax,
            Map<String, cc.starsector.server.runtime.FireClassLaw.FireClass> overrides) {
        return pilotCommands.fireClassSnapshotForShip(shipId, rateMax, overrides);
    }

    /** @see PilotCommandApplier#warnGroupParity (EngineReflectionBinding calls it through this binding). */
    void warnGroupParity(String lane, int wireIdx, int hullGroupCount) {
        pilotCommands.warnGroupParity(lane, wireIdx, hullGroupCount);
    }


    /** The hull's live weapon-group list, or null when the seam is unbound or the read fails soft. */
    List<?> weaponGroupsCopyOrNull(Object ship) {
        if (shipGetWeaponGroupsCopy == null) {
            return null;
        }
        Object raw = invoke(shipGetWeaponGroupsCopy, ship, "ShipAPI.getWeaponGroupsCopy");
        return raw instanceof List<?> ? (List<?>) raw : null;
    }

    List<?> shipList() {
        Object repo = invoke(getObjects, engine, "getObjects");
        if (repo == null) {
            throw new EngineBindingException("getObjects returned null", null);
        }
        Object list = invoke(repositoryGetList, repo, "repository.getList", shipClass);
        return asList(list, "repository.getList");
    }

    /**
     * The ROSTER enumeration for the positional per-ship view walk
     * family — {@link #shipList()} minus non-roster fx/presentation mints (blank
     * {@code getId()}, the ONE {@link EngineBornGrowthSeam#isNonRosterMintId} law the
     * growth seam's {@code blank_id_skip} already applies), so every walk keyed by the
     * 1-based repository ordinal skips the same entries and the pairing invariant holds
     * BY CONSTRUCTION (the crash: a hydra MDEM child's DEM beam-drone minted a
     * blank-id repo entry the lifecycle walk voided while its siblings served it). Raw
     * {@link #shipList()} stays for the non-positional seams: the growth seam must SEE
     * the mint to classify it, and the census/dump diagnostics count raw by design.
     * W3 L4 S1: the old transfer-pod identity exclusion is
     * DELETED — the wire mirrors the world; the pod rides this walk family (and the AWSS)
     * deliberately, carried to clients by its own RAPP row at the serve lane's mint branch.
     *
     * <p>Kept entries stay fail-closed downstream: a null repo slot or a failing/blank
     * id read here KEEPS the entry (post-filter a blank id is genuinely novel again),
     * and the in-walk guards + {@code reportWalkNull} receipts remain the floor. An
     * unbound id handle returns the raw list unchanged — no classification is possible,
     * and the id-reading walks already fail closed on their own.
     */
    List<?> rosterShipList() {
        List<?> ships = shipList();
        if (shipGetId == null) {
            return ships;
        }
        List<Object> roster = null; // copy-on-first-exclusion: no blank-id entry, no alloc
        for (int i = 0; i < ships.size(); i++) {
            Object ship = ships.get(i);
            boolean excluded = false;
            if (ship != null) {
                try {
                    excluded = EngineBornGrowthSeam.isNonRosterMintId(shipGetId.invoke(ship));
                } catch (ReflectiveOperationException | RuntimeException kept) {
                    // keep the entry: the owning walk's guard + receipt surface the failure
                }
            }
            if (excluded) {
                if (roster == null) {
                    roster = new ArrayList<>(ships.subList(0, i));
                }
            } else if (roster != null) {
                roster.add(ship);
            }
        }
        return roster == null ? ships : roster;
    }

    private Object movementCommandFor(ShipCommandSink.Movement intent) {
        switch (intent) {
            case ACCELERATE:
                return movementCommandAccelerate;
            case DECELERATE:
                return movementCommandDecelerate;
            case STRAFE_LEFT:
                return movementCommandStrafeLeft;
            case STRAFE_RIGHT:
                return movementCommandStrafeRight;
            case TURN_LEFT:
                return movementCommandTurnLeft;
            case TURN_RIGHT:
                return movementCommandTurnRight;
            case STOP:
            default:
                return null;
        }
    }

    private ShipCommandSink.Result validatePlayerShipCommand(
            long shipId,
            ShipCommandSink.Source source,
            long simTick) {
        ShipCommandSink.Result validation = validatePlayerShip(shipId, source, simTick);
        if (validation != ShipCommandSink.Result.APPLIED) {
            return validation;
        }
        if (playerShipGiveCommand == null) {
            return ShipCommandSink.Result.REJECTED_UNSUPPORTED;
        }
        return ShipCommandSink.Result.APPLIED;
    }

    private ShipCommandSink.Result validatePlayerShip(
            long shipId,
            ShipCommandSink.Source source,
            long simTick) {
        if (source == null || simTick < 0L) {
            return ShipCommandSink.Result.REJECTED_INVALID;
        }
        // Relaxed from the single-player-ship singleton (shipId == 1) to the
        // configured server-owned set so a 2nd owned ship is commandable. Default
        // ownership is {1}, preserving the proven single-player path. A non-owned
        // ship is REJECTED_INVALID (an unauthorized command target).
        if (!ownedShipIds.contains(shipId)) {
            return ShipCommandSink.Result.REJECTED_INVALID;
        }
        return ShipCommandSink.Result.APPLIED;
    }

    private ShipCommandSink.Result invokePlayerShipCommand(long shipId, Object command, Object param, int group) {
        if (command == null) {
            return ShipCommandSink.Result.REJECTED_UNSUPPORTED;
        }
        try {
            Object ship = shipForWireIdOrNull(shipId);
            if (ship == null) {
                return ShipCommandSink.Result.REJECTED_INVALID;
            }
            maybeSuppressShipAi(ship);
            playerShipGiveCommand.invoke(ship, command, param, group);
            return ShipCommandSink.Result.APPLIED;
        } catch (InvocationTargetException e) {
            throw new EngineBindingException("ShipAPI.giveCommand threw", e.getCause() != null ? e.getCause() : e);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new EngineBindingException("ShipAPI.giveCommand invocation failed", e);
        }
    }

    private ShipCommandSink.Result invokePlayerShipUseSystem(long shipId) {
        try {
            Object ship = shipForWireIdOrNull(shipId);
            if (ship == null) {
                return ShipCommandSink.Result.REJECTED_INVALID;
            }
            maybeSuppressShipAi(ship);
            playerShipUseSystem.invoke(ship);
            return ShipCommandSink.Result.APPLIED;
        } catch (InvocationTargetException e) {
            throw new EngineBindingException("ShipAPI.useSystem threw", e.getCause() != null ? e.getCause() : e);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new EngineBindingException("ShipAPI.useSystem invocation failed", e);
        }
    }

    /**
     * (B) Option A AI-suppression (handoff-detail): when a client command targets an
     * owned ship that still carries an engine {@code ShipAI} (the demo deploy attaches a real
     * {@code BasicShipAI} — a {@code ShipAIPlugin}, so {@code getShipAI()} returns it — to the
     * non-player owned seat), strip it once via {@code Ship.setShipAI(null)} (which calls
     * {@code setAI(null)}) so the client's commanded intent is not fought by the
     * engine's {@code BasicEngineAI}. Lazy: applied at command time, after ships have drained from
     * the pending-add queue (robust to the AI-assignment-timing unknown). Self-healing across
     * {@code RealEngineCombatBindingFactory.resetForRedeploy}: a redeployed ship gets a fresh AI, so
     * the next round's first command re-suppresses ({@code getShipAI()} non-null again). No-op when
     * the suppression handles are unbound (the fake binding never sets them) — so every game-free
     * contract stays byte-identical and {@link cc.starsector.server.runtime.ShipCommandDriver} is
     * untouched. Fail-soft: a suppression reflection miss is logged once and never fails the command.
     */
    void maybeSuppressShipAi(Object ship) { // pkg-private: PilotCommandApplier
        if (shipSetShipAI == null || shipGetShipAI == null || ship == null) {
            return;
        }
        try {
            Object ai = shipGetShipAI.invoke(ship);
            // The DD1 fast-time reinject shim is WHITELISTED by the pinned
            // wrapper identity (the getShipAI() readback captured at install) — never strip it;
            // anything else non-null still strips. A foreign AI that replaced the shim is stripped
            // to null like any other; the next fly-in installs a fresh shim (self-healing).
            if (ai != null && ai == reinjectWrapperByShip.get(ship)) {
                return;
            }
            // The W5 grace AI is lane-OWNED — never strip it (detach is explicit,
            // graceDetachAi). A foreign AI that displaced a pinned grace AI still strips below.
            if (ai != null && ai == graceAiByShip.get(ship)) {
                return;
            }
            if (ai != null) {
                shipSetShipAI.invoke(ship, new Object[] {null});
                // A stripped FOREIGN AI had already displaced the
                // shim wrapper engine-side — prune both shim maps so fastTimeShimInstalled() cannot
                // over-report a shim that is no longer wired; the next fly-in reinstalls fresh.
                if (reinjectShimByShip.remove(ship) != null) {
                    reinjectWrapperByShip.remove(ship);
                }
                long n = aiSuppressions.incrementAndGet();
                System.out.println("engine-combat-binding ai-suppressed"
                        + " ship_ai_class=" + ai.getClass().getName()
                        + " ai_suppressions=" + n);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!aiSuppressionErrorReported) {
                aiSuppressionErrorReported = true;
                System.out.println("engine-combat-binding ai-suppress FAILED (tolerated once) "
                        + e.getClass().getName() + ": " + e.getMessage());
            }
        }
    }

    /** Total owned-ship engine-AI suppressions performed this process (one per ship per round). */
    public long aiSuppressions() {
        return aiSuppressions.get();
    }

    /**
     * Install the DD1 fast-time reinject shim on a seat hull by wire
     * id (the serve loop's fly-in surface). Resolves through the same seat-addressed authority as
     * {@code applyPilotIntent}; an unresolvable id is a fail-soft no-op (the fly-in log already
     * names the hull). Returns whether a shim was installed.
     */
    public boolean installFastTimeReinjectShim(long wireId) {
        return installFastTimeReinjectShim(shipForWireIdOrNull(wireId));
    }

    /**
     * The just-fielded hull's facing at the fly-in site, read
     * through the SAME {@code ShipAPI.getFacing} handle the wire's {@code facing} float comes from
     * (the motion view, {@code EngineBackedShipMotionView}) — so the {@code fly_in facing=} log
     * field pairs unit-for-unit with the client's {@code own-flyin-facing} instrument rows.
     * Fail-soft: unbound seam (every fake binding) or an unresolvable hull ⇒ {@code NaN}.
     */
    public float shipFacingOrNaN(long wireId) {
        if (playerShipGetFacing == null) {
            return Float.NaN;
        }
        Object ship = shipForWireIdOrNull(wireId);
        if (ship == null) {
            return Float.NaN;
        }
        try {
            return numberAsFloat(playerShipGetFacing.invoke(ship), "getFacing");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Float.NaN;
        }
    }

    /**
     * The just-fielded hull's position at the fly-in site ({@code [x, y]}), the
     * {@link #shipFacingOrNaN} twin — the {@code fly_in … loc=} log field is how a headless rig
     * reads WHICH map edge the engine's null-location deploy chose (side 0 = south, y &lt; 0;
     * side 1 = north, y &gt; 0). One read per fly-in, off the tick path. Fail-soft: unbound seam
     * (every fake binding) or an unresolvable hull ⇒ {@code null}.
     */
    public float[] shipLocationOrNull(long wireId) {
        if (playerShipGetLocation == null) {
            return null;
        }
        Object ship = shipForWireIdOrNull(wireId);
        if (ship == null) {
            return null;
        }
        try {
            return readXY(playerShipGetLocation.invoke(ship));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Disarm variant-default autofire on a freshly-built seat hull at
     * the fly-in site, BEFORE its first engine advance. The engine ship factory arms every weapon
     * group whose variant flags {@code autofireOnByDefault}
     * (→ {@code WeaponGroup.toggleOn()}), and ALL server seat suppression lanes (AI strip,
     * autofire-mask mirror) are gated on an APPLIED client intent frame — which cannot exist at a
     * respawn refield tick (the dead hull's staged frame was just cleared at mount loss). The
     * hull's first advance therefore runs the engine's own per-group autofire AI on a body whose
     * fire authority belongs to the owning client (FIRE-only law): with a foe in
     * range+arc it fires a live round at build — the "phantom respawn hammer" (real
     * projectile, real damage, wire WEAPON_DISCHARGE the client never predicted =
     * {@code own_discharge_server_only} adopt). Verb =
     * {@code WeaponGroupAPI.toggleOff()}: the engine's own public tracker write, synchronous, so
     * it lands before the first advance with NO dependence on the TOGGLE_AUTOFIRE click-edge law
     * (that law never engages — no button is pressed). The per-frame
     * {@code applyAutofireMask} mirror then converges the hull to the client's real mask from its
     * first applied frame exactly as before (hull all-OFF diffs cleanly). Fail-soft: unbound
     * seams (every fake binding) or an unresolvable hull return -1 and change nothing.
     *
     * @return groups toggled off (0 = none were autofiring), or -1 = seams unbound / hull
     *         unresolved (logged by the caller; a real-engine -1 is a seam regression).
     */
    public int disarmBuildTimeAutofire(long wireId) {
        if (shipGetWeaponGroupsCopy == null || weaponGroupIsAutofiring == null
                || weaponGroupToggleOff == null) {
            return -1;
        }
        Object ship = shipForWireIdOrNull(wireId);
        if (ship == null) {
            return -1;
        }
        List<?> groups = weaponGroupsCopyOrNull(ship);
        if (groups == null) {
            return -1;
        }
        int off = 0;
        for (Object group : groups) {
            Boolean on = asBoolean(
                    invoke(weaponGroupIsAutofiring, group, "WeaponGroupAPI.isAutofiring"));
            if (on != null && on.booleanValue()) {
                invoke(weaponGroupToggleOff, group, "WeaponGroupAPI.toggleOff");
                off++;
            }
        }
        return off;
    }

    /** What {@link #graceAttachCautiousAi} changed — the lane's bookkeeping for the later
     * restore: the pre-grace effective personality id (nullable — a captain-less hull with a
     * null fallback), which lever held it ({@code captainPath}), and the attached AI's class
     * (evidence the ModManager pick actually landed). */
    public record GraceAttachResult(
            String savedPersonalityId, boolean captainPath, String aiClassName) {}

    /** What {@link #graceDetachAi} did: autofire groups re-disarmed (the AI's own
     * AutofireManagerV2 toggles groups on while it fights
     * — so the detach must strip them back to the fly-in all-OFF
     * law) and whether the fly-in reinject shim was re-pinned. */
    public record GraceDetachResult(int autofireOff, boolean shimReinstalled) {}

    /**
     * W5 GRACE entry —
     * swap the vacated seat hull to a "cautious"-personality engine AI. Seat hulls deploy
     * PILOTLESS (their {@code getShipAI()} slot holds only the DD1 fast-time reinject shim from
     * fly-in), so this is an ATTACH of an AI that never existed, not an un-suppress:
     * <ol>
     * <li>save the hull's effective personality ({@code Ship.getPersonality()} — captain-or-
     *     fallback);</li>
     * <li>set {@code "cautious"} on the live captain via the PUBLIC {@code PersonAPI} (the
     *     PersonMint idiom; personality is LIVE-READ per AI decision, so no AI churn), or via
     *     {@code Ship.setFallbackPersonalityId} when captain-less ({@code setCaptain} REJECTED —
     *     it unapplies/applies personal stats mid-round);</li>
     * <li>attach via {@code setDefaultAI(getFleetMember())} — the proven module-restore verb
     *     family (ModManager re-picks, plain hulls take
     *     {@code BasicShipAI}) — and prune the reinject-shim pins the attach displaced (the
     *     {@link #maybeSuppressShipAi} whitelist + {@code fastTimeShimInstalled} must never
     *     hold a stale identity).</li>
     * </ol>
     * No autofire re-arm: the AI's own AutofireManagerV2 toggles groups as it fights (the
     * engine-native seam; a manual re-arm would fight it).
     * THROWS on any failure (the mount-verb family law: an engine state-change failure is never
     * per-session; a half-graced hull must not serve).
     *
     * <p><b>Strip protection (the flown self-check's FIND):</b> "vacant seat ⇒ no
     * command-time path" was FALSE — the serve loop replays the seat's LAST staged intent frame
     * every tick until replaced, and that replay's {@code applyPilotIntent} stripped the fresh
     * grace AI two ticks after attach ({@code ai-suppressed} in the flown log). Two-layer fix:
     * the grace lane drops the staged frame at ENTER_GRACE (the known path), and this attach
     * PINS the AI readback in {@code graceAiByShip} so {@link #maybeSuppressShipAi} structurally
     * cannot reap a lane-owned AI (detach is the only legal removal).
     */
    public GraceAttachResult graceAttachCautiousAi(long wireId) {
        if (shipSetShipAI == null || shipGetShipAI == null) {
            throw new EngineBindingException(
                    "grace attach needs the ship-AI seams bound (wire_id=" + wireId
                            + ") — a binding without them cannot grace", null);
        }
        Object ship = shipForWireIdOrNull(wireId);
        if (ship == null) {
            throw new EngineBindingException("grace attach: wire id " + wireId
                    + " resolves no live hull (the authority said mounted — invariant breach)",
                    null);
        }
        try {
            String saved = (String) ship.getClass().getMethod("getPersonality").invoke(ship);
            Object captain = ship.getClass().getMethod("getCaptain").invoke(ship);
            boolean captainPath = captain != null;
            setEffectivePersonality(ship, captain, "cautious");
            Object fleetMember = ship.getClass().getMethod("getFleetMember").invoke(ship);
            Method setDefaultAI = oneArgMethodOrNull(ship, "setDefaultAI");
            if (setDefaultAI == null) {
                throw new EngineBindingException("grace attach: no 1-arg setDefaultAI on Ship —"
                        + " the AI attach verb moved (re-verify against the installed engine build)", null);
            }
            setDefaultAI.invoke(ship, fleetMember);
            return new GraceAttachResult(saved, captainPath, pinAttachedAi(ship));
        } catch (EngineBindingException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new EngineBindingException(
                    "grace attach failed wire_id=" + wireId, e);
        }
    }

    /**
     * W3 L4 S1 transit-park attach, DRONE-AWARE
     * (a vacated Merlon drone "should just stay as it was"): a station-launched drone
     * source takes the engine's own drone AI ({@link OrbitDroneDrive#pickVanillaDroneAiOrNull})
     * so its launcher ring keeps flying through the transit window — the cautious attach's
     * {@code setDefaultAI} picks {@code BasicShipAI} for drones, which free-flew the drone for
     * the whole pod flight (~10 s in flight #15) until the COMMIT restore re-picked. The
     * personality is saved UNTOUCHED (a drone flies the launcher law, not a personality; the
     * abort detach's restore is then a no-op), the shim prune + readback pin are the cautious
     * attach's verbatim. Gate-miss (not a drone / no live launcher) or a reflective pick
     * failure (logged, one line) ⇒ {@link #graceAttachCautiousAi} — exactly the prior
     * behavior. Same THROW law as the cautious attach for the attach itself.
     */
    public GraceAttachResult graceAttachTransitAi(long wireId) {
        Object ship = shipForWireIdOrNull(wireId);
        Object droneAi = null;
        if (ship != null && shipSetShipAI != null && shipGetShipAI != null) {
            try {
                droneAi = OrbitDroneDrive.pickVanillaDroneAiOrNull(ship);
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.out.println("npeer-serve transit_park drone-AI pick FAILED wire_id="
                        + wireId + " -> cautious attach: " + e);
            }
        }
        if (droneAi == null) {
            return graceAttachCautiousAi(wireId);
        }
        try {
            String saved = (String) ship.getClass().getMethod("getPersonality").invoke(ship);
            boolean captainPath = ship.getClass().getMethod("getCaptain").invoke(ship) != null;
            shipSetShipAI.invoke(ship, droneAi);
            return new GraceAttachResult(saved, captainPath, pinAttachedAi(ship));
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new EngineBindingException(
                    "transit drone-AI attach failed wire_id=" + wireId, e);
        }
    }

    /** The grace-attach tail both attaches share: prune the reinject-shim pins the attach
     * displaced (the {@link #maybeSuppressShipAi} whitelist + {@code fastTimeShimInstalled}
     * must never hold a stale identity), then PIN the AI readback in {@code graceAiByShip}
     * (detach is the only legal removal). Returns the pinned AI's class name ("none"). */
    private String pinAttachedAi(Object ship) throws ReflectiveOperationException {
        if (reinjectShimByShip.remove(ship) != null) {
            reinjectWrapperByShip.remove(ship);
        }
        Object ai = shipGetShipAI.invoke(ship);
        if (ai != null) {
            pruneDeadGracePins();
            graceAiByShip.put(ship, ai);
        }
        return ai == null ? "none" : ai.getClass().getName();
    }

    /**
     * W5 grace detach — RECLAIM (returning player) and PARK-drift share it: EXPLICIT
     * {@code setShipAI(null)} (discards the grace AI; the lazy
     * {@link #maybeSuppressShipAi} strip is deliberately NOT relied on),
     * restore the saved personality through the same lever that held it, re-disarm the autofire
     * groups the AI's AutofireManagerV2 armed while fighting (the {@link #disarmBuildTimeAutofire}
     * law: a reclaimed hull's fire authority is the client's — its real mask re-converges at
     * its first applied frame; a drift-parked hull must coast silent), and re-pin the fly-in
     * reinject shim (uniform-install law). THROWS like the attach (same family law).
     *
     * @param restorePersonalityId the attach's {@code savedPersonalityId} (nullable — restores a
     *        null fallback verbatim)
     */
    public GraceDetachResult graceDetachAi(long wireId, String restorePersonalityId) {
        GraceDetachResult result = graceDetachAiIfLive(wireId, restorePersonalityId);
        if (result == null) {
            throw new EngineBindingException("grace detach: wire id " + wireId
                    + " resolves no live hull (grace episodes clear at mount loss — invariant"
                    + " breach)", null);
        }
        return result;
    }

    /**
     * W3 L4 S1: {@link #graceDetachAi}'s hull-TOLERANT twin for the
     * transit-park abort — a wire id that no longer resolves returns {@code null} instead of
     * the family THROW. A source hull destroyed mid-pod-flight is a LEGAL park end state
     * (the abort consumed the row; there is nothing to restore), not the grace lane's
     * invariant breach; the dead hull's {@code graceAiByShip} pin prunes at the next attach's
     * {@link #pruneDeadGracePins}. Everything past the resolve keeps the family law — a
     * half-detached LIVE hull still THROWs.
     */
    public GraceDetachResult graceDetachAiIfLive(long wireId, String restorePersonalityId) {
        if (shipSetShipAI == null || shipGetShipAI == null) {
            throw new EngineBindingException(
                    "grace detach needs the ship-AI seams bound (wire_id=" + wireId + ")", null);
        }
        Object ship = shipForWireIdOrNull(wireId);
        if (ship == null) {
            return null;
        }
        try {
            graceAiByShip.remove(ship);
            shipSetShipAI.invoke(ship, new Object[] {null});
            Object captain = ship.getClass().getMethod("getCaptain").invoke(ship);
            setEffectivePersonality(ship, captain, restorePersonalityId);
            int autofireOff = disarmBuildTimeAutofire(wireId);
            boolean shim = installFastTimeReinjectShim(ship);
            return new GraceDetachResult(autofireOff, shim);
        } catch (EngineBindingException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new EngineBindingException(
                    "grace detach failed wire_id=" + wireId, e);
        }
    }

    /**
     * W3 L4 S1: a command-transfer COMMIT retires the transit-parked
     * source hull into its vanilla AI-flown END state — no
     * detach, no personality restore, but the lane's {@code graceAiByShip} PIN must RELEASE:
     * a lingering pin makes the AI permanently lane-owned, so a later claimer's Option-A
     * suppression ({@link #maybeSuppressShipAi}) could never strip what is now an ordinary
     * engine AI. Called with the source hull's FRESH post-re-key id. Fail-soft: an
     * unresolvable id or an unpinned hull is a no-op {@code false}.
     */
    public boolean releaseGracePin(long wireId) {
        Object ship = shipForWireIdOrNull(wireId);
        return ship != null && graceAiByShip.remove(ship) != null;
    }

    /** The grace personality lever: the captain when one exists
     * (PUBLIC {@code PersonAPI.setPersonality} — the PersonMint interface-resolution idiom),
     * else the ship's fallback id. {@code personalityId} null is legal on the fallback path only
     * (restoring a hull that never had one); a captained hull restores a non-null id by
     * construction (its save read {@code captain.getPersonality().getId()}). */
    private void setEffectivePersonality(Object ship, Object captain, String personalityId)
            throws ReflectiveOperationException {
        if (captain != null) {
            if (personalityId == null) {
                throw new IllegalStateException("grace personality restore: captained hull with"
                        + " a null saved id (the save path cannot produce this)");
            }
            Class<?> personApi = Class.forName("com.fs.starfarer.api.characters.PersonAPI",
                    false, ship.getClass().getClassLoader());
            personApi.getMethod("setPersonality", String.class).invoke(captain, personalityId);
        } else {
            ship.getClass().getMethod("setFallbackPersonalityId", String.class)
                    .invoke(ship, personalityId);
        }
    }

    /** First public 1-arg method of the given name on the ship's class, or null (the roster's
     * engine-typed-parameter idiom — {@code setDefaultAI}'s parameter type is engine-internal, so no literal
     * signature exists to name). */
    private static Method oneArgMethodOrNull(Object ship, String name) {
        for (Method candidate : ship.getClass().getMethods()) {
            if (name.equals(candidate.getName()) && candidate.getParameterCount() == 1) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Install the DD1 fast-time reinject shim on one seat hull.
     *
     * <p><b>Why:</b> per extra fast-time pass the engine re-supplies controls only via (a)
     * {@code ai.advance} when AI≠null or (b) playerShip capture/reinject;
     * fly-in seat hulls are NEVER playerShip (the
     * first-DEPLOYED hull takes the W3 write at t=0), so an AI-less phased seat starves at
     * pass-1-only control coverage (~1/3 ship-sim). The shim is a {@link Proxy} implementing the
     * game's {@code ShipAIPlugin} (interface class taken from
     * {@code shipSetShipAI.getParameterTypes()[0]} — no name guessing, no api-jar dependency)
     * whose {@code advance(float)} re-issues the recorded pass-1 command set ONLY while the
     * engine {@code isInFastTimeAdvance()} — the engine's own single-issuer split.
     * Installed UNIFORMLY on all seat hulls at fly-in ({@code advance()} no-ops outside fast time;
     * only phased hulls ever enter it — uniform install).
     *
     * <p><b>Wrap trap:</b> {@code Ship.setShipAI} WRAPS a non-engine plugin in
     * {@code Ship.ShipAIWrapper}, so this method
     * immediately reads back {@code getShipAI()} and pins THAT object as the
     * {@link #maybeSuppressShipAi} whitelist identity.
     *
     * <p>Fail-closed: any of {@code shipSetShipAI}/{@code shipGetShipAI}/
     * {@code engineIsInFastTimeAdvance}/{@code playerShipGiveCommand} unbound ⇒ NO install (a shim
     * that cannot tell passes apart would double-issue pass 1; fakes without the seams keep
     * byte-identity). Reflection failures are fail-soft (logged once, no install). Idempotent: a
     * hull already carrying its pinned shim wrapper is left alone.
     */
    public boolean installFastTimeReinjectShim(Object ship) {
        if (ship == null || shipSetShipAI == null || shipGetShipAI == null
                || engineIsInFastTimeAdvance == null || playerShipGiveCommand == null) {
            return false;
        }
        try {
            Object current = shipGetShipAI.invoke(ship);
            if (current != null && current == reinjectWrapperByShip.get(ship)) {
                return true; // already installed + pinned — idempotent
            }
            pruneDeadReinjectShims();
            Class<?> aiInterface = shipSetShipAI.getParameterTypes()[0];
            FastTimeReinjectShim handler = new FastTimeReinjectShim(this, ship);
            Object proxy = Proxy.newProxyInstance(
                    aiInterface.getClassLoader(), new Class<?>[] {aiInterface}, handler);
            shipSetShipAI.invoke(ship, proxy);
            Object wrapper = shipGetShipAI.invoke(ship);
            if (wrapper == null) {
                if (!fastTimeErrorReported) {
                    fastTimeErrorReported = true;
                    System.out.println("engine-combat-binding fast-time-shim install FAILED"
                            + " (getShipAI readback null after setShipAI — wrap contract drifted?)");
                }
                return false;
            }
            reinjectWrapperByShip.put(ship, wrapper);
            reinjectShimByShip.put(ship, handler);
            System.out.println("engine-combat-binding fast-time-shim installed"
                    + " wrapper_class=" + wrapper.getClass().getName()
                    + " shims=" + reinjectShimByShip.size());
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!fastTimeErrorReported) {
                fastTimeErrorReported = true;
                System.out.println("engine-combat-binding fast-time-shim install FAILED (tolerated) "
                        + e.getClass().getName() + ": " + e.getMessage());
            }
            return false;
        }
    }

    /** Drop grace-AI pins whose hull is no longer engine-alive (a mid-grace hull
     * death skips detach — CLEAR_MOUNT_LOST drops only the lane's bookkeeping). Attach-time,
     * fail-soft, the {@link #pruneDeadReinjectShims} idiom. */
    private void pruneDeadGracePins() {
        if (shipIsAlive == null || graceAiByShip.isEmpty()) {
            return;
        }
        java.util.Iterator<Object> it = graceAiByShip.keySet().iterator();
        while (it.hasNext()) {
            try {
                if (!Boolean.TRUE.equals(shipIsAlive.invoke(it.next()))) {
                    it.remove();
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue; // unreadable liveness: keep the entry (bounded — one per grace episode)
            }
        }
    }

    /** Drop shim registry entries whose hull is no longer engine-alive (fail-soft, install-time). */
    private void pruneDeadReinjectShims() {
        if (shipIsAlive == null || reinjectShimByShip.isEmpty()) {
            return;
        }
        java.util.Iterator<Map.Entry<Object, FastTimeReinjectShim>> it =
                reinjectShimByShip.entrySet().iterator();
        while (it.hasNext()) {
            Object dead = null;
            try {
                Object ship = it.next().getKey();
                if (!Boolean.TRUE.equals(shipIsAlive.invoke(ship))) {
                    dead = ship;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue; // unreadable liveness: keep the entry (bounded — one per seat hull)
            }
            if (dead != null) {
                it.remove();
                reinjectWrapperByShip.remove(dead);
            }
        }
    }

    /**
     * Clear every installed shim's recorded pass-1 command set — the serve step
     * calls this at the top of each tick, BEFORE applying staged intents, so a seat that applied
     * no frame this tick reinjects NOTHING (exactly what its pass 1 issued). Zero shims = no-op
     * (every game-free lane).
     */
    public void beginReinjectTick() {
        if (reinjectShimByShip.isEmpty()) {
            return;
        }
        for (FastTimeReinjectShim shim : reinjectShimByShip.values()) {
            shim.clear();
        }
    }

    // per-tick accumulator of nanos spent inside every shim's advance callback
    // (sim-thread-only: the engine calls the shims from the advance the serve thread drives).
    // The step boundary drains it right after each advance; a game-free lane reads 0.
    private long reinjectTickNanos;

    void noteReinjectNanos(long nanos) {
        reinjectTickNanos += nanos;
    }

    /** The nanos accumulated by every shim advance since the last drain, then reset to 0. */
    public long drainReinjectTickNanos() {
        long nanos = reinjectTickNanos;
        reinjectTickNanos = 0L;
        return nanos;
    }

    /**
     * Overwrite one installed shim's reinject record directly (the probe/measure
     * surface — {@code applyPilotIntent} records for the serve lanes). No shim installed on the
     * ship = no-op returning false.
     */
    public boolean recordFastTimeReinject(Object ship, int movementBitsMask, boolean systemPressed,
            boolean ventPressed, boolean firePressed, float aimWorldX, float aimWorldY) {
        FastTimeReinjectShim shim = reinjectShimByShip.get(ship);
        if (shim == null) {
            return false;
        }
        shim.record(movementBitsMask, systemPressed, ventPressed, firePressed, aimWorldX, aimWorldY);
        return true;
    }

    /** Whether a fast-time reinject shim is currently installed + pinned for this hull. */
    public boolean fastTimeShimInstalled(Object ship) {
        return reinjectShimByShip.containsKey(ship);
    }

    /** Total fast-time shim reinject advances performed this process (passes 2+ only). */
    public long fastTimeReinjects() {
        return fastTimeReinjects.get();
    }

    /**
     * The shim's {@code advance(float)} body (called from {@link FastTimeReinjectShim} on the sim
     * thread): no-op unless the engine is inside a fast-time extra pass, then re-issue the ship's
     * recorded pass-1 command set through the engine's own {@code giveCommand}/{@code useSystem}
     * — a strict conservative SUBSET of the engine's own playerShip reinject filter classes
     * (the engine also captures SELECT_GROUP,
     * TOGGLE_AUTOFIRE, USE_SELECTED_GROUP, raise-flux and turn; subset is the SAFE
     * direction, keep it): movement ordinals, VENT, USE_SYSTEM, and the fire handle with the
     * recorded aim — on the real engine that handle is USE_SELECTED_GROUP, the
     * filter class the engine itself captures, so a {@code noFiring} lock refuses it here too.
     * NEVER {@code TOGGLE_SHIELD_OR_PHASE_CLOAK} / hold-fire / pull-back-fighters
     * (the engine's own toggle-strobe protection — the record cannot even express them). Every
     * failure is swallowed fail-soft (an AI-advance throw would kill the engine's advance).
     */
    void fastTimeReinject(Object ship, FastTimeReinjectShim shim) {
        try {
            if (engineIsInFastTimeAdvance == null
                    || !Boolean.TRUE.equals(invoke(engineIsInFastTimeAdvance, engine,
                            "CombatEngineAPI.isInFastTimeAdvance"))) {
                return; // pass 1 (or gate unreadable): the apply lane owns it — shim stays inert
            }
            if (!shim.hasRecord()) {
                return; // no frame applied this tick: pass 1 issued nothing, so neither do we
            }
            int mask = shim.movementBitsMask();
            for (int bit = 0; bit <= PilotCommandApplier.WIRE_BIT_STRAFE_RIGHT; bit++) {
                if ((mask & (1 << bit)) == 0) {
                    continue;
                }
                Object command = pilotCommands.wireMovementCommand(bit);
                if (command != null) {
                    invoke(playerShipGiveCommand, ship, "ShipAPI.giveCommand(reinject)", command, null, 0);
                }
            }
            if (shim.ventPressed() && weaponCommandVent != null) {
                invoke(playerShipGiveCommand, ship, "ShipAPI.giveCommand(VENT_FLUX reinject)",
                        weaponCommandVent, null, 0);
            }
            if (shim.systemPressed() && playerShipUseSystem != null) {
                invoke(playerShipUseSystem, ship, "ShipAPI.useSystem(reinject)");
            }
            if (shim.firePressed() && weaponCommandFire != null && vector2fCtor != null) {
                Object aimPoint = vector2fCtor.newInstance(shim.aimWorldX(), shim.aimWorldY());
                invoke(playerShipGiveCommand, ship, "ShipAPI.giveCommand(USE_SELECTED_GROUP reinject)",
                        weaponCommandFire, aimPoint, 0);
            }
            fastTimeReinjects.incrementAndGet();
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!fastTimeErrorReported) {
                fastTimeErrorReported = true;
                System.out.println("engine-combat-binding fast-time-shim reinject FAILED (tolerated) "
                        + e.getClass().getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * post-step visit: repace every piloted (seat/human) hull's phase-cloak
     * charge tracker to wall time. Called from {@link EngineBackedCombatStepBoundary#step} right
     * after the engine advance with the effective step amount (the master-dt seam — exactly what
     * a non-fast ship advances per frame). Owned-set scoped: AI/foe hulls stay engine-native BY
     * DESIGN. No-op when the kill knob is off or the lane disabled itself (fail-closed).
     */
    public void pilotedCloakWallClockPostStep(float wallDt) {
        if (!pilotedCloakWallClock.active()) {
            return;
        }
        if (shipGetPhaseCloak == null) {
            // Loud, not a silent per-frame no-op — an unresolved handle is permanent
            // and used to be indistinguishable from a healthy run (the diagnosability gap).
            pilotedCloakWallClock.disableOnError("Ship.getPhaseCloak handle unresolved");
            return;
        }
        int resolved = 0;
        for (Long wireId : ownedShipIds) {
            Object ship = shipForWireIdOrNull(wireId);
            if (ship != null) {
                resolved++;
                pilotedCloakWallClockVisit(ship, wallDt);
            }
        }
        pilotedCloakWallClock.frameStats(ownedShipIds.size(), resolved);
    }

    /**
     * Single-hull F2b visit on a raw ship object — the probe/measure surface (the
     * {@link #recordFastTimeReinject} shape: bypasses the owned set, keeps the knob + fail-closed
     * gates). No phase cloak on the hull = no-op.
     */
    public void pilotedCloakWallClockVisit(Object ship, float wallDt) {
        if (!pilotedCloakWallClock.active() || shipGetPhaseCloak == null || ship == null) {
            return;
        }
        try {
            Object cloak = invoke(shipGetPhaseCloak, ship, "Ship.getPhaseCloak");
            if (cloak != null) {
                pilotedCloakWallClock.visit(reflection, cloak, wallDt);
            }
        } catch (RuntimeException e) {
            // A throw here would kill the engine advance path — disable instead (fail-closed).
            pilotedCloakWallClock.disableOnError("visit threw " + e.getClass().getName()
                    + ": " + e.getMessage());
        }
    }

    /**
     * Null the engine AI of a server-owned ship so it sits inert — no movement, no fire — by routing it
     * through the SAME faithful {@link #maybeSuppressShipAi} strip the piloted seat gets. The headless roster
     * is two OPPOSING Mudskippers (both server-owned, ids 1 + 2); only the piloted ship (1) is AI-suppressed
     * in {@link #applyPilotIntent} (so the client's commanded intent drives it), so the foe (2) otherwise keeps
     * its deploy {@code BasicShipAI} and fights. Stripping the foe's AI leaves it with no AI and nothing
     * commanding it — exactly the scout WATCH-foe end state ("the enemy ship doesn't even move")
     * — so a movement/feel pass on the piloted ship is not cut short by an aggressive foe.
     *
     * <p>Two server-loop callers, both gated default-OFF (the production duel never idles a seat):
     * <ul>
     *   <li>the game-free system-engage MEASURE (one jets cycle = chargeup 1 + active 5 + down 1 + cooldown 5
     *       = 12 s; the piloted ship must survive a LATE re-engagement read), and</li>
     *   <li>the scout-faithful render-WATCH foe-inert knob ({@code cc.predict.foeInert}), so a
     *       single-client movement/feel WATCH matches the scout instead of the product foe gunning the owned
     *       ship down in ~24 s (the owned ship cannot fire back yet).</li>
     * </ul>
     * Idempotent + self-healing across a {@code resetForRedeploy} (a redeployed foe gets a fresh AI, re-stripped
     * the next tick). Changes nothing about authority or the demo. No-op for a non-owned id or an unresolvable
     * ship.
     */
    public void idleOwnedShipAi(long shipId) {
        if (!ownedShipIds.contains(shipId)) {
            return;
        }
        Object ship = shipForWireIdOrNull(shipId);
        if (ship != null) {
            maybeSuppressShipAi(ship);
        }
    }

    /**
     * Outcome of {@link #applyCombatMapBounds(float)} — the applied flag, the requested half-extent, and the
     * combat map width/height read before+after (for the serve loop's one-line log + the WATCH debrief).
     * {@code error} is empty on success; on a fail-soft miss it carries the reflective cause and the after
     * values are {@code NaN}.
     */
    public record MapBoundsResult(
            boolean applied, float half,
            float widthBefore, float heightBefore,
            float widthAfter, float heightAfter,
            String error) {
        static MapBoundsResult failed(float half, float widthBefore, float heightBefore, String error) {
            return new MapBoundsResult(false, half, widthBefore, heightBefore, Float.NaN, Float.NaN, error);
        }
    }

    /**
     * The scout-faithful MAP-BOUNDS port (faithful port of the scout gate's {@code gate_map_bounds},
     * confirmed in play: "phantom perimeter is gone"). Widen the engine's combat map to
     * ±{@code half} so the SERVER map matches the rendered predict client's {@code cc_predict} mission
     * (±6000). The real engine's default combat map is ±1000 with a ~200u edge return-push zone, so a ship
     * that sprints past |x|≈800 on the SERVER hits a center-ward "perimeter" force the ±6000 CLIENT mission
     * never applies — a sourceless map-edge desync the predict client cannot reconcile away (the same
     * "the server must model every movement-affecting mechanic" class as the system-engage jets boost;
     * owner re-flagged it on the live render-WATCH 2026-06-15).
     *
     * <p>Faithful: drives the engine's OWN public bounds setter — the unique public-instance {@code (FFFF)V}
     * method on the combat-map class that {@code MissionDefinitionAPI.initMap} drives (args
     * {@code minX,maxX,minY,maxY}) — NOT a server-side reimplementation. Fail-soft like the scout:
     * any reflection miss (no combat map bound yet, an ambiguous or absent setter, or a setter that threw)
     * returns a non-applied {@link MapBoundsResult} carrying the cause and NEVER throws, so the fake-binding
     * contract paths (whose opaque engine simply lacks {@code getMapWidth}/the setter) stay untouched.
     */
    /**
     * The combat-map bounds setter = the unique public-instance {@code (FFFF)V} method on the
     * map class (the one {@code MissionDefinitionAPI.initMap} drives), discovered by signature so
     * no engine-internal name is rendered in source. Shared by the round-open re-apply
     * ({@link #applyCombatMapBounds}) and the create-time sizing before {@code setCombatMap}
     * ({@code RealEngineCombatBindingFactory.create}). Throws
     * {@link IllegalStateException} when the setter is ambiguous or absent rather than guessing.
     */
    public static Method findMapBoundsSetter(Class<?> mapClass) {
        Method setBoundsM = null;
        for (Method m : mapClass.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())
                    || m.getReturnType() != void.class || m.getParameterCount() != 4) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p[0] != float.class || p[1] != float.class
                    || p[2] != float.class || p[3] != float.class) {
                continue;
            }
            if (setBoundsM != null) {
                throw new IllegalStateException(
                        "bounds setter ambiguous: " + setBoundsM + " vs " + m);
            }
            setBoundsM = m;
        }
        if (setBoundsM == null) {
            throw new IllegalStateException(
                    "no public instance (FFFF)V method on " + mapClass.getName());
        }
        return setBoundsM;
    }

    public MapBoundsResult applyCombatMapBounds(float half) {
        float widthBefore = Float.NaN;
        float heightBefore = Float.NaN;
        try {
            Method getMapWidthM = engine.getClass().getMethod("getMapWidth");
            Method getMapHeightM = engine.getClass().getMethod("getMapHeight");
            widthBefore = ((Number) getMapWidthM.invoke(engine)).floatValue();
            heightBefore = ((Number) getMapHeightM.invoke(engine)).floatValue();
            Object combatMap = getCombatMap.invoke(engine);
            if (combatMap == null) {
                return MapBoundsResult.failed(half, widthBefore, heightBefore,
                        "getCombatMap() returned null (no combat space bound)");
            }
            // Discovery shared with the create-time sizing; an ambiguous/absent
            // setter throws IllegalStateException, caught below into a fail-soft non-applied result.
            Method setBoundsM = findMapBoundsSetter(combatMap.getClass());
            setBoundsM.invoke(combatMap, -half, half, -half, half);
            float widthAfter = ((Number) getMapWidthM.invoke(engine)).floatValue();
            float heightAfter = ((Number) getMapHeightM.invoke(engine)).floatValue();
            boolean applied = widthAfter == 2.0f * half && heightAfter == 2.0f * half;
            if (applied) {
                // The engine mints the per-side fog-of-war trackers at
                // setCombatMap time with tile grids FROZEN from the map dims of that moment
                // (the tracker class sizes its boolean[][] in its ctor). Every
                // engine mission path sizes the map BEFORE setCombatMap;
                // this binding resizes AFTER, so without a re-mint the fog
                // grids stay at the default ±1000 (4x4 tiles) under a ±half playfield — once a
                // side's ships drift >sightRadius from every in-grid sample point that side's
                // fog reads all-hidden forever and vanilla autofire (correctly) refuses every
                // target. Re-minting the two trackers from the resized map is the engine's own
                // setCombatMap pair, the RealEngineRoundHeapDrain-precedented surgical scope
                // (full setCombatMap re-adds ScriptStore plugins — unaudited side effects).
                System.out.println("[cc-map-bounds] fog-remint " + reflection.remintFogTrackers(combatMap)
                        + " half=" + half);
            }
            return new MapBoundsResult(applied, half, widthBefore, heightBefore, widthAfter, heightAfter,
                    applied ? "" : "setter ran but map width/height != 2*half");
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            return MapBoundsResult.failed(half, widthBefore, heightBefore,
                    c.getClass().getName() + ": " + c.getMessage());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return MapBoundsResult.failed(half, widthBefore, heightBefore,
                    e.getClass().getName() + ": " + e.getMessage());
        }
    }

    /**
     * Resolve the engine ship addressed by the snapshot's stable 1-based
     * repository-order id (the id scheme {@link #shipMotionsOrNull()} and the
     * network client share), or {@code null} when the id is out of range or the
     * slot holds {@code null}. Replaces the prior {@code getPlayerShip()}
     * singleton lookup so a non-player owned ship can be targeted; in every proven
     * scenario the player ship is repository ship 1, so the single-player path is
     * unchanged. Reflection failures propagate as {@link EngineBindingException}.
     */
    private Object shipAtRepositoryIdOrNull(long shipId) {
        if (shipId < 1L) {
            return null;
        }
        List<?> ships = rosterShipList();
        long index = shipId - 1L;
        if (index >= ships.size()) {
            return null;
        }
        return ships.get((int) index);
    }

    /**
     * Project-owned live wireId→engine-ship authority: the roster maintains the
     * map (deploy / midDeploy fly-in / reset); the binding only consults it. {@link #live()} false
     * (between rounds, or a fake/no-roster binding that never wires this) drops the binding back to
     * the legacy positional read, byte-identical.
     */
    public interface LiveWireShipResolver {
        /** The live engine ship mounted at {@code wireId} this round, or null (unmounted seat). */
        Object shipForWireIdOrNull(long wireId);

        /** Whether the map is authoritative right now (a roster is deployed with live seats). */
        boolean live();
    }

    /**
     * Resolve the engine ship addressed by seat wire id {@code shipId}: with a LIVE
     * {@link LiveWireShipResolver} the map is AUTHORITATIVE — a miss is a harmless null drop, and
     * deliberately NEVER falls through to the positional read (under occupancy-gated N deploys the
     * repository is compacted, so position != wireId; a positional fallthrough would command the
     * WRONG ship). No resolver, or not live, = the legacy repository-position read, byte-identical
     * for every fake binding and the pre-descriptor path. ALL seat-addressed resolve sites route
     * through here (intent/command/useSystem/idle-AI/name/pose/facing); snapshot/walker enumeration
     * lanes stay repository-walk BY DESIGN (they enumerate the world, they don't resolve seats).
     */
    Object shipForWireIdOrNull(long shipId) { // pkg-private: PilotCommandApplier
        if (liveShipResolver != null && liveShipResolver.live()) {
            return liveShipResolver.shipForWireIdOrNull(shipId);
        }
        return shipAtRepositoryIdOrNull(shipId);
    }

    /**
     * The human SEAT ships as engine objects — {@link #ownedShipIds} (the ids {@code pilot-dmg-taken
     * ship=} prints) resolved through {@link #shipForWireIdOrNull}, nulls skipped; the SAME loop shape
     * as {@code logHullDropsPass}. A fresh identity set per call (≤ one allocation): the
     * beam feed calls it once per drain and caches it for the tick. A resolve throw propagates to
     * the caller (the probe's own try/catch meters it).
     */
    public Set<Object> seatShipObjects() {
        Set<Object> seatShips = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Long wireId : ownedShipIds) {
            Object ship = shipForWireIdOrNull(wireId);
            if (ship != null) {
                seatShips.add(ship);
            }
        }
        return seatShips;
    }

    /**
     * Authoritative pose write: write the
     * location + velocity of server-owned {@code shipId} directly via
     * {@code BaseEntity.setLoc}/{@code setVel} with freshly constructed lwjgl
     * {@code Vector2f} args. Path B, confirmed authoritative on the real engine
     * — the write survives the next {@code advanceInner} and is not
     * contested by the ship's AI. Fails closed: a non-owned id is
     * {@code REJECTED_NOT_OWNED}, an unbound motion-write boundary is
     * {@code REJECTED_UNSUPPORTED}, and a malformed/unresolvable request is
     * {@code REJECTED_INVALID}. The owned ship is resolved by the same
     * repository-order id scheme as the command sink and the snapshot.
     */
    public ShipPoseSink.Result writeOwnedShipPose(
            long shipId, long simTick, float locX, float locY, float velX, float velY) {
        if (simTick < 0L) {
            return ShipPoseSink.Result.REJECTED_INVALID;
        }
        if (shipSetLoc == null || shipSetVel == null || vector2fCtor == null) {
            return ShipPoseSink.Result.REJECTED_UNSUPPORTED;
        }
        if (!ownedShipIds.contains(shipId)) {
            return ShipPoseSink.Result.REJECTED_NOT_OWNED;
        }
        if (!Float.isFinite(locX) || !Float.isFinite(locY)
                || !Float.isFinite(velX) || !Float.isFinite(velY)) {
            return ShipPoseSink.Result.REJECTED_INVALID;
        }
        try {
            Object ship = shipForWireIdOrNull(shipId);
            if (ship == null) {
                return ShipPoseSink.Result.REJECTED_INVALID;
            }
            Object loc = vector2fCtor.newInstance(locX, locY);
            Object vel = vector2fCtor.newInstance(velX, velY);
            shipSetLoc.invoke(ship, loc);
            shipSetVel.invoke(ship, vel);
            return ShipPoseSink.Result.APPLIED;
        } catch (InvocationTargetException e) {
            throw new EngineBindingException(
                    "BaseEntity.setLoc/setVel threw", e.getCause() != null ? e.getCause() : e);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new EngineBindingException("BaseEntity.setLoc/setVel invocation failed", e);
        }
    }

    /**
     * Pin the owned ship's visual heading to {@code facingDeg} via
     * {@code CombatEntityAPI.setFacing(float)}. The proven predict gate
     * sets the own ship's facing to the
     * wire-carried real heading ({@code ownShipFacingDeg}) each tick BEFORE the engine integrates the
     * replayed translation thrust, so ACCELERATE/STRAFE integrate in the heading the human actually
     * flew (A/D-driven, NOT the cursor — {@code own-ship-facing-ad-driven-not-cursor}). Fails closed
     * like {@link #writeOwnedShipPose}: non-finite is {@code REJECTED_INVALID}, a non-owned id is
     * {@code REJECTED_NOT_OWNED}, an unbound facing seam is {@code REJECTED_UNSUPPORTED}.
     */
    public ShipPoseSink.Result writeOwnedShipFacing(long shipId, long simTick, float facingDeg) {
        if (simTick < 0L || !Float.isFinite(facingDeg)) {
            return ShipPoseSink.Result.REJECTED_INVALID;
        }
        if (shipSetFacing == null) {
            return ShipPoseSink.Result.REJECTED_UNSUPPORTED;
        }
        if (!ownedShipIds.contains(shipId)) {
            return ShipPoseSink.Result.REJECTED_NOT_OWNED;
        }
        try {
            Object ship = shipForWireIdOrNull(shipId);
            if (ship == null) {
                return ShipPoseSink.Result.REJECTED_INVALID;
            }
            shipSetFacing.invoke(ship, facingDeg);
            return ShipPoseSink.Result.APPLIED;
        } catch (InvocationTargetException e) {
            throw new EngineBindingException(
                    "CombatEntityAPI.setFacing threw", e.getCause() != null ? e.getCause() : e);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new EngineBindingException("CombatEntityAPI.setFacing invocation failed", e);
        }
    }

    private void validateCommandObject(String label, Object command) {
        if (playerShipGiveCommand == null || command == null) {
            return;
        }
        Class<?>[] parameterTypes = playerShipGiveCommand.getParameterTypes();
        if (parameterTypes.length != 3 || !parameterTypes[0].isInstance(command)) {
            throw new IllegalArgumentException("movement command " + label
                    + " is not compatible with ShipAPI.giveCommand first parameter");
        }
    }

    Object invoke(Method m, Object target, String label, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw new EngineBindingException(label + " threw", e.getCause() != null ? e.getCause() : e);
        } catch (ReflectiveOperationException e) {
            throw new EngineBindingException(label + " invocation failed", e);
        }
    }

    private static List<?> asList(Object value, String label) {
        if (!(value instanceof List<?>)) {
            throw new EngineBindingException(label + " did not return a List; got "
                    + (value == null ? "null" : value.getClass().getName()), null);
        }
        return (List<?>) value;
    }

    static float[] readXY(Object vector) throws ReflectiveOperationException {
        if (vector == null) {
            return null;
        }
        Field fx = vector.getClass().getField("x");
        Field fy = vector.getClass().getField("y");
        float x = fx.getFloat(vector);
        float y = fy.getFloat(vector);
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            return null;
        }
        return new float[] { x, y };
    }

    // ONE-PER-LIFETIME spawn row for a weaponless-sourced
    // (Doom Mine Strike) missile, emitted from the WEAPONLESS-SOURCED branch on the first read that
    // mints the stable id. Grades the placement divergence (client ~1000 su straight ahead
    // vs server ~420 off-nose): the mine's own loc, the source ship's loc/facing/getMouseTarget()
    // aim, and derived dist/bearing/aimDist/mineToAim. Fully fail-soft — every token reads
    // independently and prints "?" if unreadable; the row is NEVER skipped and this method NEVER
    // throws (its own try/catch keeps the caller's source-pair catch untouched). Event-rate, not
    // per-tick. Reflective on the engine's Ship `source`, the getLocation/getFacing idiom the
    // missile pass already uses; getMouseTarget() returns an lwjgl Vector2f read via readXY.
    private static void emitMineSpawnRow(long stableId, long srcShipId, String key,
            float[] mineLoc, Object source, Object mine) {
        try {
            // mineFacing = the MINE body's own facing at spawn,
            // paired by id with the client enroll row's mineFacing= (the link-snap read; facing= stays
            // the source SHIP's). Same reflective getFacing idiom, fail-soft "?".
            String mineFacing = "?";
            try {
                Float mf = numberAsFloat(mine.getClass().getMethod("getFacing").invoke(mine), "getFacing");
                if (mf != null) {
                    mineFacing = fmt1(mf);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // token stays "?"
            }
            float[] shipLoc = null;
            Float facingDeg = null;
            float[] aimLoc = null;
            try {
                shipLoc = readXY(source.getClass().getMethod("getLocation").invoke(source));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // token stays "?"
            }
            try {
                facingDeg = numberAsFloat(
                        source.getClass().getMethod("getFacing").invoke(source), "getFacing");
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // token stays "?"
            }
            try {
                aimLoc = readXY(source.getClass().getMethod("getMouseTarget").invoke(source));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // token stays "?"
            }
            String shipX = shipLoc == null ? "?" : fmt1(shipLoc[0]);
            String shipY = shipLoc == null ? "?" : fmt1(shipLoc[1]);
            String facing = facingDeg == null ? "?" : fmt1(facingDeg);
            String aimX = aimLoc == null ? "?" : fmt1(aimLoc[0]);
            String aimY = aimLoc == null ? "?" : fmt1(aimLoc[1]);
            String dist = "?";
            String bearing = "?";
            String aimDist = "?";
            String mineToAim = "?";
            if (shipLoc != null) {
                float dx = mineLoc[0] - shipLoc[0];
                float dy = mineLoc[1] - shipLoc[1];
                dist = fmt1((float) Math.hypot(dx, dy));
                if (facingDeg != null) {
                    bearing = fmt1((float) norm180(Math.toDegrees(Math.atan2(dy, dx)) - facingDeg));
                }
                if (aimLoc != null) {
                    aimDist = fmt1((float) Math.hypot(aimLoc[0] - shipLoc[0], aimLoc[1] - shipLoc[1]));
                }
            }
            if (aimLoc != null) {
                mineToAim = fmt1((float) Math.hypot(mineLoc[0] - aimLoc[0], mineLoc[1] - aimLoc[1]));
            }
            System.out.println("cc-mine-spawn id=" + stableId + " src=" + srcShipId
                    + " spec=" + key
                    + " loc=(" + fmt1(mineLoc[0]) + "," + fmt1(mineLoc[1]) + ")"
                    + " ship=(" + shipX + "," + shipY + ")"
                    + " facing=" + facing
                    + " mineFacing=" + mineFacing
                    + " aim=(" + aimX + "," + aimY + ")"
                    + " dist=" + dist
                    + " bearing=" + bearing
                    + " aimDist=" + aimDist
                    + " mineToAim=" + mineToAim);
        } catch (RuntimeException outer) {
            // Fail-soft: an event-rate diagnostic never disrupts the read loop.
        }
    }

    // one-decimal float, Locale.ROOT (no import).
    private static String fmt1(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    // Wrap degrees to (-180, 180].
    private static double norm180(double deg) {
        double d = deg % 360.0;
        if (d <= -180.0) {
            d += 360.0;
        } else if (d > 180.0) {
            d -= 360.0;
        }
        return d;
    }

    /** {@code retainPosId > 0} additionally retains the quantized cells for that position id
     *  (the roster-walk path); {@code ≤ 0} = read-only (debug riders), {@code cells} null. */
    private ArmorGridSnapshot armorGridSnapshot(Object armorGrid, long retainPosId)
            throws ReflectiveOperationException {
        if (armorGrid == null) {
            return null;
        }
        Object rawGrid = armorGridGetGrid.invoke(armorGrid);
        if (!(rawGrid instanceof float[][])) {
            return null;
        }
        float[][] grid = (float[][]) rawGrid;
        long h = 0xcbf29ce484222325L;
        h = mix(h, grid.length);
        int cellCount = 0;
        float total = 0.0f;
        for (float[] row : grid) {
            if (row == null) {
                return null;
            }
            h = mix(h, row.length);
            for (float value : row) {
                if (!Float.isFinite(value)) {
                    return null;
                }
                h = mix(h, Float.floatToIntBits(value));
                total += value;
                cellCount++;
            }
        }
        if (!Float.isFinite(total)) {
            return null;
        }
        // armorLevel = total / (cellCount × maxInCell) — the engine-exact
        // pool fraction (every cell mints at getMaxArmorInCell()). A cell-less or
        // zero-max grid reads 0 (no pool to be a fraction of), never a division fault.
        float maxInCell = numberAsFloat(
                armorGridGetMaxArmorInCell.invoke(armorGrid), "getMaxArmorInCell");
        float level = (cellCount > 0 && maxInCell > 0f)
                ? total / (cellCount * maxInCell)
                : 0f;
        if (!Float.isFinite(level)) {
            return null;
        }
        byte[] cells = (retainPosId > 0L)
                ? armorCellRetainer.retain(grid, cellCount, maxInCell, h, retainPosId)
                : null;
        return new ArmorGridSnapshot(cellCount, total, h, level, cells);
    }

    /** D-OWNARMOR (b) instrument — victim POST-hit armor pool for
     *  the hulldelta row ({@code " armorPost=<int sum> armorMax=<int cellCount×maxInCell>"}, empty on
     *  miss). Polled detector = post-hit only, so armorPre is unavailable. Fail-soft (never throws). */
    private String armorPoolFields(Object ship) {
        try {
            Object armorGrid = shipGetArmorGrid.invoke(ship);
            ArmorGridSnapshot armor = armorGridSnapshot(armorGrid, 0L);
            if (armor == null) return "";
            float maxInCell = numberAsFloat(armorGridGetMaxArmorInCell.invoke(armorGrid), "getMaxArmorInCell");
            return " armorPost=" + Math.round(armor.total)
                    + " armorMax=" + Math.round((double) armor.cellCount * (double) maxInCell);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    static float numberAsFloat(Object value, String label) {
        if (!(value instanceof Number)) {
            throw new EngineBindingException(label + " did not return a Number; got "
                    + (value == null ? "null" : value.getClass().getName()), null);
        }
        float f = ((Number) value).floatValue();
        if (!Float.isFinite(f)) {
            throw new EngineBindingException(label + " returned non-finite value: " + f, null);
        }
        return f;
    }

    static int numberAsInt(Object value, String label) {
        if (!(value instanceof Number)) {
            throw new EngineBindingException(label + " did not return a Number; got "
                    + (value == null ? "null" : value.getClass().getName()), null);
        }
        return ((Number) value).intValue();
    }

    /** Fail-soft boolean read: {@code null} when the value is not a {@link Boolean}. */
    static Boolean asBoolean(Object value) {
        return (value instanceof Boolean) ? (Boolean) value : null;
    }

    /**
     * Fail-soft enum-name read: the {@link Enum#name()} when the value is any
     * {@link Enum} (e.g. the engine's {@code ShipSystemAPI$SystemState}), else
     * {@code null}. Reads the enum identity without referencing the game enum type,
     * keeping the engine class behind the bridge.
     */
    static String enumName(Object value) {
        return (value instanceof Enum<?>) ? ((Enum<?>) value).name() : null;
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "<null>";
        }
        String message = t.getMessage();
        return t.getClass().getName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private static long mix(long h, int value) {
        h ^= (value & 0xffffffffL);
        h *= 0x100000001b3L;
        return h;
    }

    private static final class ArmorGridSnapshot {
        private final int cellCount;
        private final float total;
        private final long hash;
        private final float level;
        /** Retainer's live quantized buffer (aliased, D-FOEARMOR B0); null off the roster walk. */
        private final byte[] cells;

        private ArmorGridSnapshot(int cellCount, float total, long hash, float level, byte[] cells) {
            this.cellCount = cellCount;
            this.total = total;
            this.hash = hash;
            this.level = level;
            this.cells = cells;
        }
    }

    /** Fluent builder; the scout fills it from descriptor-resolved handles. */
    public static final class Builder {
        // Fields WITHOUT `private` are the pilot-command handles PilotCommandApplier copies straight
        // from this Builder in its constructor: the Builder is the handle
        // manifest, so a new pilot bit lands as one setter here + one field + one applyXBit THERE.
        private Object engine;
        private Method advanceInner;
        private Method advanceOuter;
        private Object stepAmountArg;
        private Method getObjects;
        private Method repositoryGetList;
        private Class<?> shipClass;
        private Method getFleetManagers;
        private Method getCombatMap;
        Method getPlayerShip;
        private Method playerShipGetLocation;
        private Method playerShipGetVelocity;
        private Method playerShipGetFacing;
        private Method playerShipGetAngularVelocity;
        private Method shipGetHitpoints;
        private Method shipGetHullLevel;
        private Method shipGetArmorGrid;
        private Method armorGridGetGrid;
        private Method armorGridGetMaxArmorInCell;
        private Method shipGetHullSpec;
        private Method hullSpecGetHullId;
        private Method shipGetId;
        private Method shipIsAlive;
        private Method shipGetOwner;
        private Method shipGetEngineController;
        private Method controllerGetShipEngines;
        private Method controllerIsFlamingOut;
        private Method controllerIsDisabled;
        private Method engineIsDisabled;
        private Method engineIsPermanentlyDisabled;
        private Method maneuverGetEngineController;
        private Method controllerIsAccelerating;
        private Method controllerIsAcceleratingBackwards;
        private Method controllerIsDecelerating;
        private Method controllerIsTurningLeft;
        private Method controllerIsTurningRight;
        private Method controllerIsStrafingLeft;
        private Method controllerIsStrafingRight;
        Method shipGetFluxTracker;
        Method fluxGetCurrFlux;
        Method fluxGetMaxFlux;
        Method fluxGetFluxLevel;
        private Method fluxGetHardFlux;
        Method fluxIsOverloaded;
        Method fluxIsVenting;
        Method shipGetShield;
        Method shieldIsOn;
        private Method shieldGetFacing;
        Method shieldGetActiveArc;
        Method shipGetAllWeapons;
        Method weaponGetSpec;
        Method weaponSpecIsBeam;
        Method weaponSpecGetWeaponId;
        Method projSpecGetRefireDelay;
        Method projSpecGetBurstSize;
        Method projSpecGetBurstDelay;
        Class<?> projectileWeaponSpecApi;
        private Method weaponGetId;
        Method weaponGetCooldownRemaining;
        private Method weaponGetCooldown;
        Method weaponIsFiring;
        Method weaponUsesAmmo;
        Method weaponGetAmmo;
        private Method weaponGetMaxAmmo;
        Method weaponIsDisabled;
        Method weaponIsForceDisabled; // Optional un-fold of the script force-disable
        private Method weaponGetChargeLevel;
        Method weaponGetFluxCostToFire; // readiness flux-headroom read
        Method weaponDistanceFromArc; // readiness live-arc read
        Method weaponGetLocation; // adopt-serve muzzle-ε read
        Method weaponSetForceFireOneFrame; // named-mount serve write
        private Method weaponGetCurrAngle;
        Method weaponGetProjectileSpeed;
        private Method projectileGetElapsed;
        private Method projectileGetWeapon;
        private Method projectileGetVelocity;
        private Method projectileGetLocation;
        private Method projectileGetFacing;
        Method shipGetSystem;
        private Method systemGetId;
        Method systemGetState;
        Method systemIsActive;
        Method systemIsOn;
        Method systemGetCooldownRemaining;
        private Method systemGetCooldown;
        private Method systemGetEffectLevel;
        Method systemGetAmmo;
        private Method systemGetMaxAmmo;
        private Method systemIsOutOfAmmo;
        private Method shipGetHullSize;
        private Method entityGetCollisionRadius;
        private Method entityGetMass;
        private Method shipGetMutableStats;
        private Method statsGetMaxSpeed;
        private Method statsGetAcceleration;
        private Method statsGetDeceleration;
        private Method statsGetMaxTurnRate;
        private Method statsGetTurnAcceleration;
        private Method statGetModifiedValue;
        private Method statsGetTimeMult;
        private Method combatGetProjectiles;
        private Method combatGetMissiles;
        private Method combatGetBeams;
        private Method engineGetWinningSideId;
        private Method engineGetCustomData;
        private Method engineIsCombatOver;
        private Method engineIsPaused;
        private Method engineSetPaused;
        Method playerShipGiveCommand;
        Object movementCommandAccelerate;
        Object movementCommandDecelerate;
        Object movementCommandStrafeLeft;
        Object movementCommandStrafeRight;
        private Object movementCommandTurnLeft;
        private Object movementCommandTurnRight;
        Object movementCommandAccelerateBackwards;
        Object weaponCommandSelectGroup;
        Object weaponCommandFire;
        private Object weaponCommandHoldFire;
        Object weaponCommandVent;
        Object weaponCommandToggleShield;
        Method shipGetPhaseCloak;
        Object weaponCommandPullBackFighters;
        Method shipIsPullBackFighters;
        private Method shipIsPhased;
        private Method shipGetCurrentCR;
        private Method shipGetSharedFighterReplacementRate;
        private Method shipGetTimeDeployedForCR;
        private Method statsGetPeakCRDuration;
        private Method statBonusComputeEffective;
        private Method hullSpecGetNoCRLossTime;
        Method playerShipUseSystem;
        Method systemCanBeActivated;
        private Method shipSetLoc;
        private Method shipSetVel;
        Method shipSetFacing;
        Method shipIsTravelDriveOn;
        Method fireLockGetSystem;
        Method fireLockGetTravelDrive;
        Method fireLockGetPhaseCloak;
        Method fireLockShouldBlockCommands;
        Method fireLockGetSpec;
        Method fireLockIsFiringAllowed;
        Method fireLockGetBlockedCommands;
        Object fireLockBlockedKey;
        Method shipGetWeaponGroupsCopy;
        Method shipGetSelectedGroupAPI;
        Method shipGetWeaponGroupFor;
        Method weaponGroupIsAutofiring;
        private Method weaponGroupToggleOff;
        Method weaponGetSlot;
        Method weaponSlotGetId;
        Object weaponCommandToggleAutofire;
        private Method groupGetType;
        private Method groupGetActiveWeapon;
        Method groupGetWeaponsCopy;
        private Method groupSetCurrentActive;
        private Object weaponGroupTypeAlternating;
        Constructor<?> vector2fCtor;
        Method shipGetMouseTarget;
        Method vector2fSet;
        private Method shipSetShipAI;
        private Method shipGetShipAI;
        Method shipSetRetreating;
        Method retreatManeuverEngage;
        Method shipTurnOffTravelDrive;
        Method shipIsRetreating;
        Method shipSetShipTarget;
        Method shipGetShipTarget;
        Method shipIsFighter;
        private Method engineIsInFastTimeAdvance;
        private Set<Long> ownedShipIds;
        private LiveWireShipResolver liveShipResolver;
        private float timeMult = 1.0f;
        private float combatSpeedMult = 1.0f;
        private boolean fogBound;

        public Builder engine(Object v) { this.engine = v; return this; }

        public Builder advanceInner(Method method, Object amountArg) {
            this.advanceInner = method;
            this.stepAmountArg = amountArg;
            return this;
        }

        public Builder advanceOuter(Method method, Object amountArg) {
            this.advanceOuter = method;
            this.stepAmountArg = amountArg;
            return this;
        }

        public Builder repository(Method getObjectsMethod, Method getListMethod, Class<?> shipType) {
            this.getObjects = getObjectsMethod;
            this.repositoryGetList = getListMethod;
            this.shipClass = shipType;
            return this;
        }

        public Builder fleetManagers(Method method) { this.getFleetManagers = method; return this; }

        public Builder combatMap(Method method) { this.getCombatMap = method; return this; }

        public Builder playerShip(Method method) { this.getPlayerShip = method; return this; }

        public Builder playerShipLocation(Method method) { this.playerShipGetLocation = method; return this; }

        public Builder playerShipVelocity(Method method) { this.playerShipGetVelocity = method; return this; }

        public Builder playerShipFacing(Method method) { this.playerShipGetFacing = method; return this; }

        public Builder playerShipAngularVelocity(Method method) { this.playerShipGetAngularVelocity = method; return this; }

        public Builder shipDurability(
                Method getHitpoints,
                Method getHullLevel,
                Method getArmorGrid,
                Method armorGridGetGrid,
                Method armorGridGetMaxArmorInCell) {
            this.shipGetHitpoints = getHitpoints;
            this.shipGetHullLevel = getHullLevel;
            this.shipGetArmorGrid = getArmorGrid;
            this.armorGridGetGrid = armorGridGetGrid;
            this.armorGridGetMaxArmorInCell = armorGridGetMaxArmorInCell;
            return this;
        }

        /**
         * Bind the content-identity read: {@code ShipAPI.getHullSpec()}
         * and {@code ShipHullSpecAPI.getHullId()}. Both null leaves
         * {@link #shipIdentitiesOrNull()} unavailable (the snapshot stays
         * content-neutral), so this is optional like the durability handles.
         */
        public Builder shipIdentity(Method getHullSpec, Method getHullId) {
            this.shipGetHullSpec = getHullSpec;
            this.hullSpecGetHullId = getHullId;
            return this;
        }

        /**
         * Bind the lifecycle read: {@code ShipAPI.getId()} (the engine's
         * stable cross-tick entity id) and {@code ShipAPI.isAlive()} (liveness). Both
         * null leaves {@link #shipLifecyclesOrNull()} unavailable (the live snapshot
         * source then stays in position-keyed mode), so this is optional like the
         * durability/identity handles.
         */
        public Builder shipLifecycle(Method getId, Method isAlive) {
            this.shipGetId = getId;
            this.shipIsAlive = isAlive;
            return this;
        }

        /**
         * Bind the allegiance read: {@code CombatEntityAPI.getOwner()} (the
         * owning {@code FleetSide} ordinal) and the {@code ShipAPI.isAlive()}
         * liveness. Either null leaves {@link #shipAllegiancesOrNull()} (and so the
         * roster-derived {@link #combatOutcomeFromRosterOrNull()}) unavailable (returns
         * null), so this is optional like the other per-ship read handles. The
         * {@code isAlive} handle is the same one {@link #shipLifecycle(Method, Method)}
         * binds; pass the identical descriptor-resolved {@link Method}.
         */
        public Builder shipAllegiance(Method getOwner, Method isAlive) {
            this.shipGetOwner = getOwner;
            this.shipIsAlive = isAlive;
            return this;
        }

        /**
         * Bind the engine-state read: {@code ShipAPI.getEngineController()},
         * the controller's {@code getShipEngines()} / {@code isFlamingOut()} /
         * {@code isDisabled()}, and per engine {@code isDisabled()} /
         * {@code isPermanentlyDisabled()} (all public
         * {@code ShipEngineControllerAPI}[{@code $ShipEngineAPI}] surface). Any null
         * leaves {@link #shipEngineStatesOrNull()} unavailable (returns null), so this is
         * optional like the other per-ship read handles.
         */
        public Builder shipEngineState(
                Method getEngineController,
                Method getShipEngines,
                Method ctrlIsFlamingOut,
                Method ctrlIsDisabled,
                Method engIsDisabled,
                Method engIsPermanentlyDisabled) {
            this.shipGetEngineController = getEngineController;
            this.controllerGetShipEngines = getShipEngines;
            this.controllerIsFlamingOut = ctrlIsFlamingOut;
            this.controllerIsDisabled = ctrlIsDisabled;
            this.engineIsDisabled = engIsDisabled;
            this.engineIsPermanentlyDisabled = engIsPermanentlyDisabled;
            return this;
        }

        /**
         * Bind the maneuver-command read: {@code ShipAPI.getEngineController()}
         * (its own handle, so this tier stays available independently of
         * {@link #shipEngineState}) plus the controller's 7 maneuver booleans
         * ({@code isAccelerating}, {@code isAcceleratingBackwards}, {@code isDecelerating},
         * {@code isTurningLeft}, {@code isTurningRight}, {@code isStrafingLeft},
         * {@code isStrafingRight} — all public {@code ShipEngineControllerAPI} surface, in
         * canonical wire-bit order). Any null leaves {@link #shipManeuverCommandsOrNull()}
         * unavailable (returns null), so this is optional like the other per-ship read
         * handles.
         */
        public Builder shipManeuverCommands(
                Method getEngineController,
                Method isAccelerating,
                Method isAcceleratingBackwards,
                Method isDecelerating,
                Method isTurningLeft,
                Method isTurningRight,
                Method isStrafingLeft,
                Method isStrafingRight) {
            this.maneuverGetEngineController = getEngineController;
            this.controllerIsAccelerating = isAccelerating;
            this.controllerIsAcceleratingBackwards = isAcceleratingBackwards;
            this.controllerIsDecelerating = isDecelerating;
            this.controllerIsTurningLeft = isTurningLeft;
            this.controllerIsTurningRight = isTurningRight;
            this.controllerIsStrafingLeft = isStrafingLeft;
            this.controllerIsStrafingRight = isStrafingRight;
            return this;
        }

        /**
         * Bind the flux read: {@code ShipAPI.getFluxTracker()} then the
         * {@code FluxTrackerAPI} scalars/booleans ({@code getCurrFlux}, {@code getMaxFlux},
         * {@code getFluxLevel}, {@code getHardFlux}, {@code isOverloaded}, {@code isVenting}).
         * Any null leaves {@link #shipFluxStatesOrNull()} unavailable (returns null), so
         * this is optional like the durability/identity/lifecycle handles.
         */
        public Builder shipFlux(
                Method getFluxTracker,
                Method getCurrFlux,
                Method getMaxFlux,
                Method getFluxLevel,
                Method getHardFlux,
                Method isOverloaded,
                Method isVenting) {
            this.shipGetFluxTracker = getFluxTracker;
            this.fluxGetCurrFlux = getCurrFlux;
            this.fluxGetMaxFlux = getMaxFlux;
            this.fluxGetFluxLevel = getFluxLevel;
            this.fluxGetHardFlux = getHardFlux;
            this.fluxIsOverloaded = isOverloaded;
            this.fluxIsVenting = isVenting;
            return this;
        }

        /**
         * Bind the optional shield read: {@code ShipAPI.getShield()} (nullable
         * for shieldless hulls) and {@code ShieldAPI.isOn()}. Both null leaves the shield
         * fields {@code hasShield=false}/{@code shieldOn=false} on every flux view (the
         * flux scalars still cross). Independent of {@link #shipFlux} so a build can carry
         * flux without shield.
         */
        public Builder shipShield(Method getShield, Method isOn) {
            return shipShield(getShield, isOn, null, null);
        }

        /**
         * Shield read with the optional {@code ShieldAPI.getFacing()} handle
         * and the {@code ShieldAPI.getActiveArc()} idle read (the unified press
         * law's raise gate; null fails the shield APPLY lane
         * closed, the view tiers don't consume it). A null facing handle degrades every
         * view to {@code shieldFacing=0} (consumers gate on {@code hasShield}), keeping
         * the 2-arg overload's contract intact.
         */
        public Builder shipShield(Method getShield, Method isOn, Method getFacing,
                Method getActiveArc) {
            this.shipGetShield = getShield;
            this.shieldIsOn = isOn;
            this.shieldGetFacing = getFacing;
            this.shieldGetActiveArc = getActiveArc;
            return this;
        }

        /**
         * Bind the weapon-mount read: {@code ShipAPI.getAllWeapons()} then
         * per mount {@code WeaponAPI.getId()}, {@code getCooldownRemaining()},
         * {@code getCooldown()}, {@code isFiring()}, {@code usesAmmo()}, {@code getAmmo()},
         * {@code getMaxAmmo()}, {@code isDisabled()}, {@code getChargeLevel()}. Any null
         * leaves {@link #weaponMountStatesOrNull()} unavailable (returns null), so this
         * is optional like the durability/identity/lifecycle/flux handles.
         */
        public Builder weaponMounts(
                Method getAllWeapons,
                Method getId,
                Method getCooldownRemaining,
                Method getCooldown,
                Method isFiring,
                Method usesAmmo,
                Method getAmmo,
                Method getMaxAmmo,
                Method isDisabled,
                Method getChargeLevel) {
            this.shipGetAllWeapons = getAllWeapons;
            this.weaponGetId = getId;
            this.weaponGetCooldownRemaining = getCooldownRemaining;
            this.weaponGetCooldown = getCooldown;
            this.weaponIsFiring = isFiring;
            this.weaponUsesAmmo = usesAmmo;
            this.weaponGetAmmo = getAmmo;
            this.weaponGetMaxAmmo = getMaxAmmo;
            this.weaponIsDisabled = isDisabled;
            this.weaponGetChargeLevel = getChargeLevel;
            return this;
        }

        /**
         * Bind {@code WeaponAPI.isForceDisabled()} so the wire's per-mount
         * disabled bit excludes script force-disables (optional; null keeps the folded read).
         */
        public Builder weaponForceDisabled(Method isForceDisabled) {
            this.weaponIsForceDisabled = isForceDisabled;
            return this;
        }

        /**
         * Bind the readiness probe's flux-headroom read
         * {@code WeaponAPI.getFluxCostToFire()} (public API, same family as
         * {@link #weaponMounts}; additive so every existing caller — including the fake
         * factory — stays untouched). Null/unbound = the probe skips the flux-headroom
         * condition (fail-open toward pressing, the earlier path).
         */
        public Builder weaponFluxCostToFire(Method getFluxCostToFire) {
            this.weaponGetFluxCostToFire = getFluxCostToFire;
            return this;
        }

        /**
         * Bind the readiness probe's live-arc read
         * {@code WeaponAPI.distanceFromArc(Vector2f)} (public API, same family as
         * {@link #weaponMounts}; additive like {@link #weaponFluxCostToFire} so every
         * existing caller — including the fake factory — stays untouched). Null/unbound =
         * the probe skips the arc condition (fail-open toward pressing, the earlier path).
         */
        public Builder weaponDistanceFromArc(Method distanceFromArc) {
            this.weaponDistanceFromArc = distanceFromArc;
            return this;
        }

        /**
         * Bind the adopt-serve pair {@code WeaponAPI.getLocation()} (the
         * muzzle-ε validation read) + {@code WeaponAPI.setForceFireOneFrame(boolean)} (the
         * named-mount serve write; public API — the engine consumes the forced trigger
         * one-shot self-clearing with every resource gate preserved, a scoped exception to the
         * engine-driven-first rule). Additive like {@link #weaponDistanceFromArc}
         * so every existing caller — including the fake factory — stays untouched. Unlike the readiness probe's
         * fail-OPEN seams, the adopt serve is fail-CLOSED: null/unbound handles make every
         * ray-carrying declaration reject {@code adopt-seam} LOUD (never a guessed serve).
         */
        public Builder weaponAdoptServe(Method getLocation, Method setForceFireOneFrame) {
            this.weaponGetLocation = getLocation;
            this.weaponSetForceFireOneFrame = setForceFireOneFrame;
            return this;
        }

        /**
         * Bind the optional mount-facing read {@code WeaponAPI.getCurrAngle()}
         * (the {@code aimFacingDeg} payload of a {@code WEAPON_DISCHARGE} edge event —
         * for every non-missile discharge and
         * the missile fallback; a missile's payload is its fresh spawn's
         * facing, {@link #projectileSpawnReads}). Independent of {@link #weaponMounts}
         * like {@link #shipShield} is of {@link #shipFlux}: null (or a failing read)
         * degrades {@link WeaponMountView#currAngle()} to {@code NaN} without making the
         * mount tier unavailable.
         */
        public Builder weaponCurrAngle(Method getCurrAngle) {
            this.weaponGetCurrAngle = getCurrAngle;
            return this;
        }

        /**
         * Bind the optional GPEE-v2 muzzle-speed read
         * {@code WeaponAPI.getProjectileSpeed()} (one input of the
         * {@code flightBearingDeg} composition). Independent of
         * {@link #weaponMounts} like {@link #weaponCurrAngle}: null (or a failing read)
         * degrades {@link WeaponMountView#projectileSpeed()} to {@code NaN} without
         * making the mount tier unavailable. The shooter-velocity half of the
         * composition rides the already-bound {@link #playerShipVelocity} handle.
         */
        public Builder weaponProjectileSpeed(Method getProjectileSpeed) {
            this.weaponGetProjectileSpeed = getProjectileSpeed;
            return this;
        }

        /**
         * Bind the BASE-spec fire-class reads (the
         * production {@code FireClassResolver} inputs; never the live stat-modified
         * {@code WeaponAPI.getCooldown()}, hullmod/skill-skewed). Nullable as one tier:
         * any null leaves {@link EngineCombatBinding#fireClassResolverForShip} answering
         * class H — fail-safe, H is today's proven lane.
         */
        public Builder weaponSpecClass(Method getSpec, Method isBeam, Method getWeaponId,
                Method getRefireDelay, Method getBurstSize, Method getBurstDelay,
                Class<?> projectileWeaponSpecApi) {
            this.weaponGetSpec = getSpec;
            this.weaponSpecIsBeam = isBeam;
            this.weaponSpecGetWeaponId = getWeaponId;
            this.projSpecGetRefireDelay = getRefireDelay;
            this.projSpecGetBurstSize = getBurstSize;
            this.projSpecGetBurstDelay = getBurstDelay;
            this.projectileWeaponSpecApi = projectileWeaponSpecApi;
            return this;
        }

        /**
         * Bind the optional GPEE-v3 fresh-spawn bearing reads:
         * {@code DamagingProjectileAPI.getElapsed()} / {@code getWeapon()}
         * plus {@code CombatEntityAPI.getVelocity()} — the just-spawned projectile's
         * own velocity line (true per-shot spread), keyed back to its firing weapon;
         * plus (GPEE v8, shot-fidelity-budget) {@code CombatEntityAPI.getLocation()}
         * — the same fresh entity's birth position for the spawn-truth muzzle pair.
         * Rides the already-bound {@link #combatEntityLists} projectile/missile list
         * handles. Independent of {@link #weaponMounts} like {@link #weaponCurrAngle}:
         * any null degrades {@link WeaponMountView#freshSpawnBearingDeg()} to
         * {@code NaN} (the detector falls back to the v2 composition) without making
         * the mount tier unavailable; a null {@code getLocation} alone degrades only
         * the muzzle pair. Plus
         * {@code CombatEntityAPI.getFacing()} — the fresh MISSILE's birth facing, the
         * missile branch of the discharge {@code aimFacingDeg} payload
         * ({@code LaunchFacingLaw}); a null {@code getFacing} alone degrades only
         * {@link WeaponMountView#freshSpawnFacingDeg()} (a counted mount fallback).
         */
        public Builder projectileSpawnReads(Method getElapsed, Method getWeapon, Method getVelocity,
                Method getLocation, Method getFacing) {
            this.projectileGetElapsed = getElapsed;
            this.projectileGetWeapon = getWeapon;
            this.projectileGetVelocity = getVelocity;
            this.projectileGetLocation = getLocation;
            this.projectileGetFacing = getFacing;
            return this;
        }

        /**
         * Bind the primary ship-system read: {@code ShipAPI.getSystem()}
         * then the {@code ShipSystemAPI} reads {@code getId()}, {@code getState()},
         * {@code isActive()}, {@code isOn()}, {@code getCooldownRemaining()},
         * {@code getCooldown()}, {@code getEffectLevel()}, {@code getAmmo()},
         * {@code getMaxAmmo()}, {@code isOutOfAmmo()}. Any null leaves
         * {@link #shipSystemStatesOrNull()} unavailable (returns null), so this is
         * optional like the durability/identity/lifecycle/flux/weapon-mount handles.
         */
        public Builder shipSystem(
                Method getSystem,
                Method getId,
                Method getState,
                Method isActive,
                Method isOn,
                Method getCooldownRemaining,
                Method getCooldown,
                Method getEffectLevel,
                Method getAmmo,
                Method getMaxAmmo,
                Method isOutOfAmmo) {
            this.shipGetSystem = getSystem;
            this.systemGetId = getId;
            this.systemGetState = getState;
            this.systemIsActive = isActive;
            this.systemIsOn = isOn;
            this.systemGetCooldownRemaining = getCooldownRemaining;
            this.systemGetCooldown = getCooldown;
            this.systemGetEffectLevel = getEffectLevel;
            this.systemGetAmmo = getAmmo;
            this.systemGetMaxAmmo = getMaxAmmo;
            this.systemIsOutOfAmmo = isOutOfAmmo;
            return this;
        }

        /**
         * Bind the ship physical-profile read: {@code ShipAPI.getHullSize()}
         * (the {@code HullSize} enum, read by name) plus the {@code CombatEntityAPI}
         * geometry/physics reads {@code getCollisionRadius()} and {@code getMass()}.
         * Any null leaves {@link #shipProfilesOrNull()} unavailable (returns null), so
         * this is optional like the durability/identity/lifecycle/flux/weapon-mount/
         * system handles.
         */
        public Builder shipProfile(Method getHullSize, Method getCollisionRadius, Method getMass) {
            this.shipGetHullSize = getHullSize;
            this.entityGetCollisionRadius = getCollisionRadius;
            this.entityGetMass = getMass;
            return this;
        }

        /**
         * Bind the ship maneuverability read: {@code ShipAPI.getMutableStats()}
         * then the {@code MutableShipStatsAPI} maneuver getters {@code getMaxSpeed()},
         * {@code getAcceleration()}, {@code getDeceleration()}, {@code getMaxTurnRate()},
         * {@code getTurnAcceleration()} — each a {@code MutableStat} crossed by
         * {@code getModifiedValue()}. Any null leaves
         * {@link #shipManeuverProfilesOrNull()} unavailable (returns null), so this is
         * optional like the durability/identity/lifecycle/flux/weapon-mount/system/
         * profile handles.
         */
        public Builder shipManeuver(
                Method getMutableStats,
                Method getMaxSpeed,
                Method getAcceleration,
                Method getDeceleration,
                Method getMaxTurnRate,
                Method getTurnAcceleration,
                Method statGetModifiedValue) {
            this.shipGetMutableStats = getMutableStats;
            this.statsGetMaxSpeed = getMaxSpeed;
            this.statsGetAcceleration = getAcceleration;
            this.statsGetDeceleration = getDeceleration;
            this.statsGetMaxTurnRate = getMaxTurnRate;
            this.statsGetTurnAcceleration = getTurnAcceleration;
            this.statGetModifiedValue = statGetModifiedValue;
            return this;
        }

        /**
         * The {@code MutableShipStatsAPI.getTimeMult()} read backing the
         * live per-ship motion-view time multiplier (crossed by the shipManeuver group's
         * {@code MutableStat.getModifiedValue()}). Null (fake bindings, resolution miss) leaves
         * the motion view on the constructor constant — exactly the pre-1264 behavior.
         */
        public Builder shipTimeMultRead(Method statsGetTimeMult) {
            this.statsGetTimeMult = statsGetTimeMult;
            return this;
        }

        /**
         * Bind the non-ship entity list accessors:
         * {@code CombatEngineAPI.getProjectiles()}, {@code getMissiles()}, and
         * {@code getBeams()}. Each is optional and nullable; an unbound handle
         * leaves the matching {@code *EntitiesOrNull()} read unavailable (returns
         * {@code null}) without disturbing the ship reads.
         */
        public Builder combatEntityLists(Method getProjectiles, Method getMissiles, Method getBeams) {
            this.combatGetProjectiles = getProjectiles;
            this.combatGetMissiles = getMissiles;
            this.combatGetBeams = getBeams;
            return this;
        }

        /**
         * Bind the combat-outcome read: the public
         * {@code CombatEngineAPI.getWinningSideId()} and {@code isCombatOver()}.
         * Either null leaves {@link #combatOutcomeOrNull()} unavailable (returns
         * null), so this is optional like the per-ship read handles.
         */
        public Builder combatOutcome(Method getWinningSideId, Method isCombatOver) {
            this.engineGetWinningSideId = getWinningSideId;
            this.engineIsCombatOver = isCombatOver;
            return this;
        }

        /**
         * Bind the public {@code CombatEngineAPI.getCustomData()} read the
         * ship-system tier uses to lift the Ziggurat attractor's target
         * ({@code MoteControlScript.SharedMoteAIData}) onto the mote_control ON edge. Optional:
         * null leaves that payload off (plain SYSTEM_WINDOW edges, never a throw).
         */
        public Builder engineCustomData(Method getCustomData) {
            this.engineGetCustomData = getCustomData;
            return this;
        }

        /**
         * Bind the engine pause control: {@code CombatEngineAPI.isPaused()} (the VERIFY-FIRST
         * diagnostic read) and {@code setPaused(boolean)} (the faithful unpause driven once at
         * serve-start so {@code advanceInner} runs its full unpaused pass). Either null
         * leaves the matching {@link #isPausedOrNull()} / {@link #unpause()} unavailable, so this is
         * optional like the other engine-level handles.
         */
        public Builder pauseControl(Method isPaused, Method setPaused) {
            this.engineIsPaused = isPaused;
            this.engineSetPaused = setPaused;
            return this;
        }

        public Builder playerShipGiveCommand(Method method) { this.playerShipGiveCommand = method; return this; }

        public Builder playerShipMovementCommands(
                Object accelerate,
                Object decelerate,
                Object strafeLeft,
                Object strafeRight,
                Object turnLeft,
                Object turnRight) {
            this.movementCommandAccelerate = accelerate;
            this.movementCommandDecelerate = decelerate;
            this.movementCommandStrafeLeft = strafeLeft;
            this.movementCommandStrafeRight = strafeRight;
            this.movementCommandTurnLeft = turnLeft;
            this.movementCommandTurnRight = turnRight;
            return this;
        }

        public Builder playerShipWeaponCommands(Object selectGroup, Object fire, Object holdFire) {
            this.weaponCommandSelectGroup = selectGroup;
            this.weaponCommandFire = fire;
            this.weaponCommandHoldFire = holdFire;
            return this;
        }

        /**
         * The {@code ShipCommand.ACCELERATE_BACKWARDS} (reverse-thrust) constant —
         * wire bit 1 of {@link cc.starsector.server.runtime.PilotIntentFrame#heldControls}. Null
         * (the fake binding) leaves reverse thrust unexpressed; the faithful pilot apply then skips
         * that bit (a movement-mechanic gap only when truly unbound).
         */
        public Builder playerShipReverseThrust(Object accelerateBackwards) {
            this.movementCommandAccelerateBackwards = accelerateBackwards;
            return this;
        }

        /**
         * The {@code ShipCommand.VENT_FLUX} constant — wire bit 8. Drives
         * {@link EngineCombatBinding#submitPlayerShipVent} on the rising edge (the engine's own
         * {@code FluxTracker.ventFlux()} self-guards — engine-faithful). Null leaves vent unexpressed.
         */
        public Builder playerShipVentCommand(Object vent) {
            this.weaponCommandVent = vent;
            return this;
        }

        /**
         * The {@code ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK} constant and the engine-internal
         * {@code Ship.getPhaseCloak()} read backing the phase guard (the SAME verb phase-toggles
         * on cloak hulls). The unified press law is type-independent — the old
         * {@code ShieldAPI.getType()} law-discrimination slot is gone; the raise gate's
         * {@code getActiveArc()} idle read binds via {@link #shipShield(Method, Method, Method,
         * Method)} beside {@code isOn}. Any null leaves the shield lane unexpressed —
         * FAIL-CLOSED: no guard/no state read, no issue.
         */
        public Builder playerShipShieldToggle(Object toggleShield, Method getPhaseCloak) {
            this.weaponCommandToggleShield = toggleShield;
            this.shipGetPhaseCloak = getPhaseCloak;
            return this;
        }

        /**
         * The {@code ShipCommand.PULL_BACK_FIGHTERS} constant + the public
         * {@code ShipAPI.isPullBackFighters()} read backing the carrier fighter-mode state mirror
         * (wire bit 12, {@link EngineCombatBinding#applyPullBackBit}). Any null leaves the lane
         * unexpressed - FAIL-CLOSED: no state read, no issue.
         */
        public Builder playerShipPullBackFighters(Object pullBackCommand, Method isPullBackFighters) {
            this.weaponCommandPullBackFighters = pullBackCommand;
            this.shipIsPullBackFighters = isPullBackFighters;
            return this;
        }

        /**
         * The {@code ShipAPI.isPhased()} read completing the phase-cloak
         * state tier ({@link EngineCombatBinding#shipPhaseCloakStatesOrNull()} — the cloak slot
         * rides the {@code getPhaseCloak} handle plus the shared ShipSystemAPI read
         * handles). Null leaves the tier unavailable (returns null), fail-soft like every view
         * tier.
         */
        public Builder shipPhaseCloakRead(Method isPhased) {
            this.shipIsPhased = isPhased;
            return this;
        }

        /**
         * The CR-clock reads completing the CR tier
         * ({@link EngineCombatBinding#shipCrStatesOrNull()}): PUBLIC ShipAPI
         * {@code getCurrentCR()} / {@code getTimeDeployedForCRReduction()} plus the
         * effective-peak chain {@code MutableShipStatsAPI.getPeakCRDuration()} (a
         * {@code StatBonus}) crossed by {@code StatBonus.computeEffective(float)} over
         * {@code ShipHullSpecAPI.getNoCRLossTime()} — the engine's own
         * {@code getPeakTimeRemaining()} expression. Rides the already-bound
         * {@code shipGetMutableStats}/{@code shipGetHullSpec} handles; any null leaves
         * the tier unavailable (returns null), fail-soft like every view tier.
         * {@code getSharedFighterReplacementRate} (PUBLIC ShipAPI) joins
         * the same walk; null there keeps the tier UP with the percent ABSENT.
         */
        public Builder shipCrReads(
                Method getCurrentCR,
                Method getTimeDeployedForCRReduction,
                Method statsGetPeakCRDuration,
                Method statBonusComputeEffective,
                Method hullSpecGetNoCRLossTime,
                Method getSharedFighterReplacementRate) {
            this.shipGetSharedFighterReplacementRate = getSharedFighterReplacementRate;
            this.shipGetCurrentCR = getCurrentCR;
            this.shipGetTimeDeployedForCR = getTimeDeployedForCRReduction;
            this.statsGetPeakCRDuration = statsGetPeakCRDuration;
            this.statBonusComputeEffective = statBonusComputeEffective;
            this.hullSpecGetNoCRLossTime = hullSpecGetNoCRLossTime;
            return this;
        }

        /**
         * aim-carry seam: the owned ship's LIVE {@code getMouseTarget()} vector (the engine's
         * per-frame manual-group slew target) + in-place {@code Vector2f.set(float,float)}. Both null on
         * a fake binding (the aim pin then no-ops, like the facing pin). Supersedes the
         * {@code fireAimRange} bearing-projection knob (removed with the v3/v2 true-point wire).
         */
        public Builder shipMouseTargetWrite(Method getMouseTarget, Method vector2fSet) {
            this.shipGetMouseTarget = getMouseTarget;
            this.vector2fSet = vector2fSet;
            return this;
        }

        public Builder playerShipUseSystem(Method method) { this.playerShipUseSystem = method; return this; }

        /**
         * The {@code ShipSystemAPI.canBeActivated()} guard the proven predict gate
         * checks before each {@code useSystem()} (rising-edge + bounded retry). Null leaves the guard
         * off — the faithful apply then falls back to an unguarded {@code useSystem()} (the engine's
         * own charge tracker still no-ops a press during cooldown, so the end state matches; the guard
         * only avoids wasted invokes mid-cycle, matching the scout reference 1:1 when bound).
         */
        public Builder systemActivationGuard(Method canBeActivated) {
            this.systemCanBeActivated = canBeActivated;
            return this;
        }

        /**
         * Bind the authoritative motion-WRITE boundary: the universal
         * entity setters {@code BaseEntity.setLoc(Vector2f)} / {@code setVel(Vector2f)}
         * plus the lwjgl {@code Vector2f(float,float)} constructor used to build their
         * args. Any null leaves {@link #writeOwnedShipPose} unsupported (returns
         * {@code REJECTED_UNSUPPORTED}), so this is optional like the read handles.
         */
        public Builder shipMotionWrite(Method setLoc, Method setVel, Constructor<?> vector2fCtor) {
            this.shipSetLoc = setLoc;
            this.shipSetVel = setVel;
            this.vector2fCtor = vector2fCtor;
            return this;
        }

        /**
         * Bind the authoritative facing-WRITE seam {@code CombatEntityAPI.setFacing(float)}.
         * The proven predict gate PINS the owned ship's heading to the wire-carried {@code ownShipFacingDeg}
         * each tick rather than replaying coarse TURN bits. Null leaves
         * {@link EngineCombatBinding#writeOwnedShipFacing} unsupported.
         */
        public Builder shipFacingWrite(Method setFacing) {
            this.shipSetFacing = setFacing;
            return this;
        }

        /**
         * Bind the travel-drive read {@code Ship.isTravelDriveOn()}
         * (concrete engine class, not ShipAPI). When bound, {@link #applyPilotIntent} suppresses the
         * FACING pin (only) while the drive flies the hull — the seat fly-in burn / retreat window
         * vanilla blocks player commands for. Null (fakes, resolve failure) keeps the pin
         * unconditional — the earlier behavior.
         */
        public Builder shipTravelDriveRead(Method isTravelDriveOn) {
            this.shipIsTravelDriveOn = isTravelDriveOn;
            return this;
        }

        /**
         * Bind the fire-lock reads (the factory's
         * {@code resolveFireLockHandles}). {@code getSystem} / {@code getTravelDrive} /
         * {@code getPhaseCloak} + {@code shouldBlockCommands} / {@code getSpec} /
         * {@code isFiringAllowed} are vanilla's click gate;
         * {@code getBlockedCommands} + the internal USE_SELECTED_GROUP constant are the command
         * filter's refusal set. When bound,
         * {@link #fireLockProbeForShip} hands the adopt lane a lock probe (D2) and
         * {@link #driveLockWirePredicateOrNull} feeds the travel-drive wire bit (D3). Any null
         * (fakes) ⇒ no probe, no predicate — today's path verbatim.
         */
        public Builder shipFireLockReads(Method getSystem, Method getTravelDrive, Method getPhaseCloak,
                Method shouldBlockCommands, Method getSpec, Method isFiringAllowed,
                Method getBlockedCommands, Object useSelectedGroupBlockedKey) {
            this.fireLockGetSystem = getSystem;
            this.fireLockGetTravelDrive = getTravelDrive;
            this.fireLockGetPhaseCloak = getPhaseCloak;
            this.fireLockShouldBlockCommands = shouldBlockCommands;
            this.fireLockGetSpec = getSpec;
            this.fireLockIsFiringAllowed = isFiringAllowed;
            this.fireLockGetBlockedCommands = getBlockedCommands;
            this.fireLockBlockedKey = useSelectedGroupBlockedKey;
            return this;
        }

        /**
         * weapon-group mirror seams: {@code ShipAPI.getWeaponGroupsCopy()} /
         * {@code getSelectedGroupAPI()} / {@code getWeaponGroupFor(WeaponAPI)},
         * {@code WeaponGroupAPI.isAutofiring()}, and the {@code ShipCommand.TOGGLE_AUTOFIRE}
         * constant — all public-API surface. Any null leaves the matching mirror lane a no-op
         * (group mirror / autofire diff / mount→group serve resolve / consume-by-mount pairing
         * each degrade independently to the pre-B1 behavior), so every fake-binding contract
         * stays byte-identical — the fakes never bind these.
         */
        public Builder weaponGroupMirror(
                Method getWeaponGroupsCopy,
                Method getSelectedGroupAPI,
                Method getWeaponGroupFor,
                Method groupIsAutofiring,
                Object toggleAutofire,
                Method groupToggleOff) {
            this.shipGetWeaponGroupsCopy = getWeaponGroupsCopy;
            this.shipGetSelectedGroupAPI = getSelectedGroupAPI;
            this.shipGetWeaponGroupFor = getWeaponGroupFor;
            this.weaponGroupIsAutofiring = groupIsAutofiring;
            this.weaponCommandToggleAutofire = toggleAutofire;
            this.weaponGroupToggleOff = groupToggleOff;
            return this;
        }

        /**
         * group-identity slot reads: {@code WeaponAPI.getSlot()} +
         * {@code WeaponSlotAPI.getId()} (both public API, bytecode-verified) — the live
         * side of the client-ordinal→server-ordinal map's slot-set matching. Either null keeps
         * the map identity (fakes never bind these; every wire-group seam stays byte-identical).
         */
        public Builder weaponSlotIdentity(Method getSlot, Method getId) {
            this.weaponGetSlot = getSlot;
            this.weaponSlotGetId = getId;
            return this;
        }

        /**
         * active-tube mirror seams: {@code WeaponGroupAPI.getType()} /
         * {@code getActiveWeapon()} / {@code getWeaponsCopy()} (public API), the concrete
         * {@code WeaponGroup.setCurrentActive(int)} pointer write (the engine AI's own conform
         * verb), and the {@code WeaponGroupType.ALTERNATING} constant. Any null leaves the
         * tube-mirror lane a fail-closed no-op — fakes that don't bind these keep their
         * contracts byte-identical.
         */
        public Builder weaponGroupTubeMirror(
                Method getType,
                Method getActiveWeapon,
                Method getWeaponsCopy,
                Method setCurrentActive,
                Object alternatingType) {
            this.groupGetType = getType;
            this.groupGetActiveWeapon = getActiveWeapon;
            this.groupGetWeaponsCopy = getWeaponsCopy;
            this.groupSetCurrentActive = setCurrentActive;
            this.weaponGroupTypeAlternating = alternatingType;
            return this;
        }

        /**
         * (B) Option A AI-suppression handles: {@code Ship.setShipAI(ShipAIPlugin)} +
         * {@code Ship.getShipAI()}. When bound (the real-engine factory), the binding strips an
         * owned ship's engine {@code ShipAI} the first time a client commands it (see
         * {@link EngineCombatBinding#maybeSuppressShipAi}). Both null leaves suppression off — the
         * fake binding never binds these, so its command path is byte-identical.
         */
        public Builder shipAiSuppression(Method setShipAI, Method getShipAI) {
            this.shipSetShipAI = setShipAI;
            this.shipGetShipAI = getShipAI;
            return this;
        }

        /**
         * Retreat-apply handles (verb triple = ONE failure domain — all three or none):
         * {@code ShipAPI.setRetreating(boolean,boolean)}, the vanilla engage static
         * on {@code RetreatManeuver} (the exact
         * keypress call), and the falling-edge {@code Ship.turnOffTravelDrive()} (the engine's Ship; zeroes
         * the drive duration — the {@code isTravelDriveOn()} tracker flips on the NEXT advance, so
         * the same-frame clear is guard-refused and the level re-assert retries it; the
         * old "arrives drive-already-off" gloss was REFUTED). Any verb null leaves
         * {@link EngineCombatBinding#applyPilotIntent}'s retreat lane a no-op (fake bindings).
         * Plus {@code isRetreating} (the hull-truth read
         * of the level re-assert); null falls back to the earlier edge-only setter law.
         */
        public Builder shipRetreatApply(Method setRetreating, Method engageStatic,
                Method turnOffTravelDrive, Method isRetreating) {
            this.shipSetRetreating = setRetreating;
            this.retreatManeuverEngage = engageStatic;
            this.shipTurnOffTravelDrive = turnOffTravelDrive;
            this.shipIsRetreating = isRetreating;
            return this;
        }

        /**
         * R-key target-designation handles: {@code ShipAPI.setShipTarget(ShipAPI)} +
         * {@code getShipTarget()} (read-compare-issue; the SETTER re-derives the friendly cache) +
         * {@code isFighter()} (designation reject). Nulls leave the target lane a no-op.
         */
        public Builder shipTargetApply(Method setShipTarget, Method getShipTarget, Method isFighter) {
            this.shipSetShipTarget = setShipTarget;
            this.shipGetShipTarget = getShipTarget;
            this.shipIsFighter = isFighter;
            return this;
        }

        /**
         * The engine's own {@code CombatEngineAPI.isInFastTimeAdvance()}
         * read — TRUE only during the fast-time extra passes
         * — the native single-issuer split the DD1 reinject shim gates on: pass 1
         * stays {@code applyPilotIntent}'s, passes 2+ are the shim's. Null (fakes, resolution miss)
         * leaves {@link EngineCombatBinding#installFastTimeReinjectShim(Object)} refusing installs
         * (fail-closed: no gate read, no shim — a shim that cannot tell passes apart would
         * double-issue pass 1).
         */
        public Builder engineFastTimeRead(Method isInFastTimeAdvance) {
            this.engineIsInFastTimeAdvance = isInFastTimeAdvance;
            return this;
        }

        /**
         * Configure the server-owned ship set (repository-order ids) the command
         * sink and pose sink may target. Defaults to the single player ship
         * {@code {1}} when unset, preserving the proven single-player path; pass
         * {@code 1, 2} to authorize a 2nd owned ship (Spike 4 authority half).
         */
        public Builder ownedShipIds(long... ids) {
            Set<Long> set = new LinkedHashSet<>();
            for (long id : ids) {
                set.add(id);
            }
            this.ownedShipIds = set;
            return this;
        }

        /**
         * Wire the roster-maintained live wireId→engine-ship authority. When
         * set AND {@code live()}, every seat-addressed resolve (intent/command/useSystem/idle-AI/
         * name/pose/facing) consults the map authoritatively — a miss drops, never falls through
         * to the positional read. Unset (every fake binding, the {@code ownedShipIds} default-{1}
         * pattern) keeps the legacy repository-position resolution byte-identical.
         */
        public Builder liveShipResolver(LiveWireShipResolver resolver) {
            this.liveShipResolver = resolver;
            return this;
        }

        public Builder timeMult(float v) { this.timeMult = v; return this; }

        public Builder combatSpeedMult(float v) { this.combatSpeedMult = v; return this; }

        public Builder fogBound(boolean v) { this.fogBound = v; return this; }

        public EngineCombatBinding build() {
            return new EngineCombatBinding(this);
        }
    }
}
