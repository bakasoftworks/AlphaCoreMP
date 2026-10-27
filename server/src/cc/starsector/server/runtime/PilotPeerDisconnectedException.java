package cc.starsector.server.runtime;

import java.io.IOException;

/**
 * Signals that the one persistent pilot peer disconnected mid-exchange — the NORMAL end of a single
 * rendered client's participation, NOT a server fault.
 *
 * <p><b>Why a distinct type.</b> {@link PilotIntentSocketServer}'s per-tick exchange both writes the
 * {@code AWSS} DOWN and reads the client's {@link PilotIntentFrame} UP. When the rendered {@code cc_client}
 * window closes (ship death / mission end / the owner closing it) the socket ends in one of two ways:
 * <ul>
 *   <li><b>clean EOF</b> — the peer half-closed its write side; the UP read sees end-of-stream
 *       ({@link PilotIntentWireCodec#readFrame} returns {@code null}); or</li>
 *   <li><b>abrupt reset</b> — the peer process/connection died; the DOWN write or UP read throws a
 *       {@link java.net.SocketException} ("connection aborted / reset by peer") or
 *       {@link java.io.EOFException}.</li>
 * </ul>
 * Both mean the same thing — the peer is gone — and neither should crash the serve loop. The transport
 * classifies them into this one type so {@link PersistentPilotIntentCombatServer}'s blocking serve loop can
 * end the round gracefully (frames served so far, then return a normal receipt → process exit 0), exactly as
 * the decoupled {@link PeerSendGate} path already treats any peer-IO failure as a clean terminal
 * ({@code PeerSendGate} marks the rung DEAD, {@code deposit} returns false).
 *
 * <p><b>Not a catch-all.</b> Only connection-level peer-gone conditions are classified here. A protocol
 * integrity failure on a frame that DID arrive (bad magic / unsupported schema version / CRC mismatch /
 * truncation) is a genuine fault, not a normal disconnect, so it still surfaces as a plain {@link IOException}
 * and fails loud — a peer sending corrupt bytes is a bug to surface, not a quiet exit.
 *
 * <p>This subclasses {@link IOException} so existing {@code catch (IOException)} sites (the dedicated lane's
 * per-client handler, {@code PeerSendGate}'s writer-thread death classifier) keep treating it as a peer death
 * with no change; only the pilot blocking serve loop catches it specifically to take the graceful-end path.
 */
public final class PilotPeerDisconnectedException extends IOException {

    private static final long serialVersionUID = 1L;

    /** The token an untagged death reports ({@link #reasonToken()}); never a live-site value. */
    public static final String UNCLASSIFIED = "unclassified";

    private final String reasonToken;

    public PilotPeerDisconnectedException(String message) {
        this(null, message, null);
    }

    public PilotPeerDisconnectedException(String message, Throwable cause) {
        this(null, message, cause);
    }

    /**
     * A death tagged with its machine reason token ({@code udp_silence},
     * {@code ctrl_window_stall}, ...) so the datagram door's {@code session_fail} stdout row and
     * the serve loop's {@code session_dead} row name WHY without parsing prose — the
     * churn kill was diagnosable only by experiment because the token lived in this message.
     */
    public PilotPeerDisconnectedException(String reasonToken, String message) {
        this(reasonToken, message, null);
    }

    public PilotPeerDisconnectedException(String reasonToken, String message, Throwable cause) {
        super(message, cause);
        this.reasonToken = reasonToken;
    }

    /** The machine reason token this death was tagged with, or {@link #UNCLASSIFIED}. */
    public String reasonToken() {
        return reasonToken == null || reasonToken.isBlank() ? UNCLASSIFIED : reasonToken;
    }
}
