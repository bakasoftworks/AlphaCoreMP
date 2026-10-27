package cc.starsector.server.bridge;

import static cc.starsector.server.bridge.EngineCombatBinding.asBoolean;
import static cc.starsector.server.bridge.EngineCombatBinding.numberAsFloat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The mount-probe family of {@link PilotCommandApplier}, in its own file: the
 * fire-class resolver + its repoint snapshot, the readiness probe (press gate, futile-head
 * cooldown / in-burst reads, the declared-ray continuation-credit fence), the fire-lock probe
 * and drive-lock wire predicate, the declared-ray / arc / mount-ordinal reads, and the
 * adopt force-fire driver. WHY a carve: PCA had grown past its non-blank-line warn tier, and this
 * family is the one cohesive block of mount reads with no apply-path state of its own.
 * <p>Provenance: carved from {@code PilotCommandApplier} (PCA)
 * with every body verbatim. The only edits to the moved text: the
 * readiness probe's {@code PilotCommandApplier.this} qualifier now reads {@code MountProbeLane.this},
 * and the five members PCA still calls ({@link #fireLockReadsBound}, {@link #fireLockedNow},
 * {@link #selectedClassSGroupDischargeable}, {@link #groupOrdinalForMountOnShip},
 * {@link #selectedGroupOrdinalOnShip}) went from {@code private} to package-private. PCA keeps a
 * same-signature delegate for every public member, so {@link EngineCombatBinding} and the contract
 * rows reach this code unchanged.
 * <p>Handles: a handle only this family reads moved here (declaration + Builder copy, verbatim); a
 * handle both sides read is DUPLICATED (same declaration, same {@code this.x = b.x;} line), both
 * copies final reads of the same Builder field. PCA helpers the bodies call by bare name
 * ({@code invoke}, {@code shipForWireIdOrNull}, {@code weaponGroupsCopyOrNull},
 * {@code weaponGroupOrdinalForMount}) are same-named private delegates through {@code host}. Built
 * as the LAST statement of PCA's constructor; this constructor only copies handles (it never calls
 * into {@code host}); serve thread only, like PCA.
 */
final class MountProbeLane {

    private final PilotCommandApplier host;
    private final EngineCombatBinding binding;
    private final Object engine;
    private final EngineReflectionBinding reflection;

    // ---- handles this family reads (PCA's declarations verbatim: moved = family-only, the rest duplicated) ----
    private final Method fluxGetCurrFlux; // nullable: flux view unavailable
    private final Method fluxGetMaxFlux; // nullable: flux view unavailable
    private final Method fluxIsOverloaded; // nullable: flux view unavailable
    private final Method projSpecGetBurstDelay;
    private final Method projSpecGetBurstSize;
    private final Method projSpecGetRefireDelay;
    private final Class<?> projectileWeaponSpecApi;
    private final Method shipGetWeaponGroupFor; // nullable: mount->group resolve unavailable
    private final Method weaponGetAmmo; // nullable: weapon-mount view unavailable
    private final Method weaponGetFluxCostToFire; // nullable: readiness probe skips flux-headroom
    private final Method weaponGetLocation; // nullable: adopt-serve muzzle-ε read (fail-CLOSED)
    private final Method weaponGetProjectileSpeed; // nullable: mount projectileSpeed reads NaN (tier stays available)
    private final Method weaponIsDisabled; // nullable: weapon-mount view unavailable
    private final Method weaponSetForceFireOneFrame; // nullable: named-mount serve write (fail-CLOSED)
    private final Method weaponSpecIsBeam;
    private final Method weaponUsesAmmo; // nullable: weapon-mount view unavailable
    private final Method shipGetFluxTracker; // nullable: flux view unavailable
    private final Method fluxIsVenting; // nullable: flux view unavailable
    private final Method shipGetAllWeapons; // nullable: weapon-mount view unavailable
    private final Method weaponGetSpec;
    private final Method weaponSpecGetWeaponId;
    private final Method weaponGetCooldownRemaining; // nullable: weapon-mount view unavailable
    private final Method weaponIsFiring; // nullable: weapon-mount view unavailable
    private final Method weaponDistanceFromArc; // nullable: readiness probe skips the arc gate
    // WeaponAPI.isInBurst (resolved off getCooldownRemaining's declaring
    // interface — no binding-builder change); nullable = the futile-head belt stands down.
    private final Method weaponIsInBurst;
    private int futileProbeReadErrors; // Probe read throws (log once, then count)
    private final Constructor<?> vector2fCtor; // nullable: Vector2f(float,float) arg unavailable
    // Fire-lock reads (all-or-nothing; any null => no lock probe, no drive-lock predicate)
    private final Method fireLockGetSystem;
    private final Method fireLockGetTravelDrive;
    private final Method fireLockGetPhaseCloak;
    private final Method fireLockShouldBlockCommands;
    private final Method fireLockGetSpec;
    private final Method fireLockIsFiringAllowed;
    private final Method fireLockGetBlockedCommands;
    private final Object fireLockBlockedKey;
    private long fireLockReadErrors; // fire-lock reads that threw (fail-open: unlocked); first one logged
    private final Method shipGetWeaponGroupsCopy; // nullable: group/autofire mirror unavailable
    private final Method shipGetSelectedGroupAPI; // nullable: group mirror unavailable
    /**
     * {@code ProjectileWeaponSpecAPI.getChargeTime()} (public API),
     * resolved off the bound
     * {@code projectileWeaponSpecApi} class like {@link #resolveWeaponIsInBurst}; null = the pad's
     * eligibility reads unreadable (-1). {@code declPadReadErrors} counts the pad's read throws
     * (the first one logged; the count gates the log, no round-line token).
     *
     */
    private final Method projSpecGetChargeTime;
    private int declPadReadErrors;

    /**
     * Built by {@link PilotCommandApplier#PilotCommandApplier} as its LAST act, off the same
     * {@link EngineCombatBinding.Builder}: the handle lines below are PCA's constructor lines
     * verbatim (same null policy, so a fake binding's dark-lane contract holds here too).
     */
    MountProbeLane(PilotCommandApplier host, EngineCombatBinding binding, EngineCombatBinding.Builder b) {
        this.host = Objects.requireNonNull(host, "host");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.engine = binding.engine;
        this.reflection = binding.reflection;
        this.fluxGetCurrFlux = b.fluxGetCurrFlux;
        this.fluxGetMaxFlux = b.fluxGetMaxFlux;
        this.fluxIsOverloaded = b.fluxIsOverloaded;
        this.projSpecGetBurstDelay = b.projSpecGetBurstDelay;
        this.projSpecGetBurstSize = b.projSpecGetBurstSize;
        this.projSpecGetRefireDelay = b.projSpecGetRefireDelay;
        this.projectileWeaponSpecApi = b.projectileWeaponSpecApi;
        this.shipGetWeaponGroupFor = b.shipGetWeaponGroupFor;
        this.weaponGetAmmo = b.weaponGetAmmo;
        this.weaponGetFluxCostToFire = b.weaponGetFluxCostToFire;
        this.weaponGetLocation = b.weaponGetLocation;
        this.weaponGetProjectileSpeed = b.weaponGetProjectileSpeed;
        this.weaponIsDisabled = b.weaponIsDisabled;
        this.weaponSetForceFireOneFrame = b.weaponSetForceFireOneFrame;
        this.weaponSpecIsBeam = b.weaponSpecIsBeam;
        this.weaponUsesAmmo = b.weaponUsesAmmo;
        this.shipGetFluxTracker = b.shipGetFluxTracker;
        this.fluxIsVenting = b.fluxIsVenting;
        this.shipGetAllWeapons = b.shipGetAllWeapons;
        this.weaponGetSpec = b.weaponGetSpec;
        this.weaponSpecGetWeaponId = b.weaponSpecGetWeaponId;
        this.weaponGetCooldownRemaining = b.weaponGetCooldownRemaining;
        this.weaponIsFiring = b.weaponIsFiring;
        this.weaponDistanceFromArc = b.weaponDistanceFromArc;
        this.weaponIsInBurst = resolveWeaponIsInBurst(b.weaponGetCooldownRemaining);
        this.fireLockGetSystem = b.fireLockGetSystem;
        this.fireLockGetTravelDrive = b.fireLockGetTravelDrive;
        this.fireLockGetPhaseCloak = b.fireLockGetPhaseCloak;
        this.fireLockShouldBlockCommands = b.fireLockShouldBlockCommands;
        this.fireLockGetSpec = b.fireLockGetSpec;
        this.fireLockIsFiringAllowed = b.fireLockIsFiringAllowed;
        this.fireLockGetBlockedCommands = b.fireLockGetBlockedCommands;
        this.fireLockBlockedKey = b.fireLockBlockedKey;
        this.shipGetWeaponGroupsCopy = b.shipGetWeaponGroupsCopy;
        this.shipGetSelectedGroupAPI = b.shipGetSelectedGroupAPI;
        this.vector2fCtor = b.vector2fCtor;
        this.projSpecGetChargeTime = resolveProjSpecGetChargeTime(b.projectileWeaponSpecApi);
    }

    /**
     * Production class oracle:
     * a per-ship {@link PilotApplyState.FireClassResolver} classifying mounts/groups from BASE
     * spec reads only — {@code WeaponAPI.getSpec()} → {@code WeaponSpecAPI.isBeam()/getWeaponId()}
     * + {@code ProjectileWeaponSpecAPI} burstSize/refireDelay/burstDelay (getter names
     * bytecode-verified against the API jar) — NEVER the live stat-modified
     * {@code getCooldown()}. Answers cache lazily for the hull's TENURE on its wire id — a
     * {@code seat_variant_repoint} swaps the roster under the SAME id, so the serve loop
     * rebinds a FRESH resolver + resets the demote trio at both repoint edges (
     * demotes live separately in the apply state); an unresolvable read answers H
     * UNCACHED — conservative and self-healing (a ship not yet in the wire map classifies once
     * it arrives). Every reflective throw is caught to H: the resolver must never kill a serve
     * tick. Group law: S only when EVERY member weapon is S (mixed groups ride held).
     */
    public PilotApplyState.FireClassResolver fireClassResolverForShip(final long shipId,
            final double rateMax,
            final Map<String, cc.starsector.server.runtime.FireClassLaw.FireClass> overrides) {
        Objects.requireNonNull(overrides, "overrides");
        return new PilotApplyState.FireClassResolver() {
            private final java.util.HashMap<Integer, Boolean> mountCache = new java.util.HashMap<>();
            private final java.util.HashMap<Integer, Boolean> groupCache = new java.util.HashMap<>();

            @Override
            public boolean mountIsClassS(int mountIndex) {
                Boolean cached = mountCache.get(mountIndex);
                if (cached != null) {
                    return cached;
                }
                Boolean classified = classifyMountOrNull(shipForWireIdOrNull(shipId), mountIndex);
                if (classified == null) {
                    return false; // unresolvable — H now, re-tries next query
                }
                mountCache.put(mountIndex, classified);
                return classified;
            }

            @Override
            public boolean groupIsClassS(int groupOrdinal) {
                Boolean cached = groupCache.get(groupOrdinal);
                if (cached != null) {
                    return cached;
                }
                Boolean classified = classifyGroupOrNull(shipForWireIdOrNull(shipId), groupOrdinal);
                if (classified == null) {
                    return false;
                }
                groupCache.put(groupOrdinal, classified);
                return classified;
            }

            /** Mount→group memo — the intake gate asks per S-decl under a
             *  hold (~1/tick), and the uncached resolve is 3 reflective reads + a
             *  getWeaponGroupsCopy list alloc on the sim thread; the mapping is stable for the
             *  hull's tenure (a repoint rebinds a fresh resolver, the groupCache idiom). −1
             *  (unresolvable) is NOT memoized — it re-tries next query. */
            private final java.util.HashMap<Integer, Integer> groupOfMount = new java.util.HashMap<>();

            @Override
            public int groupForMount(int mountIndex) {
                Integer cached = groupOfMount.get(mountIndex);
                if (cached != null) {
                    return cached;
                }
                int group = weaponGroupOrdinalForMount(shipId, mountIndex);
                if (group >= 0) {
                    groupOfMount.put(mountIndex, group);
                }
                return group;
            }

            /** The pad-eligibility memo, per hull tenure (the {@code groupOfMount}
             *  idiom: a repoint mints a fresh resolver). A DEFINITE answer (eligible group or -1)
             *  caches; an unresolved ship or an unreadable fact returns -1 UNCACHED.
             * */
            private final java.util.HashMap<Integer, Integer> declPadGroupOfMount = new java.util.HashMap<>();

            @Override
            public int declPadGroupForMount(int mountIndex) {
                Integer cached = declPadGroupOfMount.get(mountIndex);
                if (cached != null) {
                    return cached;
                }
                Object ship = shipForWireIdOrNull(shipId);
                if (ship == null) {
                    return -1;
                }
                Integer verdict = declPadGroupOrNull(ship, mountIndex);
                if (verdict == null) {
                    return -1;
                }
                declPadGroupOfMount.put(mountIndex, verdict);
                return verdict;
            }

            private Boolean classifyMountOrNull(Object ship, int mountIndex) {
                if (ship == null || mountIndex < 0 || shipGetAllWeapons == null) {
                    return null;
                }
                try {
                    Object raw = invoke(shipGetAllWeapons, ship, "ShipAPI.getAllWeapons");
                    if (!(raw instanceof List<?> weapons) || mountIndex >= weapons.size()) {
                        return null;
                    }
                    return classifyWeaponClassSOrNull(weapons.get(mountIndex), rateMax, overrides);
                } catch (Throwable t) {
                    return null; // read failed — H, never a serve kill
                }
            }

            private Boolean classifyGroupOrNull(Object ship, int groupOrdinal) {
                if (ship == null || groupOrdinal < 0 || shipGetAllWeapons == null) {
                    return null;
                }
                try {
                    Object raw = invoke(shipGetAllWeapons, ship, "ShipAPI.getAllWeapons");
                    if (!(raw instanceof List<?> weapons)) {
                        return null;
                    }
                    boolean anyMember = false;
                    for (int mount = 0; mount < weapons.size(); mount++) {
                        if (groupOrdinalForMountOnShip(ship, mount) != groupOrdinal) {
                            continue;
                        }
                        anyMember = true;
                        Boolean memberS = classifyWeaponClassSOrNull(weapons.get(mount), rateMax, overrides);
                        if (memberS == null) {
                            return null; // one unreadable member ⇒ group unresolvable ⇒ H uncached
                        }
                        if (!memberS) {
                            return false; // mixed group rides held (group law)
                        }
                    }
                    // Empty/unknown ordinal classifies H for the hull's tenure — no weapons
                    // appear under an ordinal mid-tenure (a repoint rebinds a fresh resolver),
                    // so caching is safe.
                    return anyMember;
                } catch (Throwable t) {
                    return null;
                }
            }

        };
    }

    /**
     * The production {@link PilotApplyState.MountReadinessProbe} — LIVE
     * WeaponAPI reads (deliberately unlike the BASE-spec-only {@link #fireClassResolverForShip}:
     * readiness is a per-tick live question), cache-free so hull-tenure repoints need no rebind
     * (every call re-looks the wire id up). Installed once per apply state by the serve loop.
     */
    public PilotApplyState.MountReadinessProbe mountReadinessProbeForShip(final long shipId) {
        // An anonymous class (not a lambda) so the probe also carries
        // the futile-head belt's cooldown + in-burst reads.
        return new PilotApplyState.MountReadinessProbe() {
            @Override
            public boolean mountDischargeableNow(int mountIndex, float aimWorldX, float aimWorldY) {
                return MountProbeLane.this.mountDischargeableNow(
                        shipForWireIdOrNull(shipId), mountIndex, aimWorldX, aimWorldY);
            }

            @Override
            public float mountCooldownRemainingSec(int mountIndex) {
                return mountCooldownRemainingSecNow(shipForWireIdOrNull(shipId), mountIndex);
            }

            @Override
            public boolean mountInBurstOrFiring(int mountIndex) {
                return mountInBurstNow(shipForWireIdOrNull(shipId), mountIndex);
            }

            @Override
            public boolean declaredRayInArc(int mountIndex, float muzzleX, float muzzleY, float bearingDeg) {
                return declaredRayInArcNow(shipForWireIdOrNull(shipId), mountIndex,
                        muzzleX, muzzleY, bearingDeg);
            }
        };
    }

    /**
     * The production {@link PilotApplyState.FireLockProbe}
     * (D2), in the {@link #mountReadinessProbeForShip} idiom — cache-free, the hull re-resolved by
     * wire id on EVERY call (a captured ship would read a dead hull after a remount), so one
     * install per apply state covers every hull tenure on this wire id. A null hull (unmounted,
     * dead, between tenures) answers unlocked. Returns null (no probe — today's adopt path
     * verbatim) when the fire-lock reads are unbound (fake bindings).
     */
    public PilotApplyState.FireLockProbe fireLockProbeForShip(final long shipId) {
        if (!fireLockReadsBound()) {
            return null;
        }
        return () -> fireLockedNow(shipForWireIdOrNull(shipId));
    }

    /**
     * The travel-drive wire-bit source (D3,
     * {@code ShipFluxSnapshot.FLAG_DRIVE_LOCK}) — over the {@code ownedShipIds} hulls ONLY, true for
     * a wire id iff vanilla's travel-drive click clause holds.
     * NOT the ship system and NOT the phase cloak (the client runs those locks locally at
     * zero latency; echoing them back would hold its trigger one round-trip late). Null when the
     * reads are unbound or no owned hull's drive blocks — the common case allocates no predicate
     * and costs the fold no per-ship test (the publish reads this once, on the sim
     * thread). The predicate tests fold entity ids, which equal the owned hulls' wire ids (> 0).
     */
    public java.util.function.LongPredicate driveLockWirePredicateOrNull() {
        if (!fireLockReadsBound()) {
            return null;
        }
        long[] locked = null;
        int count = 0;
        for (Long wireId : binding.ownedShipIds) {
            Object ship = wireId == null ? null : shipForWireIdOrNull(wireId);
            if (ship == null || !travelDriveBlocksFiring(ship)) {
                continue;
            }
            if (locked == null) {
                locked = new long[4];
            } else if (count == locked.length) {
                locked = java.util.Arrays.copyOf(locked, count * 2);
            }
            locked[count++] = wireId;
        }
        if (count == 0) {
            return null;
        }
        final long[] ids = locked;
        final int n = count;
        return entityId -> {
            for (int i = 0; i < n; i++) {
                if (ids[i] == entityId) {
                    return true;
                }
            }
            return false;
        };
    }

    boolean fireLockReadsBound() {
        return fireLockGetSystem != null && fireLockGetTravelDrive != null && fireLockGetPhaseCloak != null
                && fireLockShouldBlockCommands != null && fireLockGetSpec != null
                && fireLockIsFiringAllowed != null && fireLockGetBlockedCommands != null
                && fireLockBlockedKey != null;
    }

    /**
     * The UNION of the two refusals a human trigger passes — (a) the command
     * filter's refusal set holds the internal USE_SELECTED_GROUP (catches
     * API / hullmod blocks too), (b) vanilla's click gate for the ship system, the travel drive and
     * the phase cloak. A read that throws answers unlocked (counted, first one logged): a failed
     * read never silences a ship.
     */
    boolean fireLockedNow(Object ship) {
        if (ship == null) {
            return false;
        }
        try {
            if (fireLockGetBlockedCommands.invoke(ship) instanceof java.util.Collection<?> blocked
                    && blocked.contains(fireLockBlockedKey)) {
                return true;
            }
            return systemBlocksFiring(fireLockGetSystem.invoke(ship))
                    || systemBlocksFiring(fireLockGetTravelDrive.invoke(ship))
                    || systemBlocksFiring(fireLockGetPhaseCloak.invoke(ship));
        } catch (ReflectiveOperationException | RuntimeException e) {
            noteFireLockReadError(e);
            return false;
        }
    }

    /** The travel-drive clause alone (a hull with no drive — e.g. a drone — is false). */
    private boolean travelDriveBlocksFiring(Object ship) {
        try {
            return systemBlocksFiring(fireLockGetTravelDrive.invoke(ship));
        } catch (ReflectiveOperationException | RuntimeException e) {
            noteFireLockReadError(e);
            return false;
        }
    }

    /** Vanilla's click clause for one system: non-null, shouldBlockCommands(), !getSpec().isFiringAllowed(). */
    private boolean systemBlocksFiring(Object system) throws ReflectiveOperationException {
        if (system == null || !Boolean.TRUE.equals(fireLockShouldBlockCommands.invoke(system))) {
            return false;
        }
        Object spec = fireLockGetSpec.invoke(system);
        return spec != null && Boolean.FALSE.equals(fireLockIsFiringAllowed.invoke(spec));
    }

    /**
     * fire-lock reads that threw since this binding was built
     * (fail-open: each read answered unlocked) — SESSION-cumulative: the binding outlives a
     * round, so a later round's line re-prints an earlier round's throws. Round lines print it as
     * {@code fire_lock_read_errors=}; reader = the arena rig's R-LOCK INFO row (> 0 means a
     * failed R-LOCK is the READ, not the gate). Delete with {@code fire_lock_waits=}.
     */
    public int fireLockReadErrors() {
        return (int) Math.min(Integer.MAX_VALUE, fireLockReadErrors);
    }

    private void noteFireLockReadError(Exception e) {
        fireLockReadErrors++;
        if (fireLockReadErrors == 1L) {
            System.out.println("fire-lock read FAILED (tolerated: reads unlocked, further throws counted only) "
                    + e.getClass().getSimpleName());
        }
    }

    /** First credit-fence read throw logged (fail-closed; refusals count on the round line). */
    private boolean creditFenceReadErrorLogged;

    /**
     * The continuation-credit spend's stateless fence —
     * mount ordinal in range + the DECLARED muzzle within {@link PilotApplyState#ADOPT_MUZZLE_EPS}
     * of the weapon's LIVE location (the serve fence's own muzzle-eps, same constant)
     * + the DECLARED ray inside the weapon's live arc within
     * {@link PilotApplyState#ADOPT_ARC_TOL_DEG}, through {@link #declaredRayArcDistDeg} (the
     * serve fence's own far-point read, shared so the two can never drift). Fail-CLOSED: a
     * null ship/seam/weapon, an unreadable or non-finite location, a non-finite ray or arc
     * read, or a throw answers false (the decl
     * then falls through to the LOUD serve-time fence).
     */
    private boolean declaredRayInArcNow(Object ship, int mountIndex,
            float muzzleX, float muzzleY, float bearingDeg) {
        if (ship == null || shipGetAllWeapons == null || weaponDistanceFromArc == null
                || weaponGetLocation == null
                || vector2fCtor == null || !Float.isFinite(muzzleX) || !Float.isFinite(muzzleY)
                || !Float.isFinite(bearingDeg)) {
            return false;
        }
        try {
            Object raw = shipGetAllWeapons.invoke(ship);
            if (!(raw instanceof List<?> weapons) || mountIndex < 0 || mountIndex >= weapons.size()) {
                return false;
            }
            Object weapon = weapons.get(mountIndex);
            if (weapon == null) {
                return false;
            }
            // Muzzle fence: muzzle-eps against the LIVE weapon location, the serve
            // fence's own ADOPT_MUZZLE_EPS (one number) — without it a patched client picks any
            // muzzle whose far point lands in the arc. Fail-CLOSED on an unreadable location.
            Object mountLoc = weaponGetLocation.invoke(weapon);
            float dx = muzzleX - vectorComponentOrNaN(mountLoc, true);
            float dy = muzzleY - vectorComponentOrNaN(mountLoc, false);
            if (!Float.isFinite(dx) || !Float.isFinite(dy)
                    || dx * dx + dy * dy > PilotApplyState.ADOPT_MUZZLE_EPS * PilotApplyState.ADOPT_MUZZLE_EPS) {
                return false;
            }
            float arcDist = declaredRayArcDistDeg(weapon, muzzleX, muzzleY, bearingDeg);
            return Float.isFinite(arcDist) && arcDist <= PilotApplyState.ADOPT_ARC_TOL_DEG;
        } catch (Throwable t) {
            if (!creditFenceReadErrorLogged) {
                creditFenceReadErrorLogged = true;
                System.out.println("pilot-fire-decl credit-fence read-error err="
                        + t.getClass().getSimpleName()
                        + " (fail-closed: refusal counted in credit_fence_refused; logged once)");
            }
            return false;
        }
    }

    /**
     * The bearing read shared by the adopt serve fence and the continuation-credit fence: a far
     * point down the DECLARED ray ({@code muzzle + 1000·û(bearing)}) through the engine's own
     * {@code distanceFromArc} (the weaponsThatWouldBeFiredAtPoint read), so the answer
     * is angular off-arc degrees.
     */
    private float declaredRayArcDistDeg(Object weapon, float muzzleX, float muzzleY, float bearingDeg)
            throws ReflectiveOperationException {
        double brgRad = Math.toRadians(bearingDeg);
        Object farPoint = vector2fCtor.newInstance(
                muzzleX + (float) (1000.0 * Math.cos(brgRad)),
                muzzleY + (float) (1000.0 * Math.sin(brgRad)));
        return numberAsFloat(weaponDistanceFromArc.invoke(weapon, farPoint), "distanceFromArc");
    }

    /**
     * Resolve {@code WeaponAPI.isInBurst()} off the interface that declares
     * the binding's {@code getCooldownRemaining} handle (it IS WeaponAPI in production —
     * {@code RealEngineCombatBindingFactory}); null when the view is unbound or the method is
     * absent (logged once) — the futile belt then stands down.
     */
    private static Method resolveWeaponIsInBurst(Method cooldownHandle) {
        if (cooldownHandle == null) {
            return null;
        }
        try {
            return cooldownHandle.getDeclaringClass().getMethod("isInBurst");
        } catch (Throwable t) {
            System.out.println("pilot-fire-decl futile-belt off reason=isInBurst-unresolved err="
                    + t.getClass().getSimpleName());
            return null;
        }
    }

    /** The live weapon at this mount ordinal, or null. */
    private Object weaponAtMountOrNull(Object ship, int mountIndex) throws ReflectiveOperationException {
        if (ship == null || mountIndex < 0 || shipGetAllWeapons == null) {
            return null;
        }
        Object raw = shipGetAllWeapons.invoke(ship);
        if (!(raw instanceof List<?> weapons) || mountIndex >= weapons.size()) {
            return null;
        }
        return weapons.get(mountIndex);
    }

    /**
     * Live {@code getCooldownRemaining()} seconds at this mount, NaN on any
     * unreadable seam (fail-open: the futile belt never fires). A missing isInBurst handle
     * also answers NaN — without the burst read the cooldown sawtooth in a burst gap
     * would look like a clock.
     */
    private float mountCooldownRemainingSecNow(Object ship, int mountIndex) {
        if (weaponGetCooldownRemaining == null || weaponIsInBurst == null) {
            return Float.NaN;
        }
        try {
            Object weapon = weaponAtMountOrNull(ship, mountIndex);
            if (weapon == null) {
                return Float.NaN;
            }
            return numberAsFloat(weaponGetCooldownRemaining.invoke(weapon), "getCooldownRemaining");
        } catch (Throwable t) {
            noteFutileProbeReadError(t);
            return Float.NaN;
        }
    }

    /**
     * Live {@code WeaponAPI.isInBurst()} at this mount. NOT
     * {@code isFiring()}: that reads the tracker's non-idle state,
     * which stays true through the whole CHARGEDOWN — exactly the orphan's 8.75 s window — so
     * it would disarm the belt where it is needed; {@code isInBurst}
     * is true only while burst shots are still due. Unreadable =
     * true (stand down: never call a head futile on a failed read).
     */
    private boolean mountInBurstNow(Object ship, int mountIndex) {
        if (weaponIsInBurst == null) {
            return true;
        }
        try {
            Object weapon = weaponAtMountOrNull(ship, mountIndex);
            if (weapon == null) {
                return true;
            }
            return !Boolean.FALSE.equals(asBoolean(weaponIsInBurst.invoke(weapon)));
        } catch (Throwable t) {
            noteFutileProbeReadError(t);
            return true;
        }
    }

    /** Count a probe read throw; log the first. */
    private void noteFutileProbeReadError(Throwable t) {
        futileProbeReadErrors++;
        if (futileProbeReadErrors == 1) {
            System.out.println("pilot-fire-decl futile-probe read-error n=1 err="
                    + t.getClass().getSimpleName() + " (fail-open; further errors counted only)");
        }
    }

    /**
     * Whether the weapon at this mount ordinal could BEGIN a discharge if FIRE were pressed this
     * tick — the engine's own press gate, mirrored read-only (trigger dispatch;
     * weapon pre-gates ammo/disabled; tracker
     * — a press in CHARGEDOWN starts nothing and {@code isFiring()} is
     * true through CHARGEUP/ACTIVE/CHARGEDOWN; flux hard cap + overload/vent;
     * the arc gate — the group dispatch fires a member
     * only when the aim point is in its LIVE arc, {@code weaponsThatWouldBeFiredAtPoint}
     * + {@code distanceFromArc==0}, so an
     * out-of-arc named mount is refused via {@code WeaponAPI.distanceFromArc(aim) > 0} — a live
     * per-tick read, NEVER static hull-arc data: recorded forensics showed static arcs
     * mis-verdict swept aims). Every unreadable seam ANSWERS TRUE (press — the
     * pre-probe path): the gate may only withhold a press it can prove futile, so a degraded
     * view can never silence a ship. Skipped edge gates (forceDisabled / forceNoFireOneFrame /
     * NO_MANUAL_FIRE / phase-cloak-charging) fail toward pressing too — the engine still
     * refuses the discharge; the strobe collapse only needs the dominant blockers.
     */
    /** W3-M measure: arc-branch refusals (aggregate across seats), throttled log. */
    private int fireArcRefusals;

    private boolean mountDischargeableNow(Object ship, int mountIndex,
            float aimWorldX, float aimWorldY) {
        if (ship == null || mountIndex < 0 || shipGetAllWeapons == null
                || weaponGetCooldownRemaining == null || weaponIsFiring == null) {
            return true;
        }
        try {
            Object raw = shipGetAllWeapons.invoke(ship);
            if (!(raw instanceof List<?> weapons) || mountIndex >= weapons.size()) {
                return true;
            }
            Object weapon = weapons.get(mountIndex);
            if (weaponDistanceFromArc != null && vector2fCtor != null
                    && Float.isFinite(aimWorldX) && Float.isFinite(aimWorldY)) {
                Object aimPoint = vector2fCtor.newInstance(aimWorldX, aimWorldY);
                float arcDist = numberAsFloat(
                        weaponDistanceFromArc.invoke(weapon, aimPoint), "distanceFromArc");
                if (!Float.isNaN(arcDist) && arcDist > 0f) {
                    // The engine's own dispatch refuses this mount at this aim —
                    // pressing would only strobe the group's in-arc mounts (the strobe tails a recorded run showed).
                    // W3-M MEASURE (bounded, ≤1 line/5s sustained): rank-1 vs
                    // rank-3/4 disambiguation for the ridden-drone pressless-expiry class —
                    // arc-refusals ≈ expired ⇒ the pre-adjudicated escalation
                    // is the fix lane; ≈0 ⇒ look at mask convergence/targets.
                    fireArcRefusals++;
                    if (fireArcRefusals == 1 || fireArcRefusals % 300 == 0) {
                        System.out.println("fire-press arc-refusal n=" + fireArcRefusals
                                + " mount=" + mountIndex + " arcDist=" + arcDist);
                    }
                    return false;
                }
            }
            if (numberAsFloat(weaponGetCooldownRemaining.invoke(weapon), "getCooldownRemaining") > 0f) {
                return false; // CHARGEDOWN — the one state where a press starts nothing
            }
            if (Boolean.TRUE.equals(asBoolean(weaponIsFiring.invoke(weapon)))) {
                return false; // CHARGEUP/ACTIVE/burst — cycle committed; a re-press only strobes the group
            }
            if (weaponIsDisabled != null
                    && Boolean.TRUE.equals(asBoolean(weaponIsDisabled.invoke(weapon)))) {
                return false;
            }
            if (weaponUsesAmmo != null && weaponGetAmmo != null
                    && Boolean.TRUE.equals(asBoolean(weaponUsesAmmo.invoke(weapon)))
                    && numberAsFloat(weaponGetAmmo.invoke(weapon), "getAmmo") <= 0f) {
                return false;
            }
            if (shipGetFluxTracker != null) {
                Object tracker = shipGetFluxTracker.invoke(ship);
                if (tracker != null) {
                    if (fluxIsOverloaded != null
                            && Boolean.TRUE.equals(asBoolean(fluxIsOverloaded.invoke(tracker)))) {
                        return false;
                    }
                    if (fluxIsVenting != null
                            && Boolean.TRUE.equals(asBoolean(fluxIsVenting.invoke(tracker)))) {
                        return false;
                    }
                    if (weaponGetFluxCostToFire != null && fluxGetCurrFlux != null
                            && fluxGetMaxFlux != null) {
                        float cost = numberAsFloat(
                                weaponGetFluxCostToFire.invoke(weapon), "getFluxCostToFire");
                        float curr = numberAsFloat(fluxGetCurrFlux.invoke(tracker), "getCurrFlux");
                        float max = numberAsFloat(fluxGetMaxFlux.invoke(tracker), "getMaxFlux");
                        if (!Float.isNaN(cost) && !Float.isNaN(curr) && !Float.isNaN(max)
                                && max - curr < cost) {
                            return false; // flux hard cap: the engine refuses the shot
                        }
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            return true; // unreadable = press — never silence a ship on a failed read
        }
    }

    /**
     * Whether ANY member mount of this (server-space) weapon group
     * could begin a discharge this tick at the pilot's carried aim — the decl-recency belt's
     * decl-capability oracle ({@link PilotApplyState#noteClassSHeldTick}). Reuses the
     * press gate verbatim ({@link #mountDischargeableNow}: live cooldown/isFiring/disabled/
     * ammo/overload/vent/flux-headroom + the live-arc term), so the belt's verdict of
     * "this silence is unexplained" is exactly the engine's own "a press now would discharge".
     * Called only on held-trigger ticks of the held-excluded routed shape (empty FIFO,
     * selected group class-S — by that gate every member is non-demoted class-S, so plain
     * group membership is the only filter) and short-circuits on the first capable mount.
     * {@code false} (freeze) is answered ONLY on positive proof: ≥1 member mount was
     * enumerated and EVERY one refused. Anything less — unbound seams, an unreadable weapons
     * list, zero enumerable members (a live read contradicting the resolver's class-S verdict,
     * e.g. a fake/degraded world) — answers {@code true} (capable): the belt then keeps its
     * original reach, fail-open toward the loud held revert, never toward a frozen
     * fail-safe.
     */
    boolean selectedClassSGroupDischargeable(Object ship, int groupOrdinal,
            float aimWorldX, float aimWorldY) {
        if (ship == null || groupOrdinal < 0 || shipGetAllWeapons == null
                || shipGetWeaponGroupFor == null || shipGetWeaponGroupsCopy == null) {
            return true; // unreadable = capable — the belt keeps its original reach
        }
        try {
            Object raw = shipGetAllWeapons.invoke(ship);
            if (!(raw instanceof List<?> weapons)) {
                return true;
            }
            boolean memberFound = false;
            for (int mount = 0; mount < weapons.size(); mount++) {
                if (groupOrdinalForMountOnShip(ship, mount) != groupOrdinal) {
                    continue;
                }
                memberFound = true;
                if (mountDischargeableNow(ship, mount, aimWorldX, aimWorldY)) {
                    return true;
                }
            }
            return !memberFound;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * The production {@link PilotApplyState.MountFireDriver} — the OPTION-A
     * adopt serve (a scoped force-fire exception). Cache-free
     * per-call wire-id lookup like {@link #mountReadinessProbeForShip}; the per-mount rate
     * memory lives in the closure (one apply state per round ⇒ the memory dies with the round,
     * no cross-round tick-clock staleness). Fake bindings leave the seams unbound, so every
     * ray head rejects {@code adopt-seam} LOUD — the adopt lane is fail-CLOSED end to end,
     * deliberately unlike the press path's fail-open law (a guessed press only wastes a press;
     * a guessed FORCED fire would be a server-minted shot the client never committed).
     */
    public PilotApplyState.MountFireDriver mountFireDriverForShip(final long shipId) {
        return (simTick, head) -> adoptForceFireNamedMount(
                shipForWireIdOrNull(shipId), simTick, head);
    }

    /**
     * Validate + serve one ray-carrying declaration by force-firing its NAMED mount
     * ({@code WeaponAPI.setForceFireOneFrame(true)} — engine consume is one-shot self-clearing
     * with ammo/cooldown/flux gates preserved, so S1
     * resource authority survives; an engine-refused force-fire leaves the head pending for
     * the ε-clock retry). Returns {@code null} = force-fire issued + ray posted to
     * {@link cc.starsector.server.runtime.AdoptServeRegistry} for the in-callback newborn
     * re-pose; else the LOUD reject reason ({@code adopt-range/adopt-eps/adopt-arc/adopt-speed/
     * adopt-seam}) — fail-CLOSED including every unreadable seam.
     * The tick-based per-mount rate cap ({@code adopt-rate}) is RETIRED — the
     * engine's own cooldown gate on a force-fire (the engine's force-fire consume keeps
     * {@code getCooldownRemaining} authority) plus the readiness probe ARE the physical rate
     * floor; a spec-derived tick cap sat ABOVE the live floor under ROF stat mults (Accelerated
     * Ammo Feeder 2× · armoredweapons S-mod 1.1× ⇒ 55-tick volleys vs a 60-tick cap) and
     * dropped REAL volleys LOUD ({@code adopt_rejects=52(adopt-rate:52)}), the
     * HVEL desync seed.
     */
    private String adoptForceFireNamedMount(Object ship, long simTick,
            cc.starsector.server.runtime.FireDischargeDeclaration head) {
        if (ship == null || shipGetAllWeapons == null || weaponGetLocation == null
                || weaponSetForceFireOneFrame == null || weaponDistanceFromArc == null
                || vector2fCtor == null) {
            return "adopt-seam";
        }
        try {
            Object raw = shipGetAllWeapons.invoke(ship);
            if (!(raw instanceof List<?> weapons)) {
                return "adopt-seam";
            }
            int mount = head.mountIndex();
            if (mount < 0 || mount >= weapons.size()) {
                return "adopt-range";
            }
            Object weapon = weapons.get(mount);
            Object mountLoc = weaponGetLocation.invoke(weapon);
            float wx = vectorComponentOrNaN(mountLoc, true);
            float wy = vectorComponentOrNaN(mountLoc, false);
            // Bearing check rides the engine's own arc predicate: a far point down the DECLARED
            // ray (muzzle + 1000·û(bearing)) through distanceFromArc — the
            // weaponsThatWouldBeFiredAtPoint read, so tolerance is angular off-arc degrees.
            // One shared helper with the credit fence (never two reads).
            float arcDist = declaredRayArcDistDeg(weapon, head.muzzleX(), head.muzzleY(),
                    head.bearingDeg());
            // Fold: the mount's stat-modified muzzle speed — the exact value the
            // engine's ballistic spawn composes with shooter velocity (the GPEE-v2 read idiom
            // above). Unbound handle / non-finite read stays NaN ⇒ adopt-seam (fail-CLOSED).
            float specSpeed = Float.NaN;
            if (weaponGetProjectileSpeed != null) {
                Object specSpeedRaw = weaponGetProjectileSpeed.invoke(weapon);
                if (specSpeedRaw instanceof Number n && Float.isFinite(n.floatValue())) {
                    specSpeed = n.floatValue();
                }
            }
            boolean projectileSpec = adoptProjectileSpecReadable(weapon);
            String reason = PilotApplyState.adoptValidationFailure(mount, weapons.size(),
                    Float.isNaN(wx) ? Float.NaN : head.muzzleX() - wx,
                    Float.isNaN(wy) ? Float.NaN : head.muzzleY() - wy,
                    arcDist, head.speed(), specSpeed, projectileSpec);
            if (reason != null) {
                return reason;
            }
            weaponSetForceFireOneFrame.invoke(weapon, Boolean.TRUE);
            // Chargeup pre-pay: the client already RAN this
            // weapon's chargeup before it declared — pre-pay it on the engine's own state machine
            // so the forced fire discharges on this advance like a chargeup-0 gun (a live test
            // measured +16-19 ticks for a 0.25-s gun vs +4-5 for chargeup-0). Fail-closed inside:
            // UNBOUND = one loud row and the lane behaves as before; never throws.
            WeaponChargeupPreAdvance.preAdvance(weapon, weaponGetSpec, head.shipId(), mount);
            // PDCL v5: the posted ray is the SHIP-frame launch ray (fired
            // bearing + relative launch speed) — the emitter composes the newborn's velocity as
            // serverShipVel + speed·û(bearing). The sub-floor server recompose is
            // RETIRED: the client owns the degenerate-read recompose, and a Salamander's honest
            // relative speed IS 50.0 (float noise below the old floor would have re-posed it at
            // |shipVel + 400·û|).
            cc.starsector.server.runtime.AdoptServeRegistry.post(ship, mount,
                    head.muzzleX(), head.muzzleY(), head.bearingDeg(), head.speed(), simTick);
            return null;
        } catch (Throwable t) {
            return "adopt-seam"; // fail-CLOSED: an unreadable seam never serves a forced shot
        }
    }

    /**
     * class-S fence input: whether this mount's spec reads as a
     * PROJECTILE weapon spec — beam / non-projectile specs (outside the class-S fence) and
     * every unreadable seam answer false ⇒ {@code adopt-seam}. This read used to also derive
     * the tick-based per-mount rate cap ({@code 0.5 · tps · refireDelay}); that cap is RETIRED
     * (it ignored live ROF stat mults and rejected real volleys — the engine's cooldown
     * gate is the floor), so only the fence half survives.
     */
    private boolean adoptProjectileSpecReadable(Object weapon) {
        try {
            if (weaponGetSpec == null || weaponSpecIsBeam == null || projectileWeaponSpecApi == null) {
                return false;
            }
            Object spec = weaponGetSpec.invoke(weapon);
            return spec != null && weaponSpecIsBeam.invoke(spec) instanceof Boolean beam && !beam
                    && projectileWeaponSpecApi.isInstance(spec);
        } catch (Throwable t) {
            return false;
        }
    }

    private Method vectorGetX; // lazy: resolved from the first weapon-location object
    private Method vectorGetY; // (ReadableVector2f.getX/getY — lwjgl is not a compile-time dep here)

    private float vectorComponentOrNaN(Object vec, boolean xAxis) {
        if (vec == null) {
            return Float.NaN;
        }
        try {
            if (vectorGetX == null || vectorGetY == null) {
                vectorGetX = vec.getClass().getMethod("getX");
                vectorGetY = vec.getClass().getMethod("getY");
            }
            Object raw = (xAxis ? vectorGetX : vectorGetY).invoke(vec);
            return raw instanceof Number n ? n.floatValue() : Float.NaN;
        } catch (Throwable t) {
            return Float.NaN; // NaN rides to adoptValidationFailure ⇒ adopt-seam (fail-CLOSED)
        }
    }

    /**
     * Single classification read (BASE spec only, never live stats — law javadoc at
     * {@link #fireClassResolverForShip}): the S/H verdict for one weapon, or null when a seam
     * is unbound or a read fails (callers treat null as H-uncached / '?' in the snapshot).
     * Outer so the repoint snapshot classifies UNCACHED through the exact
     * predicate the resolver caches.
     */
    private Boolean classifyWeaponClassSOrNull(Object weapon, double rateMax,
            Map<String, cc.starsector.server.runtime.FireClassLaw.FireClass> overrides)
            throws Exception {
        if (weapon == null || weaponGetSpec == null || weaponSpecIsBeam == null
                || weaponSpecGetWeaponId == null || projectileWeaponSpecApi == null
                || projSpecGetRefireDelay == null || projSpecGetBurstSize == null
                || projSpecGetBurstDelay == null) {
            return null;
        }
        Object spec = weaponGetSpec.invoke(weapon);
        if (spec == null) {
            return null;
        }
        Object weaponIdRaw = weaponSpecGetWeaponId.invoke(spec);
        String weaponId = weaponIdRaw instanceof String id ? id : null;
        Object beamRaw = weaponSpecIsBeam.invoke(spec);
        if (!(beamRaw instanceof Boolean beam)) {
            return null;
        }
        // Non-beam, non-projectile spec (unexpected sibling) rides INF ⇒ H fail-safe;
        // an override still wins inside classify() — including over the beam exclusion.
        double rate = Double.POSITIVE_INFINITY;
        if (!beam && projectileWeaponSpecApi.isInstance(spec)) {
            Object burstRaw = projSpecGetBurstSize.invoke(spec);
            Object refireRaw = projSpecGetRefireDelay.invoke(spec);
            Object burstDelayRaw = projSpecGetBurstDelay.invoke(spec);
            if (!(burstRaw instanceof Integer burst) || !(refireRaw instanceof Float refire)
                    || !(burstDelayRaw instanceof Float burstDelay)) {
                return null;
            }
            rate = cc.starsector.server.runtime.FireClassLaw.sustainedRate(
                    burst, refire, burstDelay);
        }
        return cc.starsector.server.runtime.FireClassLaw.classify(
                weaponId, beam, rate, rateMax, overrides)
                == cc.starsector.server.runtime.FireClassLaw.FireClass.S;
    }

    /**
     * Repoint probe: one-line UNCACHED class snapshot
     * of the hull behind this wire id — {@code m<ordinal>=<weaponId>:g<group>:<S|H|?>} per
     * mount plus the live selected-group ordinal. Forensics read it against the client's decl
     * stream: a post-rebind flood on a mount whose snapshot verdict is honest-S means the
     * CLIENT's ordinal maps a DIFFERENT weapon (mount-ordinal skew), not a stale server
     * verdict. Deliberately bypasses the resolver: at the round-wrap repoint edge the fresh
     * hull first exists at the next stepped tick, and reading through the fresh resolver
     * there would warm its caches with the DYING hull's verdicts — the very staleness this bypass
     * removes. Read-fails degrade to '?' fields; the snapshot never kills the repoint path.
     */
    public String fireClassSnapshotForShip(long shipId, double rateMax,
            Map<String, cc.starsector.server.runtime.FireClassLaw.FireClass> overrides) {
        Object ship = shipForWireIdOrNull(shipId);
        if (ship == null || shipGetAllWeapons == null) {
            return "ship=unresolved";
        }
        try {
            Object raw = invoke(shipGetAllWeapons, ship, "ShipAPI.getAllWeapons");
            if (!(raw instanceof List<?> weapons)) {
                return "weapons=unreadable";
            }
            StringBuilder line = new StringBuilder("sel=g")
                    .append(selectedGroupOrdinalOnShip(ship));
            for (int mount = 0; mount < weapons.size(); mount++) {
                String weaponId = "?";
                String cls = "?";
                try {
                    Object spec = weaponGetSpec == null ? null
                            : weaponGetSpec.invoke(weapons.get(mount));
                    Object idRaw = spec == null || weaponSpecGetWeaponId == null ? null
                            : weaponSpecGetWeaponId.invoke(spec);
                    if (idRaw instanceof String id) {
                        weaponId = id;
                    }
                    Boolean s = classifyWeaponClassSOrNull(weapons.get(mount), rateMax, overrides);
                    cls = s == null ? "?" : (s ? "S" : "H");
                } catch (Throwable t) {
                    // '?' stands — one unreadable mount never drops the whole snapshot
                }
                line.append(" m").append(mount).append('=').append(weaponId)
                        .append(":g").append(groupOrdinalForMountOnShip(ship, mount))
                        .append(':').append(cls);
            }
            return line.toString();
        } catch (Throwable t) {
            return "snapshot=failed";
        }
    }

    /**
     * The {@link DeclaredShotPad} eligibility read behind
     * {@code declPadGroupForMount}: the mount's SERVER group ordinal iff the mount sits in a weapon
     * group (a system-slot weapon has none ⇒ -1), the group is not ALTERNATING (the existing
     * dwell-advance handles, {@link EngineReflectionBinding#groupIsAlternatingOrNull}), EVERY
     * member carries the declared mount's weapon id, and that weapon's BASE spec is non-beam,
     * a {@code ProjectileWeaponSpecAPI} with burst size {@code <= 1} and chargeup 0 (members
     * share the id, so one spec read decides for all). -1 = definitely ineligible (cacheable);
     * null = a handle unbound or a read failed (uncached; every throw counted, the first logged).
     *
     */
    private Integer declPadGroupOrNull(Object ship, int mountIndex) {
        if (ship == null || mountIndex < 0 || shipGetAllWeapons == null || shipGetWeaponGroupFor == null
                || shipGetWeaponGroupsCopy == null || weaponGetSpec == null || weaponSpecIsBeam == null
                || weaponSpecGetWeaponId == null || projectileWeaponSpecApi == null
                || projSpecGetBurstSize == null || projSpecGetChargeTime == null) {
            return null;
        }
        try {
            Object rawWeapons = invoke(shipGetAllWeapons, ship, "ShipAPI.getAllWeapons");
            if (!(rawWeapons instanceof List<?> weapons)) {
                return null;
            }
            // An index past the RESOLVED ship's roster is a
            // definite -1 (the list is fixed for the hull's tenure; a repoint mints a fresh resolver), so
            // the caller caches it and a spammed [size, 256) index costs one getAllWeapons read.
            if (mountIndex >= weapons.size()) {
                return -1;
            }
            Object group = invoke(shipGetWeaponGroupFor, ship, "ShipAPI.getWeaponGroupFor",
                    weapons.get(mountIndex));
            if (group == null) {
                return -1; // no weapon group (a system-slot weapon) — definite
            }
            List<?> groups = weaponGroupsCopyOrNull(ship);
            int ordinal = groups == null ? -1 : groups.indexOf(group);
            Boolean alternating = reflection.groupIsAlternatingOrNull(group);
            if (ordinal < 0 || alternating == null) {
                return null;
            }
            if (alternating) {
                return -1;
            }
            Object spec = weaponGetSpec.invoke(weapons.get(mountIndex));
            Object idRaw = spec == null ? null : weaponSpecGetWeaponId.invoke(spec);
            Object beamRaw = spec == null ? null : weaponSpecIsBeam.invoke(spec);
            if (!(idRaw instanceof String weaponId) || !(beamRaw instanceof Boolean beam)) {
                return null;
            }
            if (beam || !projectileWeaponSpecApi.isInstance(spec)) {
                return -1;
            }
            Object burstRaw = projSpecGetBurstSize.invoke(spec);
            Object chargeRaw = projSpecGetChargeTime.invoke(spec);
            if (!(burstRaw instanceof Integer burst) || !(chargeRaw instanceof Float charge)) {
                return null;
            }
            if (burst > 1 || charge != 0f) {
                return -1;
            }
            for (int mount = 0; mount < weapons.size(); mount++) {
                Object member = weapons.get(mount);
                if (mount == mountIndex || !group.equals(
                        invoke(shipGetWeaponGroupFor, ship, "ShipAPI.getWeaponGroupFor", member))) {
                    continue;
                }
                Object memberSpec = weaponGetSpec.invoke(member);
                Object memberId = memberSpec == null ? null : weaponSpecGetWeaponId.invoke(memberSpec);
                if (!(memberId instanceof String)) {
                    return null;
                }
                if (!weaponId.equals(memberId)) {
                    return -1; // mixed group: the group press would fire a weapon the client never declared
                }
            }
            return ordinal;
        } catch (Throwable t) {
            declPadReadErrors++;
            if (declPadReadErrors == 1) {
                System.out.println("pilot-decl-pad eligibility read-error n=1 err="
                        + t.getClass().getSimpleName() + " (fail-closed -1; further errors counted only)");
            }
            return null;
        }
    }

    /** Resolve {@code getChargeTime} off the bound projectile spec API; null when absent. */
    private static Method resolveProjSpecGetChargeTime(Class<?> projectileWeaponSpecApi) {
        if (projectileWeaponSpecApi == null) {
            return null;
        }
        try {
            return projectileWeaponSpecApi.getMethod("getChargeTime");
        } catch (Throwable t) {
            System.out.println("pilot-decl-pad off reason=getChargeTime-unresolved err="
                    + t.getClass().getSimpleName());
            return null;
        }
    }

    /** Mount ordinal → owning group ordinal on a resolved ship; -1 = unresolvable (legacy pairing). */
    int groupOrdinalForMountOnShip(Object ship, int mountIndex) {
        if (mountIndex < 0 || shipGetAllWeapons == null || shipGetWeaponGroupFor == null
                || shipGetWeaponGroupsCopy == null) {
            return -1;
        }
        Object rawWeapons = invoke(shipGetAllWeapons, ship, "ShipAPI.getAllWeapons");
        if (!(rawWeapons instanceof List<?>)) {
            return -1;
        }
        List<?> weapons = (List<?>) rawWeapons;
        if (mountIndex >= weapons.size()) {
            return -1; // bounds miss (V4 parity fault or stale declaration) — legacy pairing
        }
        Object group = invoke(shipGetWeaponGroupFor, ship, "ShipAPI.getWeaponGroupFor",
                weapons.get(mountIndex));
        if (group == null) {
            return -1;
        }
        List<?> groups = weaponGroupsCopyOrNull(ship);
        return groups == null ? -1 : groups.indexOf(group);
    }
    /** The hull's current selected-group ordinal, or -1 (nothing selected / seams unbound). */
    int selectedGroupOrdinalOnShip(Object ship) {
        if (shipGetSelectedGroupAPI == null) {
            return -1;
        }
        List<?> groups = weaponGroupsCopyOrNull(ship);
        if (groups == null) {
            return -1;
        }
        return groups.indexOf(invoke(shipGetSelectedGroupAPI, ship, "ShipAPI.getSelectedGroupAPI"));
    }

    // ---- PCA helpers the moved bodies call by their old bare names (delegates through host, not copies) ----

    private Object invoke(Method m, Object target, String label, Object... args) {
        return host.invoke(m, target, label, args);
    }

    private Object shipForWireIdOrNull(long shipId) {
        return host.shipForWireIdOrNull(shipId);
    }

    private List<?> weaponGroupsCopyOrNull(Object ship) {
        return host.weaponGroupsCopyOrNull(ship);
    }

    private int weaponGroupOrdinalForMount(long shipId, int mountIndex) {
        return host.weaponGroupOrdinalForMount(shipId, mountIndex);
    }
}
