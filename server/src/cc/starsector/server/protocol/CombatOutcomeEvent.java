package cc.starsector.server.protocol;

import cc.starsector.server.adapter.CombatOutcomeView;
import java.util.Objects;

/**
 * One server-authoritative combat-OUTCOME event: at completed simulation tick
 * {@code simTick} the match was decided and {@code winningSideId} won.
 *
 * <p>This is the match-lifecycle counterpart of the per-entity
 * {@link EntityDespawnEvent}: where a despawn event reports that one entity left
 * the world, a combat-outcome event reports that the <em>whole match</em> reached
 * a terminal state — the single signal a dedicated <em>combat</em> server must push
 * to end a session and tell every client who won. Nothing else on the wire conveys
 * it: the {@link AuthoritativeWorldSnapshot} tier carries evolving per-ship state,
 * {@link EntityDespawnEvent} carries individual kills, and
 * {@link SnapshotStreamCompletedMessage} only says the byte stream ended — none of
 * them declares a winner.
 *
 * <p>An outcome event is by construction <em>decided</em>: {@code winningSideId >= 0}
 * (a concrete {@code FleetSide} ordinal, e.g. 0 or 1). The render-coupled
 * {@code combatOver} flag the engine also exposes is carried verbatim for diagnostics
 * / parity, but it is <em>not</em> the trigger: per the load-bearing finding
 * (see {@link CombatOutcomeView}) a headless server decides the match on the
 * render-free {@code winningSideId() >= 0}, because the engine's public
 * {@code isCombatOver()} gates on an end-of-combat visual fade a headless server never
 * drives. So {@code combatOver} may well be {@code false} on a perfectly valid decided
 * outcome event.
 */
public record CombatOutcomeEvent(int winningSideId, boolean combatOver, long simTick) {

    public CombatOutcomeEvent {
        if (winningSideId < 0) {
            throw new IllegalArgumentException(
                    "a decided combat outcome event requires winningSideId >= 0; got " + winningSideId);
        }
        if (simTick < 0L) {
            throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
        }
    }

    /** A decided combat outcome at {@code simTick}: {@code winningSideId} won. */
    public static CombatOutcomeEvent decided(int winningSideId, boolean combatOver, long simTick) {
        return new CombatOutcomeEvent(winningSideId, combatOver, simTick);
    }

    /**
     * Build the outcome event from the project-owned {@link CombatOutcomeView} read
     * off the live engine bridge ({@code EngineCombatBinding.combatOutcomeOrNull()})
     * at completed tick {@code simTick}. Fail-closed on an <em>undecided</em> view: a
     * combat-outcome event only exists once a side is assigned, so the caller (the
     * server-side {@link cc.starsector.server.runtime.CombatOutcomeEventDetector
     * CombatOutcomeEventDetector}) emits it exactly at the render-free
     * {@code decided()} transition, never speculatively.
     */
    public static CombatOutcomeEvent fromView(CombatOutcomeView view, long simTick) {
        Objects.requireNonNull(view, "view");
        if (!view.decided()) {
            throw new IllegalArgumentException(
                    "cannot build a combat outcome event from an undecided outcome (winningSideId "
                            + view.winningSideId() + ")");
        }
        return new CombatOutcomeEvent(view.winningSideId(), view.combatOver(), simTick);
    }
}
