package cc.starsector.client;

import com.fs.starfarer.api.combat.DamagingProjectileAPI;

/**
 * The engine's own identity for "this damager is a blast, not a weapon discharge", and the client's
 * non-weapon-damager test built on it. Pure statics: no state, no engine-internal type.
 *
 * <p><b>Why a null weapon is no longer the test.</b> On the old engine a damaging explosion's weapon getter
 * returned null unconditionally, so "no weapon" read as "an engine blast".
 * The current engine keeps a weapon field on the explosion
 * and fills it with the LAUNCHER's weapon for a missile's
 * explosion-spec blast, a guided or proximity fuse burst (flak, devastator, fuse bombs) and the
 * rift-lightning mine's blast. A ship-death blast,
 * a blast spawned through the combat engine's own damaging-explosion call, and the blast of a mine minted
 * weaponless (the rift-beam mine: RiftBeamEffect 51 passes null) stay weaponless.
 * So a weapon-null test alone misreads every weapon-carrying blast
 * as a weapon discharge: an own burst would be declared as fire, a presented foe burst removed as a leak.
 *
 * <p><b>The identity.</b> {@link DamagingProjectileAPI#getExplosionSpecIfExplosion()} is non-null exactly for
 * a damaging explosion: public API, a default returning null that only the explosion class overrides, present
 * on both engines. A blast's projectile spec
 * id is still null, so a caller's spec-id split keeps
 * sorting a blast with the death explosions. An engine-internal type test would put a class name the mod
 * classloader refuses into this jar, so the identity is read through the API only.
 *
 * <p>Threads: any; the reads are the caller's (the combat thread's projectile scans and damage applies).
 *
 */
public final class EngineBlastLaw {

    private EngineBlastLaw() {
    }

    /**
     * Is {@code p} an engine blast (a damaging explosion)? A null is not. A throwing read is not, and
     * announces once: an unreadable body falls back to the weapon-only classification it had before this law.
     */
    public static boolean isEngineBlast(DamagingProjectileAPI p) {
        if (p == null) {
            return false;
        }
        try {
            return p.getExplosionSpecIfExplosion() != null;
        } catch (Throwable t) {
            FallbackAnnounce.announceOnce("engine-blast-unread",
                    "explosion-spec read threw " + t.getClass().getSimpleName() + "; the weapon test alone classifies");
            return false;
        }
    }

    /**
     * The pure core: a damager is NOT a weapon discharge when it has no weapon (a death blast, a null-weapon
     * mine or script spawn) OR it is an engine blast (which may carry its launcher's weapon).
     */
    public static boolean nonWeaponDamager(boolean weaponNull, boolean engineBlast) {
        return weaponNull || engineBlast;
    }

    /**
     * {@link #nonWeaponDamager(boolean, boolean)} over a live body. A null is no damager at all (false). The
     * weapon read is unguarded, as the weapon-null test it replaces was.
     */
    public static boolean nonWeaponDamager(DamagingProjectileAPI p) {
        return p != null && nonWeaponDamager(p.getWeapon() == null, isEngineBlast(p));
    }
}
