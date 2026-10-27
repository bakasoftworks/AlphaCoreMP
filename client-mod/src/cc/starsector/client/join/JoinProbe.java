package cc.starsector.client.join;

import cc.starsector.client.net.DoorProbe;

/**
 * The joiner GUI's PRE-LAUNCH ship-list fetch — the client
 * half of the door PROBE the server grew at L2 ({@code PilotIntentDatagramServer.handleProbeLocked}).
 *
 * <p><b>This is a THIN FACADE:</b> the socket + collector half
 * ({@link DoorProbe#fetch13}, {@link DoorProbe.Collector}, {@link DoorProbe.Result},
 * {@link DoorProbe#offerable}, {@link DoorProbe#rejectMessage}) lives
 * in {@link DoorProbe} — an in-game-legal class in the package the listing reader already lives
 * in — so the SAME fetch can run inside the game JVM ({@code CcClientModPlugin}'s empty-pick
 * fallback). {@code join/**} is exempt from the classloader-B deny scan because it runs only in
 * the JoinGui JVM, and by the same law an in-game class may reference NO {@code join/**} class
 * (the build RED, `check-b-deny-refs.ps1`) — hence the split. What stays here is GUI-only:
 * the combo's entry-0 labels and the delegates that keep {@link JoinGui} / {@link JoinLaunchPlan}
 * / the {@code --selftest} goldens on their existing call sites. Security posture + the full
 * design javadoc: {@link DoorProbe}.
 */
final class JoinProbe {

    /** Combo entry 0 — the descriptor's own spawn; travels as an EMPTY {@code variantId}. */
    static final String SERVER_DEFAULT_LABEL = "(server default)";

    /** The ship-list status before a fetch can run — the Swing fetcher's and the SPA's one line (GUI v2 step 3). */
    static final String NOT_READY_LINE = "Ship list: fill in address, port and match password to"
            + " fetch the server's list (or type a ship id).";

    //
    /** Combo entry-0 label when the SERVER named a default ship (probe mark
     * {@code ClientListingReader#MARK_SHIP_NEXT_RESPAWN}) — {@code "(server default: <id>)"}.
     * {@link JoinLaunchPlan#sanitizeShip} maps ANY {@code (server default…} label back to an
     * empty pick (so selecting it persists {@code ""} and the next open re-fetches), while the
     * Launch path resolves the concrete id via {@link JoinLaunchPlan#resolveLaunchShip}. */
    static String serverDefaultLabel(String id) {
        return "(server default: " + id + ")";
    }

    private JoinProbe() {
    }

    /** Delegate → {@link DoorProbe#offerable} (the bat's {@code :vguard} charset law). */
    static boolean offerable(String id) {
        return DoorProbe.offerable(id);
    }

    /** Delegate → {@link DoorProbe#vguardShape} (the same charset law with no cap of its own). */
    static boolean vguardShape(String value) {
        return DoorProbe.vguardShape(value);
    }

    /** Delegate → {@link DoorProbe#rejectMessage}. */
    static String rejectMessage(String reason) {
        return DoorProbe.rejectMessage(reason);
    }

    /** Delegate → {@link DoorProbe#fetch13} (the match password sealed, never readable on the wire; since THE
     * FLIP the only probe): blocking, never throws — callers run it OFF the EDT. */
    static DoorProbe.Result fetch(String host, int port, String password, String clientId,
            String fingerprint) {
        return DoorProbe.fetch13(host, port, password, clientId, fingerprint);
    }
}
