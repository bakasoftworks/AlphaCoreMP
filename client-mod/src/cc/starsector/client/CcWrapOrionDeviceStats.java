package cc.starsector.client;

import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.OrionDeviceStats;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import org.lwjgl.util.vector.Vector2f;

/**
 * The {@code orion_device} system script, installed by the
 * mod's {@code data/shipsystems/orion_device.system} (a one-key file, {@code id} + this
 * {@code statsScript}: the engine merges it onto the vanilla file key by key).
 * On a SERVED NON-OWN ship ({@link CcReplicaEffectGate#servedNonOwn} — a bound served
 * match, main thread, any ship but the local own one) the bomb is the server's: {@link #spawnBomb}
 * mints NOTHING (a local bomb would detonate a second DamagingExplosion and shove the replica the
 * wire already lunges) and remembers {@code (slot, time)}; the served body's sourceless adopt then
 * binds the FX-only mirror to THIS instance ({@link #bindServedBomb}), so the pusher-plate impulse
 * renders through the vanilla {@code apply} plate loop. Every other caller — the own ship, a
 * serverless window, the SERVER JVM (mod data loads there too) — takes {@code super}: byte-for-byte
 * vanilla. The engine's own system machine (IN/ACTIVE/OUT, effectLevel, useSound, cooldown) runs
 * untouched on every ship.
 */
public class CcWrapOrionDeviceStats extends OrionDeviceStats implements CcServedBombBinder {

    /** A detached per-spec DEFAULT binder ({@link CcServedBombMirror#defaultBinder}): FX + flash
     *  only — no ship renders its plate, so it never feeds one. */
    private final boolean detached;
    /** The remembered skip (slot id + engine seconds; NaN = none) — two scalars, bounded. */
    private String skipSlotId;
    private float skipAtSec = Float.NaN;

    public CcWrapOrionDeviceStats() {
        this(false);
    }

    CcWrapOrionDeviceStats(boolean detached) {
        this.detached = detached;
    }

    @Override
    public void spawnBomb(ShipAPI source, WeaponSlotAPI slot) {
        if (!CcReplicaEffectGate.servedNonOwn(source)) {
            super.spawnBomb(source, slot);
            return;
        }
        CcServedBombMirror.noteSpawnSkipped();
        skipSlotId = slot == null ? "-" : slot.getId();
        skipAtSec = CcServedBombMirror.engineSeconds();
    }

    @Override
    protected EveryFrameCombatPlugin createBombImpactPlugin(ShipAPI ship, WeaponSlotAPI launchSlot,
            MissileAPI bomb, Vector2f launchLoc, float launchAngle) {
        if (!CcReplicaEffectGate.servedNonOwn(ship)) {
            return super.createBombImpactPlugin(ship, launchSlot, bomb, launchLoc, launchAngle);
        }
        return CcServedBombMirror.plugin(p, pusherState, this::notifySpawnedExplosionParticles, ship,
                launchSlot, bomb);
    }

    @Override
    public String bindServedBomb(ShipAPI replica, MissileAPI bomb, Vector2f loc) {
        return CcServedBombMirror.bind(p, detached ? null : pusherState,
                this::notifySpawnedExplosionParticles, detached ? null : replica, bomb, loc);
    }

    @Override
    public float servedBombFadeIn() {
        return p.bombFadeInTime;
    }

    @Override
    public String lastSkip() {
        return CcServedBombMirror.skipToken(skipSlotId, skipAtSec, CcServedBombMirror.engineSeconds());
    }
}
