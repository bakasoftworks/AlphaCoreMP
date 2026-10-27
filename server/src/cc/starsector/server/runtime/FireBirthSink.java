package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The STATIC birth sink the product onFire emitter
 * ({@code cc.starsector.server.fire.CcOnFireEmitter}, probe-api unit) confesses into. The engine
 * dispatches {@code onFire} on the serve/advance thread (the dispatch runs
 * inside the engine step this loop drives), so everything here is single-threaded by
 * construction — plain fields, no locks. Holds ship references as {@code Object} only:
 * {@code server/src} stays game-jar-free.
 *
 * <p>Statics are shared between the emitter's ScriptStore-resolved instances and this class
 * because the janino loader parent-delegates project names to the app classpath — the
 * probe's proven same-Class fact, re-gated per run by the {@link OnFireAttachSweep} resolvable
 * pre-check. {@link #enabled()} latches true only on a successful sweep; everything else is a
 * cheap no-op while disabled (a JVM that never sweeps never pays).
 *
 * <p>The delegation table backs the sweep's wrap-don't-replace lane: the ~2 vanilla-onFire
 * projectile specs keep their original plugin BEHAVIOR by having the emitter chain-call a fresh
 * original instance per wrapper (preserving the engine's one-instance-per-weapon semantics);
 * the table maps projectile-spec identity → original class name, recorded only after the sweep
 * test-constructed the original successfully.
 */
public final class FireBirthSink {

    /** One confessed discharge: the firing engine ship ref (raw {@code Object}), the RAW
     *  positional mount ordinal ({@code getAllWeapons().indexOf}, −1 = unresolvable), the
     *  weapon id, the birth kinematics captured AT the callback (aim = the mount's
     *  {@code getCurrAngle()}, bearing = the projectile's own final velocity line — set before
     *  the engine dispatches onFire — or, when that read is degenerate, the emitter's composed
     *  {@code shooterVel + speed·û(mount ray)} line (zero-velocity MovingRay
     *  births; {@link #birthsComposed()} counts); {@code NaN} = fail-soft, never a faked
     *  value), the birth-velocity magnitude {@code birthSpeed} (GPEE v6:
     *  measured {@code hypot(projectile velocity)} on the direct branch, composed
     *  {@code |shooterVel + speed·û(ray)|} on the fallback branch; {@code NaN} = fail-soft),
     *  the birth position {@code muzzleX}/{@code muzzleY} (GPEE v8,
     * measured {@code projectile.getLocation()}
     *  at the callback — the engine's true spawn point; both-finite-or-both-{@code NaN},
     *  {@code NaN} pair = fail-soft), the sim tick stamped from {@link #beginTick}, and
     *  {@code tier}: which line the bearing/speed
     *  rode — {@link BearingTier#DIRECT} the projectile's own velocity, {@link BearingTier#FACING}
     *  the zero-velocity ray's birth facing (the jittered from→to segment) composed with the
     *  shooter velocity, {@link BearingTier#COMPOSED} the un-jittered mount ray — per-birth twin
     *  of the {@link #birthsComposed()} / {@link #birthsFacing()} counters, read by the
     *  {@link OnFireTruthMerger} source tag → {@link GameplayEdgeEventWatchSink} (never on
     *  the wire). */
    public record FireBirth(Object ship, int mountIndex, String weaponId,
            float aimFacingDeg, float flightBearingDeg, float birthSpeed,
            float muzzleX, float muzzleY, long simTick, BearingTier tier) {
        /** Legacy twin of the tier: true iff the un-jittered mount-ray fallback served. */
        public boolean composed() {
            return tier == BearingTier.COMPOSED;
        }
    }

    /** The bearing source of one birth (the {@code tier} label the GPEE watch ndjson carries). */
    public enum BearingTier {
        /** The projectile's own birth velocity line (carries the engine's per-shot draw). */
        DIRECT("birth_direct"),
        /** A zero-velocity ray's birth {@code getFacing()} — the jittered spawn
         *  angle the factory laid the from→to segment along — composed with the shooter
         *  velocity: the engine's own flight line for the MovingRay class, spread INCLUDED. */
        FACING("birth_facing"),
        /** {@code shooterVel + speed·û(mount ray)} — NO per-shot jitter; the
         *  fallback when neither the velocity nor the facing reads. */
        COMPOSED("birth_composed");

        private final String label;

        BearingTier(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** One pose-lane-OWNED discharge (shot-fidelity belt 1):
     *  the emitter saw a guided/flare/engine-less (free-fall bomb) missile birth on this mount —
     *  ordnance whose flight the pose lanes own, so the declared per-shot serve gains ~nothing.
     *  The serve loop drains these every tick and demotes routed class-S mounts to held
     *  (server seam of record: the confession lane — the enroll seams are client-only). */
    public record PoseOwnedBirth(Object ship, int mountIndex) {}

    /** Bounded belt-1 pending cap: demotes are per-mount one-shots, so a tick can never need
     *  more distinct rows than a hull has mounts; overflow drops silently (the next birth from
     *  the same mount re-confesses — the belt self-heals). */
    private static final int POSE_OWNED_PENDING_CAP = 64;

    private static boolean enabled;
    private static long currentTick = -1L;
    private static final List<FireBirth> pending = new ArrayList<>();
    private static final List<PoseOwnedBirth> pendingPoseOwned = new ArrayList<>();
    private static final Map<Object, String> delegationByProjSpec = new IdentityHashMap<>();
    private static final Set<String> coveredWeaponIds = new HashSet<>();
    private static final Set<String> missileSpecCoveredWeaponIds = new HashSet<>();
    private static final Set<String> missileSpecWeaponIds = new HashSet<>();
    private static final Set<String> composedMissileReceipted = new HashSet<>();
    private static long births;
    private static long birthsComposed;
    private static long birthsFacing;
    private static long aimFacings;
    private static long aimFallbacks;
    private static final Set<String> aimFallbackReceipted = new HashSet<>();
    private static long instances;
    private static long cbErrors;
    private static long delegated;
    private static long delegErrors;

    private FireBirthSink() {
    }

    /** Latched by a SUCCESSFUL {@link OnFireAttachSweep} only — the one writer. */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** Whether the sweep installed the emitter this JVM (false = every record is a no-op). */
    public static boolean enabled() {
        return enabled;
    }

    /** Stamp the tick the NEXT engine advance's births belong to (the serve loop calls this
     *  immediately before {@code publishNextTick()}). */
    public static void beginTick(long simTick) {
        if (!enabled) {
            return;
        }
        currentTick = simTick;
    }

    /** Confess one discharge (emitter callback; never throws — the emitter guards).
     *  Direct-branch shape ({@code composed=false}) — the contract rows' caller. */
    public static void record(Object ship, int mountIndex, String weaponId,
            float aimFacingDeg, float flightBearingDeg, float birthSpeed,
            float muzzleX, float muzzleY) {
        record(ship, mountIndex, weaponId, aimFacingDeg, flightBearingDeg, birthSpeed,
                muzzleX, muzzleY, false);
    }

    /** Confess one discharge with its bearing-source tier ({@code composed} = the emitter's
     *  mount-ray fallback branch) — the boolean twin of
     *  {@link #record(Object, int, String, float, float, float, float, float, BearingTier)}. */
    public static void record(Object ship, int mountIndex, String weaponId,
            float aimFacingDeg, float flightBearingDeg, float birthSpeed,
            float muzzleX, float muzzleY, boolean composed) {
        record(ship, mountIndex, weaponId, aimFacingDeg, flightBearingDeg, birthSpeed,
                muzzleX, muzzleY, composed ? BearingTier.COMPOSED : BearingTier.DIRECT);
    }

    /** Confess one discharge with its bearing-source {@link BearingTier} — the emitter's caller
     *  (the ray class now rides {@link BearingTier#FACING}). */
    public static void record(Object ship, int mountIndex, String weaponId,
            float aimFacingDeg, float flightBearingDeg, float birthSpeed,
            float muzzleX, float muzzleY, BearingTier tier) {
        if (!enabled) {
            return;
        }
        births++;
        pending.add(new FireBirth(ship, mountIndex, weaponId, aimFacingDeg, flightBearingDeg,
                birthSpeed, muzzleX, muzzleY, currentTick,
                tier == null ? BearingTier.DIRECT : tier));
    }

    /** Confess one pose-lane-OWNED birth (shot-fidelity belt 1; emitter callback — never throws, the
     *  emitter guards). Enabled-gated and bounded like every sink lane. */
    public static void recordPoseOwned(Object ship, int mountIndex) {
        if (!enabled || pendingPoseOwned.size() >= POSE_OWNED_PENDING_CAP) {
            return;
        }
        pendingPoseOwned.add(new PoseOwnedBirth(ship, mountIndex));
    }

    /** Drain (remove + return) ALL pending pose-owned births — the serve loop calls this every
     *  enabled tick (routing on or off: an undrained list would pin dead engine ship refs across
     *  rounds) and applies belt-1 demotes only while class routing is active. */
    public static List<PoseOwnedBirth> drainPoseOwned() {
        if (pendingPoseOwned.isEmpty()) {
            return List.of();
        }
        List<PoseOwnedBirth> drained = new ArrayList<>(pendingPoseOwned);
        pendingPoseOwned.clear();
        return drained;
    }

    /** Drain (remove + return) every pending birth stamped {@code <= simTick}, returned in
     *  record order — the {@link OnFireTruthMerger} first-birth-wins multi-pellet collapse
     *  keys on it. */
    public static List<FireBirth> drainUpTo(long simTick) {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<FireBirth> drained = new ArrayList<>();
        for (var it = pending.iterator(); it.hasNext(); ) {
            FireBirth birth = it.next();
            if (birth.simTick() <= simTick) {
                drained.add(birth);
                it.remove();
            }
        }
        return drained;
    }

    /**
     * Emitter ctor census (one per weapon built from an emitter-bearing spec). JVM-lifetime
     * cumulative — never reset between rounds, so the instances()==covered-mounts identity
     * the npb evidence reads is ROUND-0-scoped; multi-round consumers
     * diff per-round deltas before asserting.
     */
    public static void instanceBuilt() {
        instances++;
    }

    /** A throwable swallowed inside the emitter's own callback body (verifier asserts 0). */
    public static void cbError() {
        cbErrors++;
    }

    /** A wrap-lane delegation failure — resolve, construct, or chained call (verifier asserts 0). */
    public static void delegError() {
        delegErrors++;
    }

    /** Sweep-side: record that {@code projSpec} (identity key) wraps {@code originalClassName}
     *  — called only AFTER the sweep test-constructed the original successfully. */
    public static void registerDelegation(Object projSpec, String originalClassName) {
        delegationByProjSpec.put(projSpec, originalClassName);
    }

    /**
     * Sweep-side: record a weapon spec id ({@code WeaponSpecAPI.getWeaponId()} — the SAME string
     * the mount tier's {@code WeaponMountView.weaponId()} carries; every engine weapon impl's
     * {@code getId()} delegates to its spec's {@code getWeaponId()}, verified in the engine's own
     * classes) whose projectile OR missile spec ended up carrying the emitter (the missile
     * branch was added later; the old uncovered class is now spec-less weapons only, i.e.
     * beams). Mounts of an UNCOVERED weapon can never confess; the shadow comparator excludes
     * their detector events from agreement.
     */
    public static void registerCovered(String weaponId) {
        if (weaponId != null) {
            coveredWeaponIds.add(weaponId);
        }
    }

    /** Sweep-side: additionally mark a covered weapon id as landed through the MISSILE-spec
     *  branch — kind visibility only, always paired with
     *  {@link #registerCovered}. Consumers: the composed-fallback one-shot receipt
     *  ({@link #birthComposed}) and the merger's det-fallback log flag. */
    public static void registerMissileSpecCovered(String weaponId) {
        if (weaponId != null) {
            missileSpecCoveredWeaponIds.add(weaponId);
        }
    }

    /** Whether {@code weaponId} is covered through the MISSILE-spec branch. Gated like
     *  {@link #coveredWeaponIds()}: always false until a successful sweep
     *  latches {@link #enabled()}, so a half-swept set is never visible. */
    public static boolean isMissileSpecCovered(String weaponId) {
        return enabled && weaponId != null && missileSpecCoveredWeaponIds.contains(weaponId);
    }

    /** Sweep-side: mark EVERY MISSILE-spec weapon id the sweep walked,
     *  landed or not — the KIND, not coverage. WHY: a wrap-skipped missile spec is uncovered, so
     *  the poll path is its only emitter, and the detector must still know it fires missiles
     *  when no fresh spawn matched (its counted aim fallback). */
    public static void registerMissileSpec(String weaponId) {
        if (weaponId != null) {
            missileSpecWeaponIds.add(weaponId);
        }
    }

    /** Whether {@code weaponId} fires a MISSILE spec (see {@link #registerMissileSpec}); gated on
     *  {@link #enabled()} like the other sets (a half-swept set is never visible), so a knob-off
     *  serve reads false for every id (its no-spawn missile discharges stay uncounted). */
    public static boolean isMissileSpec(String weaponId) {
        return enabled && weaponId != null && missileSpecWeaponIds.contains(weaponId);
    }

    /** Read-only view of the covered weapon-spec-id set (comparator-side exclusion). Empty
     *  unless {@link #enabled()}: a partial attach sweep may register covered ids and then fail
     *  the latch — consumers must see covered-empty ⇒ identity, never a half-swept set.
     * */
    public static Set<String> coveredWeaponIds() {
        if (!enabled) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(coveredWeaponIds);
    }

    /**
     * Emitter-side lazy resolve: a FRESH instance of the original plugin wrapped for
     * {@code projSpec}, or {@code null} when the spec wraps nothing. Fresh-per-call preserves
     * the engine's per-weapon instance semantics (each wrapper instance resolves once and keeps
     * its own delegate). Construction failure counts {@link #delegError()} and returns
     * {@code null} — loud counter, never a throw.
     */
    public static Object newDelegateOrNull(Object projSpec) {
        String className = delegationByProjSpec.get(projSpec);
        if (className == null) {
            return null;
        }
        try {
            Object delegate = Class.forName(className, true, FireBirthSink.class.getClassLoader())
                    .getDeclaredConstructor().newInstance();
            delegated++;
            return delegate;
        } catch (Throwable t) {
            delegErrors++;
            return null;
        }
    }

    /** Raw confessed-birth count (multi-pellet volleys count each pellet). */
    public static long births() {
        return births;
    }

    /** Births whose bearing came from the emitter's composed fallback rather than the
     *  projectile's own velocity line (the zero-velocity MovingRay class);
     *  JVM-lifetime, like {@link #births()}. For a MISSILE-spec
     *  weapon the composed line's {@code getProjectileSpeed()} term is the spec's MAX speed,
     *  not the launch speed (missiles accelerate; the direct velocity read IS the launch line
     *  and needs no flag) — that degrade prints a one-shot per-weapon receipt so it can never
     *  pass silently. */
    public static void birthComposed(String weaponId) {
        birthsComposed++;
        missileMaxSpeedReceipt(weaponId, "composed-fallback");
    }

    /** Composed-fallback birth count (bearing-source visibility — the silent-degrade guard). */
    public static long birthsComposed() {
        return birthsComposed;
    }

    /** A birth whose bearing came from the zero-velocity ray's birth FACING (the
     *  jittered spawn angle) composed with the shooter velocity — the engine's own flight line
     *  for the MovingRay class, spread included. Same MAX-not-launch speed caveat as
     *  {@link #birthComposed} for a missile spec (same one-shot receipt). JVM-lifetime. */
    public static void birthFacing(String weaponId) {
        birthsFacing++;
        missileMaxSpeedReceipt(weaponId, "facing-composed");
    }

    /** Facing-composed birth count (a ray-class serve that regresses to
     *  {@link #birthsComposed()} = the spread-free wire is back — diagnosable from the round line). */
    public static long birthsFacing() {
        return birthsFacing;
    }

    /** A MISSILE discharge whose wire
     *  {@code aimFacingDeg} carried its birth facing ({@link LaunchFacingLaw#pick}). JVM-lifetime,
     *  like {@link #birthsFacing()}; round-line field {@code onfire_aim_facing}. */
    public static void aimFacing() {
        aimFacings++;
    }

    /** Missile discharges that carried the birth facing (see {@link #aimFacing()}). */
    public static long aimFacings() {
        return aimFacings;
    }

    /** A MISSILE discharge whose facing was unreadable,
     *  so the wire fell back to the mount angle ({@link LaunchFacingLaw#fallback}) — the remote
     *  view then launches without the barrel offset. Counted (round-line field
     *  {@code onfire_aim_fallback}, the done-when (c) instrument) + a one-shot per-weapon receipt
     *  so the degrade can never pass silently. JVM-lifetime. */
    public static void aimFallback(String weaponId) {
        aimFallbacks++;
        if (aimFallbackReceipted.add(weaponId)) {
            System.out.println("onfire-aim-fallback weapon=" + weaponId
                    + " (missile birth facing unreadable — aimFacingDeg fell back to the mount"
                    + " angle; one-shot receipt)");
        }
    }

    /** Missile discharges whose aim fell back to the mount (must stay 0 on a healthy serve). */
    public static long aimFallbacks() {
        return aimFallbacks;
    }

    private static void missileMaxSpeedReceipt(String weaponId, String branch) {
        if (isMissileSpecCovered(weaponId) && composedMissileReceipted.add(weaponId)) {
            System.out.println("onfire-composed-missile weapon=" + weaponId
                    + " (" + branch + " bearing used MAX-not-launch projectile speed for a"
                    + " missile spec — one-shot receipt)");
        }
    }

    /** Emitter instances constructed (weapon-build census). */
    public static long instances() {
        return instances;
    }

    /** Swallowed emitter-body throwables (must stay 0). */
    public static long cbErrors() {
        return cbErrors;
    }

    /** Successful wrap-lane delegate constructions. */
    public static long delegated() {
        return delegated;
    }

    /** Wrap-lane delegation failures (must stay 0). */
    public static long delegErrors() {
        return delegErrors;
    }
}
