package cc.starsector.server.bridge;

import cc.starsector.server.adapter.CombatStepBoundary;
import cc.starsector.server.adapter.CombatWorldState;
import cc.starsector.server.runtime.AuthoritativeDamageSink;
import cc.starsector.server.runtime.ServerAuthoredProjectileField;
import cc.starsector.server.runtime.ServerAuthoredProjectileImpact;
import cc.starsector.server.runtime.ServerAuthoredProjectileTarget;
import java.util.List;
import java.util.Objects;

/**
 * A {@link CombatStepBoundary} decorator that gives the server-authored projectile tier
 * an <em>authoritative, engine-resolved</em> effect.
 *
 * <p>After advancing the inner headless combat step for {@code simTick}, it resolves any
 * server-authored projectile impacts against the just-advanced live ships (supplied by a
 * {@link TargetSupplier} that reads them off the engine bridge) and applies each through
 * the {@link AuthoritativeDamageSink} — the GL-free {@code CombatEngineAPI.applyDamage}
 * boundary. Because the engine never sees a server-authored projectile
 * (it cannot spawn one headless), this decorator is what lets that
 * projectile change authoritative ship durability: the server owns the impact geometry,
 * the engine owns the armor/hull math.
 *
 * <p>It is INTENDED to compose with {@link EngineBackedLiveWorldSnapshotSource}, whose
 * {@code publishNextTick()} calls {@code step(dt, simTick)} and folds the same
 * {@link ServerAuthoredProjectileField} onto the wire — but at HEAD that source steps a plain
 * {@link CombatStepBoundary} field and nothing in the live source wraps it in this decorator; the
 * only construction sites are {@code ServerContractTests}. This is purely a not-yet-wired
 * present-tense claim, NOT an unfaithful path: server-authored damage still routes through the
 * engine's OWN {@code applyDamage} via the {@link AuthoritativeDamageSink}. When
 * wired, a projectile is consumed by {@link ServerAuthoredProjectileField#resolveImpactsAt} on impact,
 * so it disappears from the wire on the impact frame while the target's durability — already carried
 * by the ship snapshot — drops that frame: the observable effect.
 *
 * <p>All {@link CombatStepBoundary} reads (world state, step amount, input-coupling
 * guard) delegate to the inner step, so this decorator never changes the headless-clean
 * step contract — it only adds the post-step impact application.
 */
public final class ServerAuthoredProjectileDamageStep implements CombatStepBoundary {

    /** Supplies the live impact targets (ship id, owner, position, hit radius) for a tick. */
    @FunctionalInterface
    public interface TargetSupplier {
        List<ServerAuthoredProjectileTarget> targetsAt(long simTick);
    }

    private final CombatStepBoundary inner;
    private final ServerAuthoredProjectileField field;
    private final TargetSupplier targets;
    private final AuthoritativeDamageSink sink;
    private long impactsResolved;
    private long impactsApplied;

    public ServerAuthoredProjectileDamageStep(
            CombatStepBoundary inner,
            ServerAuthoredProjectileField field,
            TargetSupplier targets,
            AuthoritativeDamageSink sink) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.field = Objects.requireNonNull(field, "field");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public CombatWorldState worldState() {
        return inner.worldState();
    }

    @Override
    public float effectiveStepAmount(float fixedDt) {
        return inner.effectiveStepAmount(fixedDt);
    }

    @Override
    public boolean inputCouplingBypassed() {
        return inner.inputCouplingBypassed();
    }

    // Pass the inner boundary's advance sub-stamps through (a decorator that
    // swallowed them would print 0 on the stall row).
    @Override
    public long lastEngineAdvanceNanos() {
        return inner.lastEngineAdvanceNanos();
    }

    @Override
    public long lastPostStepNanos() {
        return inner.lastPostStepNanos();
    }

    @Override
    public long lastReinjectNanos() {
        return inner.lastReinjectNanos();
    }

    @Override
    public long lastEngineAdvanceCpuNanos() {
        return inner.lastEngineAdvanceCpuNanos();
    }

    @Override
    public void step(float fixedDt, long simTick) {
        inner.step(fixedDt, simTick);
        List<ServerAuthoredProjectileTarget> live = targets.targetsAt(simTick);
        List<ServerAuthoredProjectileImpact> impacts = field.resolveImpactsAt(simTick, live);
        for (ServerAuthoredProjectileImpact impact : impacts) {
            impactsResolved++;
            if (sink.apply(impact)) {
                impactsApplied++;
            }
        }
    }

    /** Number of authored-projectile impacts resolved over this boundary's life. */
    public long impactsResolved() {
        return impactsResolved;
    }

    /** Number of impacts the engine sink accepted (applied authoritative damage for). */
    public long impactsApplied() {
        return impactsApplied;
    }
}
