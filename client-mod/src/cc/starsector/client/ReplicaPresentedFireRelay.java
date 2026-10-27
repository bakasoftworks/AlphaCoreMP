package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import com.fs.starfarer.api.util.WeightedRandomPicker;

/**
 * The presented-fire {@code onFire} relay for OUR replica
 * effect wrappers. A wire-presented replica shot is minted by
 * {@code AuthoritativePresenter} through {@code CombatEngineAPI.spawnProjectile}, which never
 * runs the weapon's {@code OnFireEffectPlugin} (only the weapon's own fire does);
 * {@code onHit} DOES run on a local hit
 * (projectile, missile and ray alike).
 * So an effect whose visual keys off {@code onFire}/range-expiry (Rift Lightning registers the
 * bolt at fire time and arcs when it hits OR outranges; the voltaic discharge arcs AT fire) never
 * presented on a replica — the reported "no EMP arc" class.
 *
 * <p><b>Only OUR wrappers, only on a treated replica.</b> A vanilla {@code onFire} on a
 * presented shot is exactly the sim-door class (mines, damaging arcs, ghost wings),
 * so the relay refuses anything that is not a {@link PresentedFireAware} wrapper and any
 * shooter that is not {@code CcReplicaEffectGate.isTreatedReplica}. Own-adopt shots never reach
 * it (the presenter gates the call on {@code !ownAdopt}); the own ship's weapons fire natively
 * and run the vanilla effect — no double arc.
 */
final class ReplicaPresentedFireRelay {

    /** Marker for a {@code CcWrap*} wrapper whose replica branch wants the presented-fire relay. */
    interface PresentedFireAware {
    }

    private static long relayed;
    private static long threw;

    private ReplicaPresentedFireRelay() {
    }

    static void onPresented(CombatEntityAPI spawned, WeaponAPI weapon, ShipAPI shooter, CombatEngineAPI engine) {
        try {
            if (!(spawned instanceof DamagingProjectileAPI) || weapon == null) {
                return;
            }
            EveryFrameWeaponEffectPlugin fx = weapon.getEffectPlugin();
            if (!(fx instanceof PresentedFireAware) || !(fx instanceof OnFireEffectPlugin)) {
                return;
            }
            if (!CcReplicaEffectGate.isTreatedReplica(shooter)) {
                return;
            }
            ((OnFireEffectPlugin) fx).onFire((DamagingProjectileAPI) spawned, weapon, engine);
            relayed++;
        } catch (Throwable t) {
            threw++;
            if (threw == 1) {
                PilotWatchLog.error("predict replica presented-fire relay threw (visual only; shot stands)", t);
            }
        }
    }

    static String meterTokens() {
        return " predict_presented_fire_relayed=" + relayed + " predict_presented_fire_relay_throws=" + threw;
    }

    // ---- engine faces of FragmentCarrierPresentLaw for the CcWrap*Fragment wrappers ----

    /** Is this shot's SOURCE a treated replica right now? Birth-time input only — later reads go
     *  through {@link #latched}/{@link #onHitReplica} ({@link ReplicaEffectLatchLaw}). */
    static boolean replicaShot(DamagingProjectileAPI projectile) {
        ShipAPI source = projectile == null ? null : projectile.getSource();
        return CcReplicaEffectGate.isTreatedReplica(source);
    }

    /** Decide replica-ness ONCE at the shot's birth and write it onto the shot
     *  ({@link ReplicaEffectLatchLaw#BOLT_KEY}); returns the decision. */
    static boolean latchAtFire(DamagingProjectileAPI projectile) {
        boolean replica = replicaShot(projectile);
        if (replica && projectile != null) {
            projectile.setCustomData(ReplicaEffectLatchLaw.BOLT_KEY, Boolean.TRUE);
        }
        return replica;
    }

    /** Was this shot born a replica shot (the birth latch), regardless of what the ledger says now? */
    static boolean latched(DamagingProjectileAPI projectile) {
        if (projectile == null) {
            return false;
        }
        java.util.Map<String, Object> data = projectile.getCustomData();
        return data != null && ReplicaEffectLatchLaw.boltReplica(data.get(ReplicaEffectLatchLaw.BOLT_KEY));
    }

    /** The {@code onHit} decision on a fresh-per-projectile instance: latch first,
     *  source treatment as the fallback, never an instance field. */
    static boolean onHitReplica(DamagingProjectileAPI projectile) {
        // A WEAPONLESS body is never a local weapon fire — only a
        // spawnProjectile mint with a null weapon (our sourceless presence-adopt) makes one, and
        // vanilla's onHit scripts deref getWeapon() (the null deref was a
        // Fatal). Replica branch, unconditionally.
        if (projectile != null && projectile.getWeapon() == null) {
            return true;
        }
        return ReplicaEffectLatchLaw.onHitReplica(latched(projectile), replicaShot(projectile));
    }

    /**
     * The fragment wrappers' replica-branch admission: counts the frame; {@code true} iff the
     * source swarm can seed a fragment ({@link FragmentCarrierPresentLaw#presentable}). A
     * no-swarm / empty-swarm carrier is left as-is (counted doorShut — the vanilla body would
     * have {@code removeEntity}'d the wire-minted missile).
     */
    static boolean beginReplicaShot(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        CcReplicaEffectGate.replicaFrame(FragmentCarrierPresentLaw.TAG);
        ShipAPI source = projectile == null ? null : projectile.getSource();
        if (source == null || weapon == null || engine == null) {
            return false;
        }
        RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(source);
        boolean ok = FragmentCarrierPresentLaw.presentable(true, swarm != null,
                swarm == null ? 0 : swarm.getNumActiveMembers());
        if (!ok) {
            CcReplicaEffectGate.doorShut(FragmentCarrierPresentLaw.TAG);
        }
        return ok;
    }

    /** Fallback pick when vanilla's range-filtered pick found nothing: any active member. */
    static RoilingSwarmEffect.SwarmMember anyMember(RoilingSwarmEffect swarm) {
        if (swarm == null) {
            return null;
        }
        WeightedRandomPicker<RoilingSwarmEffect.SwarmMember> picker = swarm.getPicker(true, true);
        return picker == null || picker.isEmpty() ? null : picker.pick();
    }
}
