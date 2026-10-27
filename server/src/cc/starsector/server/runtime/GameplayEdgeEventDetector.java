package cc.starsector.server.runtime;

import cc.starsector.server.adapter.ShipFluxView;
import cc.starsector.server.adapter.ShipSystemView;
import cc.starsector.server.adapter.WeaponMountView;
import cc.starsector.server.protocol.AuthoritativeEntitySnapshot;
import cc.starsector.server.protocol.GameplayEdgeEvent;
import cc.starsector.server.protocol.GameplayEdgeEventKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Server-side producer of {@link GameplayEdgeEvent}s:
 * post-step edge detection over the per-tick engine-backed read
 * models — {@link ShipFluxView} ({@code venting} edges), {@link WeaponMountView}
 * (discharge via {@code cooldownRemaining} rise / ammo decrement), and
 * {@link ShipSystemView} ({@code isActive} edges). The
 * {@link EntityDespawnEventDetector} pattern: stateful, single-threaded, {@link #observe} once
 * per completed tick in order.
 *
 * <p><b>Wiring status.</b> The GPEE <em>wire lane</em> stays un-served by design (the pilot serve
 * paths still exchange the 1-arg {@code exchangeSnapshot} / deposit {@code List.of()} — no live wire
 * publisher). {@code PersistentPilotIntentCombatServer} DOES run one fresh
 * per-round detector over the weapon-mount tier only (flux/system tiers passed null) as the
 * declared-discharge fire lane's own-ship discharge consumer — the decided "reuse the SAME detection,
 * do NOT build a second detector". {@code GameplayEdgeEventSmoke} remains the game-free exerciser.
 *
 * <p>Reads are the engine's OWN accepted post-step state (same discipline as the
 * up-wire heldControls bits 7/8): a refused command never produces an edge, so
 * emission is a pure function of (initial state, inputs, seeds) — the property the
 * exactness gates lean on. Within a tick the emitted list is sorted by
 * the canonical dedup/order key {@code (entityId, kind, phase, mountIndex)} so the
 * event log is independent of the engine's view-list iteration order.
 *
 * <p>Edge semantics: an edge is stamped {@code T} iff post-step state at
 * {@code T} shows it and state at {@code T-1} did not. The first observation of a
 * key (ship or mount) establishes its baseline and emits nothing — there is no
 * {@code T-1} to edge against. A key absent from the current tick (despawned ship,
 * tier entry gone) simply stops updating; despawn itself is the
 * {@link EntityDespawnEventDetector}'s event, and window closure on despawn is the
 * client replay's concern. A {@code null} tier list (the binding's
 * fail-soft "read unavailable this tick") skips that tier's detection and keeps its
 * baselines — an availability flap must never fabricate edges.
 *
 * <p>Discharge detection: {@code cooldownRemaining} rising by more than
 * {@link #COOLDOWN_RISE_EPSILON} (cooldown only decays with time; only a discharge
 * re-arms it), OR an ammo decrement while {@code usesAmmo} — either signal alone
 * emits exactly one event for the (ship, mount) key that tick. For ammo-bearing
 * weapons the engine lands the two signals on ADJACENT ticks
 * (measured: ammo decrements on the fire tick, cooldown rises the
 * next), so one physical shot used to emit TWO events one tick apart — live as the
 * doubled Reapers/TPCs a run showed. The detector now collapses that:
 * an edge at exactly {@code lastEmitTick+1} for the same mount whose trigger signals
 * are all ones the last emission did NOT use is the same shot's trailing half and is
 * swallowed; any repeated signal (ammo again, cooldown again) is a genuine new
 * discharge and emits. The surviving event is the FIRST signal's — the fire tick's
 * stamp and aim. Beam weapons hold {@code cooldownRemaining} at 0 while firing and
 * so correctly emit nothing (out of v1 scope); burst-internal refire below
 * the epsilon is likewise out of v1 scope (the demo loadout is the single-shot
 * ballistic Hellbore).
 * {@code aimFacingDeg} carries the mount's {@link WeaponMountView#currAngle()} at
 * the detection read ({@code NaN} when that optional read is unbound — fail-soft,
 * never a faked value) — except a MISSILE fresh spawn's own birth
 * facing ({@link WeaponMountView#freshSpawnFacingDeg()}, the launch angle incl. the
 * barrel offset) through {@link LaunchFacingLaw}; {@code flightBearingDeg} carries — GPEE v3
 * — the just-spawned projectile's OWN velocity line
 * ({@link WeaponMountView#freshSpawnBearingDeg()}: the engine's true per-shot
 * bearing including spread jitter), falling back to the v2 composed bearing
 * ({@link #composeFlightBearingDeg}) when no fresh spawn
 * matched the mount.
 */
public final class GameplayEdgeEventDetector {

    /**
     * Minimum {@code cooldownRemaining} rise (seconds) read as a discharge. Genuine
     * discharges re-arm by the weapon's full cooldown (≫ one 1/60 s step decay);
     * this only needs to swallow float jitter.
     */
    public static final float COOLDOWN_RISE_EPSILON = 1e-3f;

    /**
     * The teleport-class primary-system ids (the exactly-two
     * engine teleport factories' spec ids — displacer family 300u + phase teleporter 1500u).
     * SYSTEM_JUMP emission is class-gated HERE, server-side, so a non-teleport system's
     * IN→{ACTIVE,OUT} decay (every fixed-duration system does it) can never mint a marker;
     * {@code energy_conversion} shares the displacer icon but is NOT teleport-class.
     */
    public static final Set<String> TELEPORT_CLASS_SYSTEM_IDS = Set.of(
            "displacer", "displacer_degraded", "displacer_threat", "skimmer_drone",
            "phaseteleporter");

    /** The Ziggurat attractor system id ({@code ship_systems.csv}; the client
     *  spawn gate keys the same press by aiType {@code MOTE_ATTRACTOR} — two namespaces). */
    public static final String MOTE_CONTROL_SYSTEM_ID = "mote_control";

    /** The within-tick dedup/order key — shared with {@link OnFireTruthMerger},
     *  which re-sorts its merged stream by the same canonical key. */
    static final Comparator<GameplayEdgeEvent> CANONICAL_ORDER =
            Comparator.comparingLong(GameplayEdgeEvent::entityId)
                    .thenComparing(GameplayEdgeEvent::kind)
                    .thenComparing(GameplayEdgeEvent::phase)
                    .thenComparingInt(GameplayEdgeEvent::mountIndex);

    private record MountKey(long shipId, int mountIndex) {}

    private record MountBaseline(float cooldownRemaining, boolean usesAmmo, int ammo) {}

    /** Which signals the last emitted discharge for a mount used, and at which tick —
     *  the state the adjacent-tick collapse tests against. */
    private record MountEmission(long simTick, boolean byCooldown, boolean byAmmo) {}

    // Bounded memory: the unbounded `events` history (+ its
    // uncalled events() accessor) is gone — nothing read it and it grew every tick of a serve.
    private final Map<Long, Boolean> ventingByShipId = new HashMap<>();
    private final Map<Long, Boolean> systemActiveByShipId = new HashMap<>();
    // per-ship prev system STATE NAME (IDLE/IN/ACTIVE/OUT/COOLDOWN) — a NEW
    // baseline map beside the boolean one, so the SYSTEM_WINDOW edges stay byte-identical
    // by construction. The jump edge is prev=IN → now∈{ACTIVE,OUT}: ship_systems.csv
    // active duration is 0/blank for the whole teleport class, so post-step sampling may
    // never SEE the zero-duration ACTIVE — the disjunction is load-bearing.
    private final Map<Long, String> systemStateByShipId = new HashMap<>();
    private final Map<MountKey, MountBaseline> mountBaselines = new HashMap<>();
    private final Map<MountKey, MountEmission> lastDischargeEmission = new HashMap<>();
    /**
     * Last tick this ammo mount's ammo ROSE FROM EMPTY (the vanilla
     * {@code MissileAutoloader} hullmod's {@code setAmmo(base)}, which fires only from
     * {@code getAmmo() <= 0}; a refill from a non-zero read never arms). One
     * entry per live mount key, same bound as {@link #mountBaselines}, evicted with it by
     * {@link #resetShipBaselines}.
     * WHY: the autoloader pins a reloaded weapon's cooldown at FULL for 5 s via
     * {@code setRemainingCooldownTo(getCooldown())} every frame, starting the frame AFTER
     * its {@code setAmmo}; the first pin reads as a cooldown RISE with no ammo spend, and
     * this detector emitted it as a WEAPON_DISCHARGE (seen live: six
     * {@code det_fallback} phantoms on a player's Hammers, each exactly one tick after a
     * 0→2 ammo jump, no projectile — the client then presented a missile the server never
     * flew). A cooldown rise on an ammo mount within one tick of an ammo RISE is that
     * reload re-arm, never a shot ({@link #reloadRearmSuppressedCount}).
     */
    private final Map<MountKey, Long> lastAmmoRiseTick = new HashMap<>();
    /**
     * Mounts that have EVER produced a fresh-spawn bearing this boot.
     * Fresh reads only happen for projectile mounts (a matched just-spawned projectile), so a
     * later COMPOSED bearing from one of these is a regression signal — the v2 mount-ray
     * composition serving a mount whose true per-shot bearing was previously readable. Beam
     * mounts never enter this set, so their legitimate composition stays silent.
     */
    private final Set<MountKey> freshBearingMounts = new HashSet<>();
    private boolean sawFirstTick = false;
    private long lastObservedTick = -1L;

    // Bearing-source visibility: per emitted WEAPON_DISCHARGE, which lane
    // produced flightBearingDeg — v3 fresh-spawn read, v2 composed fallback, or unbound NaN.
    // The v3 read fails soft to v2 with no external sign; that silent degrade left the
    // step-1 cone bar undiagnosable for a whole watch. Surfaced per round in the serve-loop
    // summary lines (gpee_bearing_fresh/composed/unbound).
    private long bearingFresh;
    private long bearingComposed;
    private long bearingUnbound;

    /** MISSILE discharges whose aim carried the fresh
     *  spawn's birth facing / fell back to the mount (round line gpee_aim_facing/fallback) —
     *  counted only when the WIRE carried the detector's own event
     *  ({@link #countServedAim}); a covered twin the merger suppressed never counts. */
    private long aimFacing;
    private long aimFallback;
    /** this observe's MISSILE discharges → fallback?, consumed by
     *  {@link #countServedAim} (cleared each observe; empty on most ticks). */
    private final Map<MountKey, Boolean> missileAimByKey = new HashMap<>();
    /** Weapon id ⇒ fires a MISSILE spec — so a missile discharge with NO
     *  fresh spawn matched (scan miss / elapsed window / list unbound) is still a COUNTED fallback. */
    private final Predicate<String> missileWeapon;
    /** Weapon id ⇒ its REAL shots reach the wire as on-fire
     *  BIRTHS (independent of this detector, {@code OnFireTruthMerger}), so a covered mount's
     *  cooldown-only rise in a no-spawn state is safe to swallow ({@link #burstEndSuppressedCount}).
     *  An uncovered weapon's only fire signal IS this poll — the rule never touches it. */
    private final Predicate<String> birthCovered;
    /** Network ids of ships overloaded OR venting in THIS observe's flux tier
     *  — the STATE, not the edge (the burst-end rise can land one tick after the flag turns
     *  true). Rebuilt every observe; empty when the flux tier is null. */
    private final Set<Long> fluxLockedShipIds = new HashSet<>();

    /** Live detector: the missile KIND comes from the on-fire sweep's spec walk; birth coverage
     *  from {@link #liveBirthCovered()}. */
    public GameplayEdgeEventDetector() {
        this(FireBirthSink::isMissileSpec, liveBirthCovered());
    }

    /** Test seam (GameplayEdgeEventSmoke det-facing-4): an injected missile-weapon predicate;
     *  the burst-end swallow is OFF (no weapon is birth-covered). */
    public GameplayEdgeEventDetector(Predicate<String> missileWeapon) {
        this(missileWeapon, w -> false);
    }

    /** Test seam (GameplayEdgeEventSmoke PD rows) — both
     *  predicates injected; {@code birthCovered} arms the burst-end swallow for its weapon ids. */
    public GameplayEdgeEventDetector(Predicate<String> missileWeapon, Predicate<String> birthCovered) {
        this.missileWeapon = missileWeapon;
        this.birthCovered = birthCovered;
    }

    /**
     * The live birth-coverage predicate. A weapon id counts
     * as birth-covered only while its real shots actually ride births on the wire: the on-fire
     * sweep latched and covered it ({@link FireBirthSink#coveredWeaponIds()}, empty until
     * {@link FireBirthSink#enabled()}; beams never enter it — the sweep's {@code no_proj_spec}
     * branch) AND the truth merger is on. WHY the merger half: {@code PilotServeSession} builds
     * {@code new OnFireTruthMerger(onFireTruth)}; with that knob off the merge is identity and the
     * detector is a covered weapon's ONLY wire source again, so a swallowed rise would have no
     * birth to stand in for it. The knob is re-resolved here exactly as {@code PilotServeResolver}
     * resolves it (the config file is immutable for the JVM's life; a malformed value already
     * refused the boot there) — anything but a true-spelling reads OFF, the behavior from before the burst-end swallow.
     */
    private static Predicate<String> liveBirthCovered() {
        String raw = ServerConfig.resolve(
                "cc.server.onFireTruth", "CC_SERVER_ONFIRE_TRUTH", "server.onFireTruth", "true");
        boolean merging = switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "", "true", "1", "yes", "on" -> true;
            default -> false;
        };
        if (!merging) {
            return w -> false;
        }
        return w -> w != null && FireBirthSink.coveredWeaponIds().contains(w);
    }

    // Marker meters: emitted SYSTEM_JUMP markers + pose-unbound SKIPS (a jump
    // edge whose ship had no post-step pose row — the marker is dropped, never NaN'd).
    private long jumpEmitted;
    private long jumpPoseUnbound;

    /** SYSTEM_WINDOW ON edges emitted in the mote-target form. */
    private long moteTargetEmitted;

    // mount-tier same-tick double-folds swallowed — a SECOND view for
    // one (ship, mount) key within one observe (two live position rows sharing one wire id,
    // the 1759 swap-tick class) updates the baseline but must never diff against the FIRST
    // view's state as if a tick had passed.
    private long sameTickDoubleFold;

    /** Cooldown rises swallowed as reload re-arms (see {@link #lastAmmoRiseTick}). */
    private long reloadRearmSuppressed;

    /** Covered cooldown-only rises swallowed as burst ends (see
     *  {@link #burstEndSuppressedCount}). */
    private long burstEndSuppressed;

    /**
     * Fold one completed tick's post-step reads into the detector, returning the
     * edge events stamped at this tick (canonically ordered; empty when no edges).
     * Ticks must be supplied in strictly increasing order — observing the same tick
     * twice would double-detect its edges.
     *
     * <p>Identity-keyed overload (position id == network id): the position-mode /
     * game-free-smoke contract. The live stable-roster serve paths MUST use the
     * map-taking overload instead — see below.
     */
    public List<GameplayEdgeEvent> observe(
            long simTick,
            List<? extends ShipFluxView> fluxStates,
            List<? extends WeaponMountView> weaponMounts,
            List<? extends ShipSystemView> shipSystems) {
        return observe(simTick, fluxStates, weaponMounts, shipSystems, null);
    }

    /**
     * {@link #observe(long, List, List, List)} with the capture's position→network id map.
     * The engine-backed views key ships POSITIONALLY (the
     * binding walks the repository roster {@code shipId = 1L; … shipId++} per slot) while the
     * wire keys ships by stable network entityId — equal only by deploy-order coincidence;
     * the first roster mutation (death/hulk/fresh-hull append) drifts them, which emitted
     * post-respawn foe-fire under ids the client never saw (events arrived
     * entity=4/5 while the pose lane re-used wire id 2) AND made the declared-lane consume
     * miss the seat-keyed decl table (r1's 15/23 expired decls). Every key here — vent /
     * system / mount baselines and emitted events — is therefore translated to the network
     * id; a view whose position id is unmapped is SKIPPED whole (mirrors the AWSS fold
     * semantics: dropped/dead hulls never edge, which also stops hulk flux/system aliasing).
     * {@code null} map = identity (position mode; game-free smokes).
     */
    public List<GameplayEdgeEvent> observe(
            long simTick,
            List<? extends ShipFluxView> fluxStates,
            List<? extends WeaponMountView> weaponMounts,
            List<? extends ShipSystemView> shipSystems,
            Map<Long, Long> entityIdByPositionShipId) {
        return observe(simTick, fluxStates, weaponMounts, shipSystems,
                entityIdByPositionShipId, null);
    }

    /**
     * {@link #observe(long, List, List, List, Map)} with the tick's post-step entity list:
     * the {@code SYSTEM_JUMP} marker's destination source. The list is the
     * same-tick AWSS {@code entities()} — ascending by network entityId, so the (rare) jump
     * edge binds its pose by binary search at zero per-tick cost. {@code null} or a missing
     * row ⇒ the marker is SKIPPED and counted ({@link #jumpPoseUnboundCount()}) — a marker
     * without a destination is useless, and NaN dest is rejected by the event family.
     */
    public List<GameplayEdgeEvent> observe(
            long simTick,
            List<? extends ShipFluxView> fluxStates,
            List<? extends WeaponMountView> weaponMounts,
            List<? extends ShipSystemView> shipSystems,
            Map<Long, Long> entityIdByPositionShipId,
            List<AuthoritativeEntitySnapshot> postStepEntities) {
        if (sawFirstTick && simTick <= lastObservedTick) {
            throw new IllegalArgumentException(
                    "ticks must be observed in strictly increasing order; got " + simTick
                            + " after " + lastObservedTick);
        }
        missileAimByKey.clear();
        fluxLockedShipIds.clear();
        List<GameplayEdgeEvent> emitted = new ArrayList<>();
        if (fluxStates != null) {
            for (ShipFluxView flux : fluxStates) {
                Long shipId = networkIdOrNull(entityIdByPositionShipId, flux.shipId());
                if (shipId == null) {
                    continue;
                }
                // The burst-end swallow's no-spawn state (iii).
                if (flux.overloaded() || flux.venting()) {
                    fluxLockedShipIds.add(shipId);
                }
                boolean venting = flux.venting();
                Boolean previous = ventingByShipId.put(shipId, venting);
                if (previous != null && previous.booleanValue() != venting) {
                    emitted.add(GameplayEdgeEvent.ventWindow(simTick, shipId, venting));
                }
            }
        }
        if (shipSystems != null) {
            for (ShipSystemView system : shipSystems) {
                Long shipId = networkIdOrNull(entityIdByPositionShipId, system.shipId());
                if (shipId == null) {
                    continue;
                }
                boolean active = system.active();
                Boolean previous = systemActiveByShipId.put(shipId, active);
                if (previous != null && previous.booleanValue() != active) {
                    if (active && MOTE_CONTROL_SYSTEM_ID.equals(system.systemId())
                            && Float.isFinite(system.moteTargetX())
                            && Float.isFinite(system.moteTargetY())) {
                        // The Ziggurat's attractor press carries its target
                        // (the bridge read SharedMoteAIData this frame) so replicas can paint
                        // the targeting arc; an unresolvable lock degrades to TARGET_NONE +
                        // the world point (the client arcs to the point). No target read ⇒
                        // the plain edge below (fail-soft: window folds still happen).
                        long lockPos = system.moteTargetLockShipId();
                        Long lockNet = lockPos > 0L
                                ? networkIdOrNull(entityIdByPositionShipId, lockPos) : null;
                        moteTargetEmitted++;
                        emitted.add(GameplayEdgeEvent.systemWindowMoteTarget(
                                simTick, shipId, system.moteTargetX(), system.moteTargetY(),
                                lockNet != null ? lockNet.longValue() : GameplayEdgeEvent.TARGET_NONE));
                    } else {
                        emitted.add(GameplayEdgeEvent.systemWindow(simTick, shipId, active));
                    }
                }
                String state = system.state();
                String prevState = systemStateByShipId.put(shipId, state);
                if (prevState != null && "IN".equals(prevState)
                        && ("ACTIVE".equals(state) || "OUT".equals(state))
                        && TELEPORT_CLASS_SYSTEM_IDS.contains(system.systemId())) {
                    AuthoritativeEntitySnapshot pose = entityOrNull(postStepEntities, shipId);
                    if (pose == null
                            || !Float.isFinite(pose.locX()) || !Float.isFinite(pose.locY())) {
                        jumpPoseUnbound++;
                    } else {
                        jumpEmitted++;
                        emitted.add(GameplayEdgeEvent.systemJump(
                                simTick, shipId, pose.locX(), pose.locY()));
                    }
                }
            }
        }
        if (weaponMounts != null) {
            Set<MountKey> foldedMounts = new HashSet<>();
            for (WeaponMountView mount : weaponMounts) {
                Long shipId = networkIdOrNull(entityIdByPositionShipId, mount.shipId());
                if (shipId == null) {
                    continue;
                }
                MountKey key = new MountKey(shipId, mount.mountIndex());
                MountBaseline current = new MountBaseline(
                        mount.cooldownRemaining(), mount.usesAmmo(), mount.ammo());
                MountBaseline previous = mountBaselines.put(key, current);
                if (!foldedMounts.add(key)) {
                    // Guard: a second view for this key within ONE observe. The
                    // baseline update above stands (last view wins — the pre-guard map
                    // behavior), but a diff of two SAME-tick views is not an edge; detecting
                    // on it fabricates a discharge from roster aliasing, never from fire.
                    sameTickDoubleFold++;
                    continue;
                }
                if (previous == null) {
                    continue;
                }
                boolean cooldownRearmed =
                        current.cooldownRemaining() > previous.cooldownRemaining() + COOLDOWN_RISE_EPSILON;
                boolean ammoSpent = current.usesAmmo() && previous.usesAmmo()
                        && current.ammo() < previous.ammo();
                if (current.usesAmmo() && previous.usesAmmo() && current.ammo() > previous.ammo()
                        && previous.ammo() <= 0) {
                    // Only a rise FROM EMPTY arms the swallow. The
                    // vanilla hullmod reloads only from getAmmo() <= 0;
                    // a rise from a non-zero read is either a refill with no pin (Autoforge, regen)
                    // or the MASKED same-tick reload (the emptying shot spends, the hullmod refills
                    // in the same frame, the wire reads 1->2) whose ONLY poll signal is the next
                    // tick's full-cooldown rise - arming on it swallowed that REAL shot (row dRl5).
                    lastAmmoRiseTick.put(key, simTick);
                }
                if (cooldownRearmed && !ammoSpent && current.usesAmmo()
                        && current.cooldownRemaining() >= mount.cooldown() - COOLDOWN_RISE_EPSILON) {
                    // The reload re-arm signature — ammo rose this tick or the
                    // last, and the cooldown now rises to EXACTLY full with no spend
                    // (setRemainingCooldownTo(getCooldown()) lands after the weapon's own
                    // advance; a natural shot reads 0 on its fire tick and full−2·dt the
                    // next).
                    // Narrow by design: a cooldown-first REAL shot has no ammo rise beside
                    // it (GameplayEdgeEventSmoke row d6 stays intact), a shot the tick after
                    // a refill reads below full, and a shot that spends ammo rides ammoSpent.
                    Long rose = lastAmmoRiseTick.get(key);
                    if (rose != null && simTick - rose <= 1L) {
                        reloadRearmSuppressed++;
                        continue;
                    }
                }
                // A burst that ENDS without spawning still
                // sets the chargedown, so the rise reads like a shot (MG measure, 14 of 14
                // phantoms): (i) an empty burst slot (deductOneAmmo false below 1 round), (ii) the
                // disable edge, (iii) overload/vent ending the burst. Swallowed only on a
                // birth-covered mount (its real shots ride births) with no matched spawn (the
                // engine's own witness of a shot); no emission record, no aim record, no event.
                if (cooldownRearmed && !ammoSpent
                        && Float.isNaN(mount.freshSpawnMuzzleX())
                        && ((current.usesAmmo() && previous.usesAmmo()
                                && previous.ammo() < 1 && current.ammo() < 1)
                                || mount.disabled()
                                || fluxLockedShipIds.contains(shipId))
                        && birthCovered.test(mount.weaponId())) {
                    burstEndSuppressed++;
                    continue;
                }
                if (cooldownRearmed || ammoSpent) {
                    MountEmission last = lastDischargeEmission.get(key);
                    boolean trailingHalfOfSameShot = last != null && simTick == last.simTick() + 1
                            && (!cooldownRearmed || !last.byCooldown())
                            && (!ammoSpent || !last.byAmmo());
                    if (trailingHalfOfSameShot) {
                        // Adjacent-tick collapse:
                        // the same physical shot's OTHER signal landing one tick
                        // later. Swallow it, and clear the record so the NEXT real shot can
                        // never read as a trailing half of this one.
                        lastDischargeEmission.remove(key);
                    } else {
                        lastDischargeEmission.put(
                                key, new MountEmission(simTick, cooldownRearmed, ammoSpent));
                        // GPEE v3: prefer the just-spawned
                        // projectile's OWN velocity line — the engine's true per-shot bearing
                        // including spread jitter — over the v2 mount-ray composition, which
                        // stays as the fail-soft fallback (beams, unbound reads, no fresh
                        // spawn matched). Same wire field, semantic upgrade only.
                        float flightBearingDeg = mount.freshSpawnBearingDeg();
                        if (Float.isNaN(flightBearingDeg)) {
                            flightBearingDeg = composeFlightBearingDeg(mount);
                            if (Float.isNaN(flightBearingDeg)) {
                                bearingUnbound++;
                            } else {
                                bearingComposed++;
                                // (83) SV-5: composed bearing on a mount that previously read
                                // fresh = the projectile-bearing tier regressed mid-boot. Feeds
                                // the root-cause lead (a) with a graded-visible signal.
                                // NOT a regression: a zero-velocity missile (empty launch-speed
                                // column) matched its spawn with a NaN bearing.
                                if (freshBearingMounts.contains(key) && !mount.freshSpawnMissile()) {
                                    FallbackAnnounce.announceOnce("gpee-bearing-composed-regression",
                                            "projectile mount fell from fresh-spawn to composed"
                                            + " bearing (ship=" + key.shipId()
                                            + " mount=" + key.mountIndex() + ")");
                                }
                            }
                        } else {
                            bearingFresh++;
                            freshBearingMounts.add(key);
                        }
                        // GPEE v6: the poll path reads no spawn SPEED, so
                        // |v0| is ALWAYS the composed magnitude |shooterVel + speed·û(ray)| (for a
                        // missile the spec MAX, not the launch speed) —
                        // server-side truth for the v0 law even when the bearing came from the
                        // fresh-spawn read (which carries no speed). NaN = compose unbound.
                        // v8 muzzle pair: the fresh-spawn entity's
                        // own location, riding the same mount view — NaN/NaN when no fresh
                        // spawn matched (honest absence, never a mount-ray re-derivation).
                        // A MISSILE fresh spawn's birth
                        // facing is the aim (the on-fire emitter's LaunchFacingLaw); every other
                        // discharge keeps currAngle; a missile with an unreadable facing COUNTS —
                        // also a missile-spec mount with NO fresh spawn.
                        boolean missile = mount.freshSpawnMissile()
                                || missileWeapon.test(mount.weaponId());
                        float freshFacing = mount.freshSpawnFacingDeg();
                        if (missile) {
                            missileAimByKey.put(key, LaunchFacingLaw.fallback(true, freshFacing));
                        }
                        emitted.add(GameplayEdgeEvent.weaponDischarge(
                                simTick, shipId, mount.mountIndex(),
                                LaunchFacingLaw.pick(missile, freshFacing, mount.currAngle()),
                                flightBearingDeg, composeFlightSpeed(mount),
                                mount.freshSpawnMuzzleX(), mount.freshSpawnMuzzleY()));
                    }
                }
            }
        }
        emitted.sort(CANONICAL_ORDER);
        sawFirstTick = true;
        lastObservedTick = simTick;
        return List.copyOf(emitted);
    }

    /** A view's network id under {@code map} ({@code null} map = identity; {@code null}
     *  result = unmapped ⇒ the caller SKIPS the view, mirroring the AWSS fold semantics). */
    private static Long networkIdOrNull(Map<Long, Long> map, long positionShipId) {
        return map == null ? Long.valueOf(positionShipId) : map.get(positionShipId);
    }

    /**
     * Probe seam (read-only; public since the parole move — the
     * sole consumer {@code tools.RealEngineDeployFirerProbeMain} left this package): the
     * stored discharge baseline for ({@code networkShipId}, {@code mountIndex}) formatted as
     * {@code cd=<f> usesAmmo=<b> ammo=<n>}, or {@code "none"} — lets the offline
     * detector-fold repro ({@code RealEngineDeployFirerProbeMain --swap --fold}) print
     * prev-baseline/current pairs without disturbing detection state.
     */
    public String debugMountBaseline(long shipId, int mountIndex) {
        MountBaseline b = mountBaselines.get(new MountKey(shipId, mountIndex));
        return b == null ? "none"
                : "cd=" + b.cooldownRemaining() + " usesAmmo=" + b.usesAmmo()
                        + " ammo=" + b.ammo();
    }

    /**
     * Drop EVERY baseline and collapse record held for one network
     * (wire) id, returning the number of entries dropped. The T4 remount fly-in repoints a
     * reserved wire id from the seat's DEAD hull to its fresh replacement
     * ({@code PersistentPilotIntentCombatServer} calls this right after
     * {@code internReservedMount}, pre-advance); any baseline surviving that repoint belongs
     * to the corpse, and a fresh hull whose ammo reads BELOW the stale ammo baseline
     * fabricates a same-tick phantom WEAPON_DISCHARGE volley (the offline repro,
     * via the ammoSpent lane, the cooldown lane sterile). After the reset the fresh hull's
     * first observation re-establishes baselines and emits nothing (the first-sight law).
     * {@code 0} = nothing stale (a T1 first mount) — the healthy log-row read.
     */
    public int resetShipBaselines(long shipId) {
        int dropped = 0;
        if (ventingByShipId.remove(shipId) != null) {
            dropped++;
        }
        if (systemActiveByShipId.remove(shipId) != null) {
            dropped++;
        }
        if (systemStateByShipId.remove(shipId) != null) {
            dropped++;
        }
        for (Iterator<MountKey> it = mountBaselines.keySet().iterator(); it.hasNext();) {
            if (it.next().shipId() == shipId) {
                it.remove();
                dropped++;
            }
        }
        for (Iterator<MountKey> it = lastDischargeEmission.keySet().iterator(); it.hasNext();) {
            if (it.next().shipId() == shipId) {
                it.remove();
                dropped++;
            }
        }
        // The corpse's reload-rise record must not swallow the
        // fresh hull's first full-cooldown rise under the same wire id (the repoint is
        // pre-advance, so that rise can land within one tick of the corpse's reload).
        for (Iterator<MountKey> it = lastAmmoRiseTick.keySet().iterator(); it.hasNext();) {
            if (it.next().shipId() == shipId) {
                it.remove();
                dropped++;
            }
        }
        return dropped;
    }

    /** Mount-tier same-tick double-folds swallowed by the guard — nonzero means
     *  two live position rows shared one wire id within a single observed tick. */
    public long sameTickDoubleFoldCount() {
        return sameTickDoubleFold;
    }

    /** Cooldown rises on ammo mounts swallowed as reload re-arms (ammo rose at
     *  T or T-1, no spend) — the Missile Autoloader hold's first pin. Nonzero is healthy on
     *  an autoloader hull; a shot never rides here (a fired weapon spends ammo). */
    public long reloadRearmSuppressedCount() {
        return reloadRearmSuppressed;
    }

    /** cooldown-only rises on BIRTH-COVERED mounts swallowed
     *  in a no-spawn state — an empty burst slot (ammo below 1 before and after), a disable edge,
     *  an overload / vent (round line gpee_burst_end_suppressed). Never a shot: a matched fresh
     *  spawn or an ammo spend exempts the rise, and a covered weapon's real shots ride births.
     *  Nonzero is healthy on burst-missile fighters (swarmer Thunders read ~1 per 800 ticks). */
    public long burstEndSuppressedCount() {
        return burstEndSuppressed;
    }

    /** Emitted discharges whose bearing came from the GPEE v3 fresh-spawn read (true jittered line). */
    public long bearingFreshCount() {
        return bearingFresh;
    }

    /** Emitted discharges that fell back to the v2 composed bearing (fresh read missed — no jitter). */
    public long bearingComposedCount() {
        return bearingComposed;
    }

    /** Emitted discharges whose bearing stayed NaN (both reads unbound — beams/fail-soft). */
    public long bearingUnboundCount() {
        return bearingUnbound;
    }

    /**
     * Count the missile aim class of every discharge the LAST
     * {@code observe} emitted that the wire carries as the detector's OWN event — present in
     * {@code served} (the merge output) and {@link OnFireTruthMerger#detectorServed} for its
     * (entity, mount); {@code merger == null} = the identity path (every event is the
     * detector's). WHY: counting at emit also counted covered on-fire twins the merger then
     * SUPPRESSED, so a detector scan-miss on a shot the on-fire lane served right would read
     * as a wire fallback. Consume-once: the per-observe record is cleared here.
     */
    public void countServedAim(List<GameplayEdgeEvent> served, OnFireTruthMerger merger) {
        if (missileAimByKey.isEmpty()) {
            return;
        }
        for (GameplayEdgeEvent event : served) {
            if (event.kind() != GameplayEdgeEventKind.WEAPON_DISCHARGE) {
                continue;
            }
            Boolean fallback = missileAimByKey.remove(new MountKey(event.entityId(), event.mountIndex()));
            if (fallback == null
                    || (merger != null && !merger.detectorServed(event.entityId(), event.mountIndex()))) {
                continue;
            }
            if (fallback.booleanValue()) {
                aimFallback++;
            } else {
                aimFacing++;
            }
        }
        missileAimByKey.clear();
    }

    /** Wire-served MISSILE discharges whose aim carried the fresh spawn's birth facing. */
    public long aimFacingCount() {
        return aimFacing;
    }

    /** Wire-served MISSILE discharges whose facing was unreadable or whose missile-spec mount
     *  matched NO fresh spawn — aim fell back to the mount angle (must stay 0). */
    public long aimFallbackCount() {
        return aimFallback;
    }

    /** Emitted {@code SYSTEM_JUMP} markers. */
    public long jumpEmittedCount() {
        return jumpEmitted;
    }

    /** Jump edges whose marker was SKIPPED for want of a post-step pose row (never NaN'd). */
    public long jumpPoseUnboundCount() {
        return jumpPoseUnbound;
    }

    /** SYSTEM_WINDOW ON edges that carried the mote-attractor target. */
    public long moteTargetEmittedCount() {
        return moteTargetEmitted;
    }

    /** Binary search over the AWSS post-step entity list (ascending by network entityId). */
    private static AuthoritativeEntitySnapshot entityOrNull(
            List<AuthoritativeEntitySnapshot> entities, long entityId) {
        if (entities == null) {
            return null;
        }
        int lo = 0;
        int hi = entities.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            AuthoritativeEntitySnapshot candidate = entities.get(mid);
            if (candidate.entityId() < entityId) {
                lo = mid + 1;
            } else if (candidate.entityId() > entityId) {
                hi = mid - 1;
            } else {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The v2 COMPOSED initial flight bearing of a discharge from this mount
     * — since GPEE v3 the fallback when no fresh-spawn read
     * matched (it cannot see per-shot spread jitter): the engine's own ballistic spawn composition
     * {@code shooterVel + projectileSpeed·û(mountRay)} (the
     * spawn uses {@code getMoveSpeed(stats, weapon)}
     * off the weapon spec, whose base speed the loader copies from the projectile spec's
     * base through identical stat-mult chains — so
     * it tracks what {@code WeaponAPI.getProjectileSpeed()} returns rather than being a
     * bare identity, and any residual is empirically bounded ≤1.11° over 106 real tracks),
     * normalized to [0,360). The mount ray
     * alone is NOT the bearing a moving shooter's bolt flies; the residual vs the
     * measured projectile track is the sub-degree spread state at discharge
     * (≤1.11° across 106 audited tracks at Hellbore cadence).
     *
     * <p>{@link Float#NaN} when any input read is unbound/non-finite or the composed
     * vector is degenerate ({@code projectileSpeed <= 0} covers beams and unbound
     * reads) — fail-soft, never a faked bearing.
     */
    public static float composeFlightBearingDeg(WeaponMountView mount) {
        float rayDeg = mount.currAngle();
        float speed = mount.projectileSpeed();
        float velX = mount.shipVelX();
        float velY = mount.shipVelY();
        if (!Float.isFinite(rayDeg) || !Float.isFinite(speed) || speed <= 0f
                || !Float.isFinite(velX) || !Float.isFinite(velY)) {
            return Float.NaN;
        }
        double rayRad = Math.toRadians(rayDeg);
        double vx = velX + speed * Math.cos(rayRad);
        double vy = velY + speed * Math.sin(rayRad);
        if (vx == 0.0 && vy == 0.0) {
            return Float.NaN;
        }
        float bearingDeg = (float) Math.toDegrees(Math.atan2(vy, vx));
        return bearingDeg < 0f ? bearingDeg + 360f : bearingDeg;
    }

    /**
     * The |v0| magnitude of the same composed flight vector as
     * {@link #composeFlightBearingDeg} (GPEE v6): same inputs, same
     * guards, {@code hypot} instead of {@code atan2}. May be finite {@code 0} where the
     * bearing is {@code NaN} (exactly-cancelling shooter velocity — a legal magnitude);
     * {@code NaN} when any input read is unbound (fail-soft, never a faked speed).
     */
    public static float composeFlightSpeed(WeaponMountView mount) {
        float rayDeg = mount.currAngle();
        float speed = mount.projectileSpeed();
        float velX = mount.shipVelX();
        float velY = mount.shipVelY();
        if (!Float.isFinite(rayDeg) || !Float.isFinite(speed) || speed <= 0f
                || !Float.isFinite(velX) || !Float.isFinite(velY)) {
            return Float.NaN;
        }
        double rayRad = Math.toRadians(rayDeg);
        double vx = velX + speed * Math.cos(rayRad);
        double vy = velY + speed * Math.sin(rayRad);
        return (float) Math.hypot(vx, vy);
    }
}
