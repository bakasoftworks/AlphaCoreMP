package cc.starsector.client.join;

/**
 * The join window's ship-list probe COMMIT law (rider r6). The door
 * records every probe's password as a guess (the no-reset backoff keeps a FAIL for hours), so the auto-probe
 * never fires on a password prefix: a password KEYSTROKE marks the field uncommitted and schedules nothing; a
 * COMMIT (Enter in the field after an edit, focus loss with a value changed since the last commit, a Refresh click, the
 * Launch press, a pasted connect code, or a remembered password loaded untyped at open) marks it committed and
 * the caller probes AT ONCE (no added latency). Host/port edits keep their 700 ms debounce; its fire probes
 * only while committed, else the caller shows {@link #blockedStatusLine()} and sends nothing.
 *
 * <p>Pure state, no Swing: {@code ShipListFetcher} drives one instance on the EDT (not thread-safe by
 * design); {@code client-mod/web/join/app.js} carries the same table in JS (its {@code pwCommitted} /
 * {@code pwEditedSinceCommit} pair) and the same status words. Pinned by
 * {@code JoinGuiProbeSelftest.selftestProbeCommit}.
 *
 * <p>The shared table, event → probe?
 * <ul>
 * <li>password keystroke / clipboard paste into the field → no probe; uncommitted, edited-since-commit set;</li>
 * <li>Enter in the password field → probe iff edited since the last commit (then committed, edited cleared);
 *     Enter over an UNCHANGED committed value → NO probe (each re-probe records one more door guess) — the
 *     Java window shows {@link #ENTER_UNCHANGED_STATUS}, the web lets its document Enter handler launch;</li>
 * <li>focus leaving the field (Java: a permanent in-window loss; web: blur while {@code document.hasFocus()})
 *     → probe iff edited since the last commit;</li>
 * <li>Refresh click → commit + probe, always;</li>
 * <li>Launch press / pasted connect code / remembered password at open → commit, no probe of its own;</li>
 * <li>host/port debounce fire → probe iff committed, else {@link #BLOCKED_STATUS}.</li>
 * </ul>
 */
final class ProbeCommitGate {
    /** The status line of a host/port debounce fire while the password is uncommitted (the web twin: app.js). */
    static final String BLOCKED_STATUS = "Ship list: press Enter to check the password";
    /**
     * The status hint of an Enter over an unchanged,
     * already-committed password (no probe; Java window only — the web's Enter launches).
     */
    static final String ENTER_UNCHANGED_STATUS = "Password already checked - press Launch, or edit it";
    /** Appended to a held / no-reply / failed probe line on an unchanged
     * Enter — the hint above is shown only after a SERVED probe; a REJECT line is left as it is. */
    static final String ENTER_RETRY_SUFFIX = " - press Refresh to retry";

    private boolean committed;
    private boolean editedSinceCommit;
    private boolean debouncePending;

    /** {@code committedAtOpen} = a remembered password loaded untyped. */
    ProbeCommitGate(boolean committedAtOpen) {
        this.committed = committedAtOpen;
    }

    /** A password keystroke / document change — uncommitted, NO probe. */
    void passwordEdited() {
        committed = false;
        editedSinceCommit = true;
    }

    /** A COMMIT event; always true = the caller probes now. */
    boolean passwordCommitted() {
        committed = true;
        editedSinceCommit = false;
        return true;
    }

    /**
     * Focus left the password field — a commit only when the value was
     * edited since the last commit (a tab through an untouched field probes nothing); true = probe now.
     */
    boolean passwordFocusLost() {
        return editedSinceCommit && passwordCommitted();
    }

    /**
     * Enter in the password field — a commit only when the
     * value was edited since the last commit (the web's {@code pwEditedSinceCommit} Enter law); true = probe now,
     * false = nothing changed, no probe (a repeat Enter would record one more guess on the door).
     */
    boolean passwordEnter() {
        return editedSinceCommit && passwordCommitted();
    }

    /** A host/port edit — the caller restarts its 700 ms debounce. */
    void otherFieldEdited() {
        debouncePending = true;
    }

    /**
     * The host/port debounce fired — true = probe now; false = show
     * {@link #blockedStatusLine()} and send nothing (the password is uncommitted).
     */
    boolean debounceFired() {
        debouncePending = false;
        return committed;
    }

    /** A host/port edit is waiting on the debounce (selftest read). */
    boolean debouncePending() {
        return debouncePending;
    }

    /** May an automatic (debounced) probe run now. */
    boolean probeAllowed() {
        return committed;
    }

    /** The blocked-probe status line. */
    String blockedStatusLine() {
        return BLOCKED_STATUS;
    }
}
