package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * The CLIENT {@code OnFireEffectPlugin} the app-load
 * {@link ClientOnFireAttachSweep} installs by NAME onto projectile specs: the engine's own fire
 * push confessing every covered-spec discharge birth into the static {@link ClientFireBirthSink}
 * (mirror of the server's {@code CcOnFireEmitter}; the S1/S2 productized precedent). The engine
 * resolves the name through ScriptStore at weapon construction and builds one instance per
 * weapon — hence the public no-arg ctor and the per-instance delegate below.
 *
 * <p>Wrap lane: a wrapper built from a spec whose vanilla plugin the sweep recorded lazily
 * resolves ONE fresh original instance for itself ({@link ClientFireBirthSink#newDelegateOrNull})
 * and chain-calls it after delivering. The engine dispatch has no guard, so this class NEVER
 * throws: the whole body is caught to {@link ClientFireBirthSink#cbError()}, the chained call
 * separately to {@link ClientFireBirthSink#delegError()} (a delegate's own throw is its failure,
 * not ours — and it must not crash the window).
 */
public final class CcClientOnFireEmitter implements OnFireEffectPlugin {

    private boolean delegateResolved;
    private OnFireEffectPlugin delegate;

    public CcClientOnFireEmitter() {
        ClientFireBirthSink.instanceBuilt();
    }

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        try {
            ClientFireBirthSink.deliver(projectile, weapon);
            if (!delegateResolved) {
                delegateResolved = true;
                Object projSpec = weapon == null || weapon.getSpec() == null
                        ? null : weapon.getSpec().getProjectileSpec();
                Object resolved = projSpec == null ? null
                        : ClientFireBirthSink.newDelegateOrNull(projSpec);
                if (resolved instanceof OnFireEffectPlugin) {
                    delegate = (OnFireEffectPlugin) resolved;
                }
            }
            if (delegate != null) {
                try {
                    delegate.onFire(projectile, weapon, engine);
                } catch (Throwable delegateFailure) {
                    ClientFireBirthSink.delegError();
                }
            }
        } catch (Throwable t) {
            ClientFireBirthSink.cbError();
        }
    }
}
