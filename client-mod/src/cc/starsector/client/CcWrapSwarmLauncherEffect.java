package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import com.fs.starfarer.api.impl.combat.threat.SwarmLauncherEffect;
import com.fs.starfarer.api.impl.combat.threat.ThreatShipConstructionScript;

/**
 * {@code swarm_launcher} — treated-replica EVERYFRAME wrapper over the vanilla
 * {@link SwarmLauncherEffect}, installed by {@link ClientEveryFrameEffectSwap}. Subclassing
 * keeps ALL THREE parent interfaces (OnFireEffectPlugin, EveryFrameWeaponEffectPlugin,
 * SwarmLaunchingWeapon — sibling launchers read the last one through
 * {@code getPreferredNumFragmentsToFireConsideringAllWeapons}). The audited LIVE door is
 * vanilla {@code advance()} L109 {@code setForceFireOneFrame(true)}: the effect commands its
 * OWN one-frame fire the moment the ship's roiling swarm holds ≥ FRAGMENT_NUM members, so the
 * context hold on replica weapons does NOT cover it — one uncaught frame mints a live 4-ship
 * {@code attack_swarm} ghost wing via {@code onFire}. The authoritative wing arrives as
 * server-replicated replica entities; the class has no standalone visual to preserve (audit).
 */
public class CcWrapSwarmLauncherEffect extends SwarmLauncherEffect {

    static final String TAG = "swarm_launcher";

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        ShipAPI ship = weapon == null ? null : weapon.getShip();
        if (!CcReplicaEffectGate.isTreatedReplica(ship)) {
            super.advance(amount, engine, weapon);
            return;
        }
        CcReplicaEffectGate.replicaFrame(TAG);
        // Replica branch: never the vanilla body (its force-fire IS the door). Hold the muzzle
        // inert (the VoltaicDischarge greyed-weapon residue class — cosmetic, entity-free) and
        // re-run the vanilla would-fire arithmetic READ-ONLY so doorShut counts the exact
        // frames vanilla would have dispatched the ghost wing mint.
        weapon.setForceDisabled(true);
        if (weapon.usesAmmo() && weapon.getAmmo() <= 0) {
            return; // vanilla's out-of-ammo no-op branch
        }
        this.elapsed += amount; // vanilla timing state — keeps the would-fire count honest
        if (this.elapsed < INITIAL_SPAWN_DELAY
                || ship.hasTag(ThreatShipConstructionScript.SHIP_UNDER_CONSTRUCTION)) {
            return; // vanilla takes its own setForceDisabled(true) else-branch here
        }
        // Vanilla's disable arithmetic under this wrapper's OWN state: the replica branch never
        // spawns a wing, so currWing stays null and waitUntilOneLeft stays false forever — the
        // wing-tracking clauses reduce to the two swarm-population gates below (both reads).
        RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(ship);
        int active = swarm == null ? 0 : swarm.getNumActiveMembers();
        Integer required = FRAGMENT_NUM.get(getWingId(weapon));
        boolean wouldFire = required != null && active >= required;
        if (wouldFire && swarm != null) {
            int preferred = getPreferredNumFragmentsToFireConsideringAllWeapons(ship);
            preferred = (int) Math.min((float) preferred,
                    swarm.getParams().baseMembersToMaintain * 0.9F);
            if (active < preferred) {
                wouldFire = false;
            }
        }
        if (wouldFire) {
            // Vanilla: setForceFireOneFrame(true) → local onFire → spawnShipOrWing. Shut.
            CcReplicaEffectGate.doorShut(TAG);
        }
    }

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        if (!CcReplicaEffectGate.isTreatedReplica(weapon == null ? null : weapon.getShip())) {
            super.onFire(projectile, weapon, engine);
            return;
        }
        // Second line of defense (unreachable while the advance() above never force-fires): an
        // EXTERNAL force-fire on a treated replica still mints no wing; vanilla's own last line
        // removes the trigger projectile — keep that so no ghost projectile lingers.
        CcReplicaEffectGate.doorShut(TAG);
        if (engine != null && projectile != null) {
            engine.removeEntity(projectile);
        }
    }
}
