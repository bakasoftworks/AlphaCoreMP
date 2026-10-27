package cc.starsector.client;

import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.loading.specs.BaseWeaponSpec;

/**
 * The client-mod's ONE non-API engine read: the weapon spec's
 * fire sound id, which {@link com.fs.starfarer.api.loading.WeaponSpecAPI} does not expose — the
 * API carries only the fire-sound FLAGS ({@code isStopPreviousFireSound()} etc.), never the id.
 *
 * <p><b>Descriptor-grade identity (verified against the real 0.98a-RC8
 * jar):</b> {@code com.fs.starfarer.loading.specs.BaseWeaponSpec} — the class all three
 * .wpn spec kinds extend (projectile, pulse and beam) and the
 * one that implements {@code WeaponSpecAPI} — whose readable bean getters
 * {@code getFireSoundTwo()}/{@code getFireSoundOne()} kept their names; they carry the .wpn
 * {@code fireSoundTwo}/{@code fireSoundOne} keys as loaded by {@code WeaponSpecLoader}. The read
 * is a plain virtual call on a compile-time link — NO {@code java.lang.reflect.*}, so the script
 * classloader-B blocklist ("File access and reflection are not allowed to
 * scripts") does not apply; the engine class resolves through the game classpath exactly like the
 * API impl classes already flowing through the mod. Fail-soft: ANY surprise (a future game
 * version renaming the class, a spec that is not a {@code BaseWeaponSpec}) returns {@code null}
 * — the caller counts + capped-logs a skip; nothing throws into the presentation path.
 *
 * <p><b>Per-shot semantics:</b> {@code fireSoundTwo} is the engine's per-discharge fire sound
 * (the engine's fire path resolves it per shot; {@code fireSoundOne} is the burst-start/charge
 * layer), so a WEAPON_DISCHARGE presentation plays Two and falls back to One only when Two is
 * absent. New engine-internal identities in the client-mod get this same adapter treatment — never inline
 * in a plugin.
 */
final class FireSoundAdapter {

    private FireSoundAdapter() {
    }

    /**
     * The weapon's per-shot fire sound id (a {@code sounds.json} key, e.g. {@code hellbore_fire}),
     * or {@code null} when the spec carries none or the engine-internal shape moved.
     */
    static String fireSoundId(WeaponAPI weapon) {
        try {
            Object spec = weapon != null ? weapon.getSpec() : null;
            if (spec instanceof BaseWeaponSpec) {
                BaseWeaponSpec base = (BaseWeaponSpec) spec;
                String two = base.getFireSoundTwo();
                if (two != null && !two.isEmpty()) {
                    return two;
                }
                String one = base.getFireSoundOne();
                return one != null && !one.isEmpty() ? one : null;
            }
            if (spec != null) {
                // A non-null spec that is not BaseWeaponSpec = engine-shape drift — the
                // fire-sound feature silently vanishes run-wide (a null spec is normal absence).
                FallbackAnnounce.announceOnce("x10-fire-sound-shape-moved",
                        "weapon spec is " + spec.getClass().getName() + ", not BaseWeaponSpec");
            }
            return null;
        } catch (Throwable t) {
            FallbackAnnounce.announceOnce("x10-fire-sound-shape-moved",
                    "fire-sound read threw " + t.getClass().getSimpleName());
            return null;
        }
    }
}
