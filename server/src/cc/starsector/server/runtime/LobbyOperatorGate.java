package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The serve loop's operator-file reader for the CLIENT-WAIT (phase=lobby) — the abort probe
 * {@link PilotIntentDatagramServer#awaitLiveSessions(int, long, BooleanSupplier)} evaluates once
 * per wait slice.
 *
 * <p>WHY: the serve loop's only
 * operator poll lived at frame (a2), inside the round loop, so an {@code end-match} the GUI wrote
 * while the serve sat in the lobby was never read — the owner mis-hit Start on the public host,
 * pressed End match, and could not stop the serve. This gate polls the same
 * {@link OperatorCommandFile} during the wait: {@code end-match}/{@code end-round} FIRE (the wait
 * ends, the serve takes the clean-exit-0 no-session path — {@code run-supervised} records
 * {@code clean_exit} and STOPS, a final snapshot re-enables Start); every other verb read here
 * is CARRIED, in file order with its rejected lines, into the first in-round poll via
 * {@link #takeCarry()} — consume-on-read never drops a line, and a lobby-written
 * {@code set-joiner-default} keeps the effect it had before this gate existed.
 *
 * <p>The one exception to carrying: an {@code admin-cid} arm, when the gate was built with a
 * lobby-apply consumer, is APPLIED during the wait instead of carried (see
 * {@link #LobbyOperatorGate(OperatorCommandFile, Consumer)}).
 *
 * <p>A null operator file (surface off) is a gate that never fires and carries nothing. Not
 * thread-safe by design: one owner, the sim thread, both in the wait and at (a2).
 */
public final class LobbyOperatorGate implements BooleanSupplier {
    private final OperatorCommandFile operatorFile;
    private final Consumer<OperatorCommandFile.Command> lobbyApply;
    private String endVerb;
    private OperatorCommandFile.ParseResult carry = OperatorCommandFile.ParseResult.EMPTY;

    /** A gate with no lobby-apply consumer: every non-end verb (an {@code admin-cid} arm too) is carried. */
    public LobbyOperatorGate(OperatorCommandFile operatorFile) {
        this(operatorFile, null);
    }

    /**
     * A gate whose {@code admin-cid} arms are handed to {@code lobbyApply} as they are read, in the
     * wait slice, instead of carried. WHY: an arm deposited while the serve waits in the lobby (the
     * operator's own second window pressing Join) must land BEFORE that window's HELLO, so the
     * window is admitted as admin and outside the per-address seat cap; carried to (a2), it would
     * land only after the window was already admitted plain and capped. The probe is polled in each
     * round's start hold as well ({@code PilotServeSession}'s pre-tick gate), so an arm read there is
     * applied too, never carried into that round's (a2). Every other verb keeps the
     * carry (file order, rejects included); {@code end-match}/{@code end-round} still fire and latch.
     * The consumer runs on the caller's thread — the sim thread, inside the wait-slice probe, where
     * the in-round apply runs too. Null = carry everything (the 1-arg form).
     *
     */
    public LobbyOperatorGate(OperatorCommandFile operatorFile,
            Consumer<OperatorCommandFile.Command> lobbyApply) {
        this.operatorFile = operatorFile;
        this.lobbyApply = lobbyApply;
    }

    /** One wait-slice probe: true once an end verb has been read (latched; stays true). */
    @Override
    public boolean getAsBoolean() {
        if (operatorFile == null || endVerb != null) {
            return endVerb != null;
        }
        OperatorCommandFile.ParseResult polled = operatorFile.poll();
        if (polled.isEmpty()) {
            return false;
        }
        List<OperatorCommandFile.Command> carried = new ArrayList<OperatorCommandFile.Command>();
        for (OperatorCommandFile.Command command : polled.commands()) {
            if (command.verb() == OperatorCommandFile.Verb.END_MATCH
                    || command.verb() == OperatorCommandFile.Verb.END_ROUND) {
                if (endVerb == null) {
                    endVerb = command.verb() == OperatorCommandFile.Verb.END_MATCH
                            ? "end_match" : "end_round";
                }
            } else if (lobbyApply != null && command.verb() == OperatorCommandFile.Verb.ADMIN_CID) {
                lobbyApply.accept(command);
            } else {
                carried.add(command);
            }
        }
        carry = OperatorCommandFile.ParseResult.concat(carry,
                new OperatorCommandFile.ParseResult(List.copyOf(carried), polled.rejected()));
        return endVerb != null;
    }

    /** The end verb that fired as its log token ({@code end_match}/{@code end_round}), else null. */
    public String endVerb() {
        return endVerb;
    }

    /** Drains (once) the non-lobby verbs read during the wait — prepend to the first (a2) poll. */
    public OperatorCommandFile.ParseResult takeCarry() {
        OperatorCommandFile.ParseResult out = carry;
        carry = OperatorCommandFile.ParseResult.EMPTY;
        return out;
    }
}
