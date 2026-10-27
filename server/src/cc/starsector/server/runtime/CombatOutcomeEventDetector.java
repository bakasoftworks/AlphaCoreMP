package cc.starsector.server.runtime;

import cc.starsector.server.adapter.CombatOutcomeView;
import cc.starsector.server.protocol.CombatOutcomeEvent;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-side producer that derives the single explicit {@link CombatOutcomeEvent}
 * from the per-tick combat-outcome read ({@link CombatOutcomeView},
 * {@code EngineCombatBinding.combatOutcomeOrNull()}).
 *
 * <p>It is the match-lifecycle analogue of {@link EntityDespawnEventDetector}: where
 * that detector folds the authoritative roster stream and emits one despawn event per
 * entity that leaves the world, this folds the per-tick outcome reads and emits
 * exactly one outcome event when the match is first decided. The trigger is the
 * <em>render-free</em> {@link CombatOutcomeView#decided()} (winning side assigned),
 * <em>not</em> the engine's render-coupled {@code isCombatOver()} — the load-bearing
 * finding: a headless server has no
 * render loop to drive the end-of-combat visual fade, so it must end the session on
 * {@code decided()}. The {@code combatOver} flag observed at the decision is carried
 * on the event verbatim, for diagnostics/parity, but it does not gate emission.
 *
 * <p>A match is decided exactly once: after the first decided read the detector latches
 * and never re-emits, even if later reads still report a (possibly different) winning
 * side. The detector is stateful and single-threaded: {@link #observe} each tick's
 * read in non-decreasing {@code simTick} order. An undecided read (winning side
 * {@code < 0}) yields no event.
 */
public final class CombatOutcomeEventDetector {

    private boolean decided = false;
    private CombatOutcomeEvent decidedEvent;
    private boolean sawFirstObservation = false;
    private long lastObservedTick = -1L;

    /**
     * Fold one tick's combat-outcome read into the detector, returning the outcome
     * event if this read is the one that first decides the match (empty otherwise, and
     * empty for every read after the match is already decided). Reads must be supplied
     * in non-decreasing {@code simTick} order.
     */
    public Optional<CombatOutcomeEvent> observe(CombatOutcomeView outcome, long simTick) {
        Objects.requireNonNull(outcome, "outcome");
        if (simTick < 0L) {
            throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
        }
        if (sawFirstObservation && simTick < lastObservedTick) {
            throw new IllegalArgumentException(
                    "outcome reads must be observed in non-decreasing simTick order; got " + simTick
                            + " after " + lastObservedTick);
        }
        sawFirstObservation = true;
        lastObservedTick = simTick;
        if (decided) {
            return Optional.empty();
        }
        if (!outcome.decided()) {
            return Optional.empty();
        }
        decided = true;
        decidedEvent = CombatOutcomeEvent.fromView(outcome, simTick);
        return Optional.of(decidedEvent);
    }

    /** True once the match has been decided and the outcome event emitted. */
    public boolean isDecided() {
        return decided;
    }

    /** The decided outcome event, or empty until the match is decided. */
    public Optional<CombatOutcomeEvent> decidedEvent() {
        return Optional.ofNullable(decidedEvent);
    }
}
