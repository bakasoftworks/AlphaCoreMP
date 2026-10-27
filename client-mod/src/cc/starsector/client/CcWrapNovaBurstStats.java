package cc.starsector.client;

import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.NovaBurstStats;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import org.lwjgl.util.vector.Vector2f;

/**
 * The {@code nova_burst} system script, installed by the
 * mod's {@code data/shipsystems/nova_burst.system}. The {@link CcWrapOrionDeviceStats} contract
 * exactly (see its class doc), over {@link NovaBurstStats}: the vanilla ctor's params, the
 * decorative {@code nova} beams ({@code advanceImpl}) and the flash ({@code
 * notifySpawnedExplosionParticles}) are inherited untouched. A sibling, not a subclass, of the
 * Orion wrapper (vanilla Nova extends vanilla Orion), so the shared body lives in
 * {@link CcServedBombMirror}.
 */
public class CcWrapNovaBurstStats extends NovaBurstStats implements CcServedBombBinder {

    /** See {@link CcWrapOrionDeviceStats}'s field of the same name. */
    private final boolean detached;
    private String skipSlotId;
    private float skipAtSec = Float.NaN;

    public CcWrapNovaBurstStats() {
        this(false);
    }

    CcWrapNovaBurstStats(boolean detached) {
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
