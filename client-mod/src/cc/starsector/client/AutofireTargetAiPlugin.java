package cc.starsector.client;

import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.lwjgl.util.vector.Vector2f;

/**
 * The autofire designation-bracket wrapper: a delegating
 * {@link AutofireAIPlugin} whose ONLY intervention is bracketing the vanilla delegate's
 * {@link #advance} in a per-mount {@code ShipAPI.setShipTarget} save/set/restore, so the
 * delegate adopts the SERVER's target pick through its own designation-honor branch
 * (foe-check + feasibility gate, adoption via
 * its private setter on vanilla cadence). Fire/hold logic, lead computation and every
 * other behavior stay the delegate's — the fix-target law: an infeasible wire target
 * falls back to the vanilla search, vanilla's own property; nothing above the vanilla
 * reference is added.
 *
 * <p>Why THIS seam: overriding {@code getTargetShip()} is inert — the
 * engine driver ({@code WeaponGroup.advanceAuto}) aims the weapon off the
 * plugin's PRIVATE lead vector ({@code getTarget()}), and a {@code getTarget()} override
 * decoheres aim from the delegate's own {@code shouldFire()} grading. The designation
 * seam is the one public door the vanilla pick already honors. The bracket nests
 * consistently inside the engine's own per-group save/restore
 * ({@code advanceAuto}), and the HUD reads between frames — the player's
 * real designation stays visible.
 *
 * <p>Installed by {@link CcClientModPlugin#pickWeaponAutofireAI} on EVERY weapon
 * (all ships, all sessions) — a PURE PASS-THROUGH unless
 * {@link AutofireTargetCoordinator#livePickFor} serves a live server pick for
 * (own ship, this mount): no predict session / no pick / any query trouble ⇒ the delegate
 * runs verbatim (offline play and sim are zero-delta) — pure pass-through + an own-mount
 * observation (the own-burst-gate row below: reads only, the verdict unchanged). B-deny-clean:
 * public API + project classes only.
 */
public final class AutofireTargetAiPlugin implements AutofireAIPlugin {

    private final AutofireAIPlugin delegate;
    private final WeaponAPI weapon;
    /** The own-burst-gate observation ({@link OwnBurstPhaseMeter.Gate}):
     *  bound ONCE per frame in {@link #advance} (the engine calls {@code advance} then {@code shouldFire} back
     *  to back, {@code WeaponGroup.advanceAuto}); a non-own / offline {@code shouldFire} pays ONE field test. */
    private final OwnBurstPhaseMeter.Gate gate = new OwnBurstPhaseMeter.Gate();
    private boolean gateOn;

    public AutofireTargetAiPlugin(AutofireAIPlugin delegate, WeaponAPI weapon) {
        this.delegate = delegate;
        this.weapon = weapon;
    }

    @Override
    public void advance(float amount) {
        ShipAPI ship;
        ShipAPI wirePick;
        AutofireTargetCoordinator coordinator = null;
        try {
            ship = weapon == null ? null : weapon.getShip();
            coordinator = ship == null ? null : PredictReconcileCombatPlugin.activeAutofireTargets();
            wirePick = coordinator == null ? null : coordinator.livePickFor(ship, weapon);
        } catch (Throwable t) {
            ship = null;
            wirePick = null; // fail-soft: query trouble must never break vanilla autofire
        }
        gateOn = gate.bind(coordinator, ship, weapon);
        if (wirePick == null || ship == null) {
            delegate.advance(amount);
            return;
        }
        ShipAPI saved = ship.getShipTarget();
        ship.setShipTarget(wirePick);
        try {
            delegate.advance(amount);
        } finally {
            ship.setShipTarget(saved); // bracket law: restore INCLUDING on delegate throw
        }
    }

    @Override
    public boolean shouldFire() {
        boolean v = delegate.shouldFire(); // EXACTLY once: vanilla's phased branch consumes Math.random()
        if (gateOn) {
            gate.observe(weapon, v); // reads only, never throws; the verdict stays the delegate's
        }
        return v;
    }

    @Override
    public void forceOff() {
        delegate.forceOff();
    }

    @Override
    public Vector2f getTarget() {
        return delegate.getTarget();
    }

    @Override
    public ShipAPI getTargetShip() {
        return delegate.getTargetShip();
    }

    @Override
    public WeaponAPI getWeapon() {
        return delegate.getWeapon();
    }

    @Override
    public MissileAPI getTargetMissile() {
        return delegate.getTargetMissile();
    }
}
