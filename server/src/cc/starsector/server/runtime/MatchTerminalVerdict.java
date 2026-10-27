package cc.starsector.server.runtime;

import cc.starsector.server.runtime.PersistentDedicatedCombatServer.MatchOutcomeReceipt;
import java.util.Objects;

/**
 * The server-authored, persistable TERMINAL verdict
 * of a match,
 * covering the cases the on-wire
 * {@link cc.starsector.server.protocol.CombatOutcomeEvent} structurally CANNOT.
 *
 * <p><b>Why a new type, not a reuse of {@code CombatOutcomeEvent}.</b> That event is a
 * <em>decided</em>-only family: its canonical constructor enforces {@code winningSideId >= 0}.
 * So a round that hits {@code maxTicks} with no side eliminated — a real L1
 * "best-of-N" case, since the next-round seam runs "until {@code decided()} OR maxTicks"
 * — has no representation at all. This verdict is
 * the superset: it classifies every terminal a {@link MatchOutcomeReceipt} can carry.
 *
 * <p><b>The three terminals</b> (derived faithfully from the receipt the LIVE
 * {@link PersistentDedicatedCombatServer#runMatchOutcomeLoop} already produces — no new engine
 * read, no faked value):
 * <ul>
 *   <li>{@link Kind#DECIDED} — a fighting side eliminated the other; the winner is
 *       {@code winningSideId} (0 or 1), {@code terminalTick} is the tick the roster first
 *       decided. This is exactly the existing {@code CombatOutcomeEvent}.</li>
 *   <li>{@link Kind#DRAW} — the loop reached {@code maxTicks} undecided. In a fixed-{@code maxTicks}
 *       match, running out the clock with no winner IS the no-winner result (the gap-table's
 *       "timeout / draw" terminal). {@code winningSideId == -1}.</li>
 *   <li>{@link Kind#ABORTED} — the loop stopped on the shutdown sentinel before any terminal: an
 *       operational stop, not a sporting result. {@code winningSideId == -1}.</li>
 * </ul>
 *
 * <p>This type is on-DISK only (persisted by {@link MatchVerdictRecordCodec}); it is NOT a wire
 * message — the decided case already crosses the wire as {@code CombatOutcomeEvent}, and the
 * byte-frozen codecs stay untouched (avoid-list: codecs stay byte-identical). A finer DRAW
 * sub-classification (clock-expiry vs. simultaneous mutual destruction) is a deferred enhancement:
 * the roster outcome view collapses both-alive and both-dead to undecided, so distinguishing them
 * needs per-side liveness the view does not carry today.
 */
public record MatchTerminalVerdict(
        Kind kind,
        int winningSideId,
        long terminalTick,
        int ticksAdvanced,
        String stopReason) {

    /** The terminal a match reached. */
    public enum Kind {
        /** One fighting side was eliminated; {@code winningSideId} is the survivor. */
        DECIDED,
        /** {@code maxTicks} reached undecided — the no-winner result of a timed match. */
        DRAW,
        /** The shutdown sentinel fired before any terminal — an operational stop. */
        ABORTED
    }

    /** {@code winningSideId} sentinel for every non-{@link Kind#DECIDED} terminal. */
    public static final int NO_WINNER = -1;

    public MatchTerminalVerdict {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(stopReason, "stopReason");
        if (ticksAdvanced < 0) {
            throw new IllegalArgumentException("ticksAdvanced must be >= 0; got " + ticksAdvanced);
        }
        if (terminalTick < 0L) {
            throw new IllegalArgumentException("terminalTick must be >= 0; got " + terminalTick);
        }
        if (kind == Kind.DECIDED) {
            if (winningSideId < 0) {
                throw new IllegalArgumentException(
                        "a DECIDED verdict requires winningSideId >= 0; got " + winningSideId);
            }
        } else if (winningSideId != NO_WINNER) {
            throw new IllegalArgumentException(
                    "a " + kind + " verdict requires winningSideId == " + NO_WINNER + "; got " + winningSideId);
        }
    }

    /**
     * Classify the terminal a finished {@link MatchOutcomeReceipt} represents. A decided receipt
     * becomes {@link Kind#DECIDED} keyed at its decided tick; an undecided receipt is a
     * {@link Kind#ABORTED} when the loop stopped on the shutdown sentinel, else a {@link Kind#DRAW}
     * (the {@code max-ticks} terminal). For the non-decided terminals {@code terminalTick} records
     * how far the round actually advanced.
     */
    public static MatchTerminalVerdict from(MatchOutcomeReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        if (receipt.decided()) {
            return new MatchTerminalVerdict(
                    Kind.DECIDED,
                    receipt.winningSideId(),
                    receipt.decidedTick(),
                    receipt.ticksAdvanced(),
                    receipt.stopReason());
        }
        Kind kind = "shutdown-signal".equals(receipt.stopReason()) ? Kind.ABORTED : Kind.DRAW;
        return new MatchTerminalVerdict(
                kind,
                NO_WINNER,
                receipt.ticksAdvanced(),
                receipt.ticksAdvanced(),
                receipt.stopReason());
    }
}
