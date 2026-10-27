package cc.starsector.client;

import java.awt.Color;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.impl.combat.NegativeExplosionVisual;
import com.fs.starfarer.api.impl.combat.RiftCascadeEffect;
import com.fs.starfarer.api.impl.combat.RiftCascadeMineExplosion;
import com.fs.starfarer.api.impl.combat.RiftLanceEffect;
import com.fs.starfarer.api.impl.combat.RiftTrailEffect;
import com.fs.starfarer.api.impl.combat.dweller.AssayingRiftEffect;
import com.fs.starfarer.api.impl.combat.dweller.DwellerShroud;
import com.fs.starfarer.api.input.InputEventAPI;
import org.lwjgl.util.vector.Vector2f;

/**
 * A served Hungering Rift showed "bare bright red diamonds"
 * (vanilla = diamonds with soft red glow trails), so this is the treated-replica wrapper over the Shrouded
 * {@link AssayingRiftEffect} ({@code assaying_rift}). Non-replica sources ride {@code super}
 * unchanged; {@code advance} is inherited (its {@link AssayingRiftEffect#MAX_RIFTS} force-disable
 * is inert on dweller hulls).
 *
 * <p><b>Why:</b> the glow trail is a {@link RiftTrailEffect} plugin the parent registers ONLY in
 * its {@code onFire}; presented replica missiles are minted by {@code spawnProjectile}, which
 * never runs it. One class, two slots:
 * the everyFrame row relays the presented fire, the ON_HIT row makes the
 * {@code .proj}'s fresh-per-projectile {@code onHit} ours.
 *
 * <p>Both replica branches are rewritten from the
 * functional spec. Replica {@code onFire} = the engine's own trail
 * class with the shroud look ({@link ShroudRiftTrail}) + the missile attributes the server's
 * missile carries; the trail mints the rift bloom once, the first frame the missile is removed,
 * expired or out of play without having done damage ({@link #expiryBloomDue}, widened: on a
 * puppet twin that is the wire end; a local prediction hit sets {@code didDamage} first, so it
 * never doubles). Door SHUT: the parent's local proximity fuse — a pose-driven twin must never
 * explode itself. Replica {@code onHit} ({@link #replicaOnHit}) = the parent's rift visual + sound
 * with its shield-hit flag forced true, which keeps the shroud-ally HEAL search ({@code setHitpoints}
 * on a wire-reconciled body — the cluster-2 H4 row) shut; the heal stays server-authoritative and
 * reaches the client as hull on the wire. Zero wire bytes, zero server compute.
 */
public class CcWrapAssayingRiftEffect extends AssayingRiftEffect
        implements ReplicaPresentedFireRelay.PresentedFireAware {

    static final String TAG = "assayingrift";

    /** The trail's base particle life, seconds (spec S19 look hook). */
    private static final float TRAIL_PARTICLE_LIFE_S = 1.5f;
    /** EMP resistance the presented missile carries (spec S19; the server's missile has the same). */
    private static final int REPLICA_EMP_RESISTANCE = 1000;
    /** ECCM chance override the presented missile carries (spec S19). */
    private static final float REPLICA_ECCM_CHANCE_OVERRIDE = 1f;
    /** The float argument of {@code createStandardRiftParams} on the weaponless rift (spec S19 (b)). */
    private static final float NO_WEAPON_RIFT_SIZE = 15f;
    /** The rift's impact sound id (spec S19). */
    private static final String RIFT_EXPLOSION_SOUND = "assaying_rift_explosion";

    /**
     * Rewritten from the functional spec.
     * A source that is not a treated replica (the birth latch,
     * {@link ReplicaPresentedFireRelay#latchAtFire}) takes the parent's {@code onFire} unchanged. A
     * replica shot counts its census frame and never runs the parent: on a presented MISSILE the
     * parent's local proximity fuse is counted {@code doorShut} (a pose-driven twin must never
     * detonate itself), a {@link ShroudRiftTrail} is registered as a combat plugin (counted
     * {@code visualMinted}) and the missile gets the attributes the server's missile carries: EMP
     * resistance 1000, ECCM chance override 1, the {@link AssayingRiftEffect#ASSAYING_RIFT} tag. A
     * non-missile replica shot (or no engine) gets nothing more.
     */
    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, final CombatEngineAPI engine) {
        if (!ReplicaPresentedFireRelay.latchAtFire(projectile)) {
            super.onFire(projectile, weapon, engine);
            return;
        }
        CcReplicaEffectGate.replicaFrame(TAG);
        if (!(projectile instanceof MissileAPI) || engine == null) {
            return;
        }
        MissileAPI missile = (MissileAPI) projectile;
        CcReplicaEffectGate.doorShut(TAG); // the parent's local proximity fuse, never built on a twin
        engine.addPlugin(new ShroudRiftTrail(missile, engine));
        missile.setEmpResistance(REPLICA_EMP_RESISTANCE);
        missile.setEccmChanceOverride(REPLICA_ECCM_CHANCE_OVERRIDE);
        missile.addTag(ASSAYING_RIFT);
        CcReplicaEffectGate.visualMinted(TAG);
    }

    /**
     * Rewritten from the functional spec.
     * A hit whose shot is not a replica shot ({@link ReplicaPresentedFireRelay#onHitReplica}:
     * the birth latch, else the source's treatment; a weaponless body is always one) takes the parent's
     * {@code onHit} unchanged; a replica hit takes {@link #replicaOnHit}.
     */
    @Override
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point,
            boolean shieldHit, ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        if (!ReplicaPresentedFireRelay.onHitReplica(projectile)) {
            super.onHit(projectile, target, point, shieldHit, damageResult, engine);
            return;
        }
        replicaOnHit(projectile, target, point, shieldHit, damageResult, engine);
    }

    /**
     * The replica {@code onHit}, rewritten from the functional
     * spec. Census: a non-shield hit on a non-hulk ship counts
     * {@code doorShut} ({@link #healDoorReached}: that is where the parent's shroud-ally heal search
     * would run). With a weapon, the parent's {@code onHit} runs with its shield-hit flag forced TRUE —
     * the flag gates only that heal search, so the rift look and sound stay and no hull is written on
     * the client. A weaponless body (the parent reads the weapon unchecked) takes our own rift instead,
     * counted {@code visualMinted}: {@link RiftCascadeEffect#STANDARD_RIFT_COLOR}, standard rift params
     * with fade-out 1 and hit-glow size x1 spawned on the projectile, and the rift sound at
     * {@code point} with the target's velocity (zero without a target).
     */
    private void replicaOnHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point,
            boolean shieldHit, ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        boolean ship = target instanceof ShipAPI;
        if (healDoorReached(shieldHit, ship, ship && ((ShipAPI) target).isHulk())) {
            CcReplicaEffectGate.doorShut(TAG);
        }
        if (projectile.getWeapon() != null) {
            super.onHit(projectile, target, point, true, damageResult, engine);
            return;
        }
        NegativeExplosionVisual.NEParams params = RiftCascadeMineExplosion.createStandardRiftParams(
                RiftCascadeEffect.STANDARD_RIFT_COLOR, NO_WEAPON_RIFT_SIZE);
        params.fadeOut = 1f;
        params.hitGlowSizeMult = 1f;
        RiftCascadeMineExplosion.spawnStandardRift(projectile, params);
        Vector2f velocity = target == null ? new Vector2f() : target.getVelocity();
        Global.getSoundPlayer().playSound(RIFT_EXPLOSION_SOUND, 1f, 1f, point, velocity);
        CcReplicaEffectGate.visualMinted(TAG);
    }

    /**
     * The heal-door census pick, pure (game-free row
     * {@code ApiRwD2Rows}): the parent's shroud-ally heal search would run on a hit that is NOT on
     * shields and lands on a ship that is not a hulk.
     */
    static boolean healDoorReached(boolean shieldHit, boolean targetIsShip, boolean targetIsHulk) {
        return !shieldHit && targetIsShip && !targetIsHulk;
    }

    /**
     * The expiry-bloom pick, pure (game-free row
     * {@code ApiRwD2Rows}): bloom iff not bloomed yet, the missile has done no damage, and it is
     * removed, expired or out of play. Wider than a removal-only check on purpose: on a
     * puppet twin the wire end can leave the body out of play before it is removed; a local hit sets
     * {@code didDamage} first, so a hit never blooms twice.
     */
    static boolean expiryBloomDue(boolean alreadyBloomed, boolean didDamage, boolean removed,
            boolean expired, boolean inPlay) {
        return !alreadyBloomed && !didDamage && (removed || expired || !inPlay);
    }

    /**
     * The replica glow trail, rewritten from the functional spec:
     * the engine's own {@link RiftTrailEffect} on the presented missile
     * with no loop sound and three look hooks (undercolour {@link DwellerShroud#SHROUD_COLOR}, darkening
     * {@link RiftLanceEffect#getColorForDarkening} of that undercolour, base particle life 1.5 s). Each
     * frame, before the trail's own advance, it mints the expiry bloom ({@link #expiryBloomDue}) once:
     * {@link #replicaOnHit} with no target at the missile's location.
     */
    private final class ShroudRiftTrail extends RiftTrailEffect {

        private final MissileAPI rift;
        private final CombatEngineAPI engine;
        private boolean bloomed;

        ShroudRiftTrail(MissileAPI rift, CombatEngineAPI engine) {
            super(rift, null);
            this.rift = rift;
            this.engine = engine;
        }

        @Override
        protected Color getUndercolor() {
            return DwellerShroud.SHROUD_COLOR;
        }

        @Override
        protected Color getDarkeningColor() {
            return RiftLanceEffect.getColorForDarkening(getUndercolor());
        }

        @Override
        protected float getBaseParticleDuration() {
            return TRAIL_PARTICLE_LIFE_S;
        }

        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            if (expiryBloomDue(bloomed, rift.didDamage(), rift.wasRemoved(), rift.isExpired(),
                    engine.isEntityInPlay(rift))) {
                bloomed = true;
                replicaOnHit(rift, null, new Vector2f(rift.getLocation()), false, null, engine);
            }
            super.advance(amount, events);
        }
    }
}
