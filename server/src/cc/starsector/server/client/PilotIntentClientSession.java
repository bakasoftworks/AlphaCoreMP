package cc.starsector.server.client;

import cc.starsector.server.runtime.PilotIntentFrame;
import cc.starsector.server.runtime.PilotIntentWireCodec;
import cc.starsector.server.runtime.PilotWorldViewFrame;
import cc.starsector.server.runtime.PilotWorldViewWireCodec;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The reusable, INJECTABLE-intent faithful pilot client session for the live two-client 1v1 demo
 * — the client-integration seam the two real Starsector game windows
 * drive for the two-window watch.
 *
 * <p>{@link PilotIntentSocketClient} is a standalone {@code main()} that BAKES IN its intent: aim is
 * always {@link PilotWorldViewFrame#bearingToTargetDeg()}, the only key is a scripted strafe burst,
 * and fire is always held. That is exactly right for a headless gate, but a real game window cannot
 * use it: the human's aim is wherever the mouse points, the keys are whatever is held this tick, and
 * fire is the trigger button — all of which only exist per-frame inside the game's input loop. So the
 * mod's per-frame combat plugin needs a session it can (1) ask for the latest {@link PilotWorldViewFrame}
 * to RENDER the two ships, and (2) hand this tick's live {@code (aim, heldControls, fireHeld)} to SEND.
 *
 * <p>This session is that seam. It owns the socket to the server's
 * {@link cc.starsector.server.runtime.PilotIntentSocketServer} and the strict per-tick ping-pong
 * ({@link #receiveWorldView()} reads the view DOWN, {@link #sendIntent} writes the intent UP, echoing
 * the view's {@code frameSeq} and the configured ship/group). It reuses the UNCHANGED
 * {@link PilotWorldViewWireCodec}/{@link PilotIntentWireCodec} — no re-minted codec — so a window
 * driving this session is byte-wire-identical to the proven {@code PilotIntentSocketClient}; only the
 * intent SOURCE differs (live human input vs. a script). A SHA-256 of exactly the intent bytes sent is
 * kept so a verifier can prove cross-process byte-identity end to end.
 *
 * <p>Fail-closed: any I/O error propagates; the caller closes the session ({@link AutoCloseable}).
 */
public final class PilotIntentClientSession implements AutoCloseable {

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 60_000;

    private final long shipId;
    private final int selectedWeaponGroup;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final MessageDigest digest;

    private PilotWorldViewFrame lastView;
    private int framesSent;
    private float lastAimDeg = Float.NaN;

    /** The default stream-acquire budget: ONE attempt, no backoff — the historical single-shot behavior
     *  every off-engine caller (the contract self-test) uses. The in-game pilot opts into a retry budget
     *  via the {@code maxStreamAcquireAttempts}/{@code streamAcquireBackoffMillis} overload below. */
    private static final int DEFAULT_STREAM_ACQUIRE_ATTEMPTS = 1;
    private static final long DEFAULT_STREAM_ACQUIRE_BACKOFF_MILLIS = 0L;

    /**
     * Observes each {@link #connect} stream-acquisition attempt so an in-game caller can log WHEN the
     * script-sandbox denial clears. Pure {@code java.*} (a small functional interface) — the
     * server module takes no Starsector dependency; the mod passes a lambda that writes to its own log.
     */
    @FunctionalInterface
    public interface StreamAcquireObserver {
        /**
         * @param attempt       1-based attempt number
         * @param failure       {@code null} on the successful attempt; otherwise the throwable that denied it
         * @param elapsedMillis ms since the first attempt
         */
        void onAttempt(int attempt, Throwable failure, long elapsedMillis);

        /** A no-op observer (the off-engine / default path logs nothing). */
        StreamAcquireObserver NONE = (attempt, failure, elapsedMillis) -> { };
    }

    private PilotIntentClientSession(long shipId, int selectedWeaponGroup, Socket socket,
            InputStream in, OutputStream out, boolean trackSentDigest) {
        this.shipId = shipId;
        this.selectedWeaponGroup = selectedWeaponGroup;
        this.socket = socket;
        this.in = in;
        this.out = out;
        // The SHA-256 is a TEST-ONLY checksum of the sent bytes (sentSha256(), consumed by the
        // byte-identity self-tests); it is NEVER written to the wire. MessageDigest.getInstance loads a
        // JCA provider via REFLECTION, which Starsector's in-combat script SecurityManager also forbids
        // ("File access and reflection are not allowed to scripts"). The in-game pilot opts OUT
        // (digest == null) so the combat path does no reflection; off-engine callers keep it (wire bytes
        // identical either way). The streams are acquired BEFORE the constructor by acquireStreamsWithRetry
        // — see connect(...) for the blocker root cause (a static java.io name blocklist in B).
        this.digest = trackSentDigest ? newSha256() : null;
    }

    /**
     * Connect to the server's {@link cc.starsector.server.runtime.PilotIntentSocketServer} at
     * {@code host:port} and pilot {@code shipId} with weapon group {@code selectedWeaponGroup}.
     * Maintains the test-only sent-bytes SHA-256 ({@link #sentSha256()}); off-engine callers use this.
     */
    public static PilotIntentClientSession connect(String host, int port, long shipId, int selectedWeaponGroup)
            throws IOException {
        return connect(host, port, shipId, selectedWeaponGroup, true);
    }

    /**
     * As {@link #connect(String, int, long, int)}, but {@code trackSentDigest} chooses whether the
     * session keeps the test-only SHA-256 of sent bytes ({@link #sentSha256()}). The IN-GAME pilot passes
     * {@code false}: {@code MessageDigest.getInstance} does JCA provider class-loading via reflection,
     * which Starsector's in-combat script SecurityManager denies. The bytes that cross the
     * wire are identical either way (the digest is local, never sent), so off-engine callers pass
     * {@code true} to keep the cross-process byte-identity proof. Single-shot stream acquisition (the
     * historical behavior); the in-game pilot uses the retry overload below.
     */
    public static PilotIntentClientSession connect(String host, int port, long shipId, int selectedWeaponGroup,
            boolean trackSentDigest) throws IOException {
        return connect(host, port, shipId, selectedWeaponGroup, trackSentDigest,
                DEFAULT_STREAM_ACQUIRE_ATTEMPTS, DEFAULT_STREAM_ACQUIRE_BACKOFF_MILLIS, StreamAcquireObserver.NONE);
    }

    /**
     * As {@link #connect(String, int, long, int, boolean)}, with a stream-acquisition retry budget kept
     * for API compatibility. <b>The retry is now VESTIGIAL</b> — a root-cause pass found the
     * blocker that failed five WATCHes and proved retry could never have cleared it:
     *
     * <p><b>What the blocker actually was.</b> The TCP
     * {@code connect()} always succeeds. The denial came from the script classloader
     * {@code com.fs.starfarer.loading.scripts.B}, which is a STATIC name blocklist evaluated BEFORE any
     * delegation / {@code findLoadedClass}: any {@code java.io.*} name not on a fixed allowlist is denied
     * ("File access and reflection are not allowed to scripts"). The earlier "cold class / warms up /
     * retry until allowed" model was wrong — a blocklisted name is denied on EVERY request, which is
     * why the earlier 240 retries over ~60s ALL failed. The class the old code tripped was the
     * {@code new BufferedOutputStream(...)} wrapper (blocklisted), NOT the bare {@code getOutputStream()}
     * (its {@code java.io.OutputStream} type and {@code java.net.*} impl ARE allowlisted).
     *
     * <p><b>The real fix.</b> Make the transport allowlist-clean — write to a BARE output
     * stream and serialize via {@link PilotIntentWireCodec#encodeFrame} (no {@code BufferedOutputStream}/
     * {@code ByteArrayOutputStream}/{@code Data*Stream}), mirroring the proven {@code ServerSnapshotClient}
     * /{@code WireBytes} discipline. With that, acquisition succeeds on the first attempt
     * in-game, so the retry loop is a harmless no-op-on-success path. We still do NOT reconnect (that would
     * strand the server, which {@code accept()}s exactly ONE connection — {@link PilotIntentSocketServer#awaitClient}).
     * The {@code observer} is still notified per attempt, and on exhaustion the socket is closed and the
     * last failure rethrown (fail-closed); the socket-close-on-failure is the keeper.
     */
    public static PilotIntentClientSession connect(String host, int port, long shipId, int selectedWeaponGroup,
            boolean trackSentDigest, int maxStreamAcquireAttempts, long streamAcquireBackoffMillis,
            StreamAcquireObserver observer) throws IOException {
        if (host == null) {
            throw new IllegalArgumentException("host must not be null");
        }
        if (port < 0) {
            throw new IllegalArgumentException("port must be >= 0; got " + port);
        }
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (selectedWeaponGroup < 0) {
            throw new IllegalArgumentException("selectedWeaponGroup must be >= 0; got " + selectedWeaponGroup);
        }
        StreamAcquireObserver obs = observer != null ? observer : StreamAcquireObserver.NONE;
        int attempts = Math.max(1, maxStreamAcquireAttempts);
        long backoff = Math.max(0L, streamAcquireBackoffMillis);
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
        try {
            Streams streams = acquireStreamsWithRetry(socket, attempts, backoff, obs);
            return new PilotIntentClientSession(shipId, selectedWeaponGroup, socket,
                    streams.in, streams.out, trackSentDigest);
        } catch (Throwable t) {
            // Stream acquisition failed (before the transport fix this was the script classloader B denying a
            // blocklisted java.io class on the output path; the allowlist-clean transport now
            // clears it). CLOSE the just-connected socket so the server is not left holding a dangling
            // half-open connection it then aborts (the WSAECONNABORTED prior WATCHes misread as an
            // in-combat failure). Fail-closed: rethrow so the caller leaves the ships locally driven.
            try {
                socket.close();
            } catch (IOException ignore) {
                // best effort
            }
            throw t;
        }
    }

    /**
     * Acquire the socket streams on the ALREADY-CONNECTED {@code socket}. The OUTPUT stream is a BARE
     * {@code socket.getOutputStream()} ({@code java.io.OutputStream} + {@code java.net.*} impl — both
     * allowlisted by the script classloader B); the INPUT stream keeps the allowlisted
     * {@code BufferedInputStream}. Since the allowlist-clean transport this succeeds on the first attempt in-game; the retry
     * budget ({@code maxAttempts}/{@code backoffMillis}) is vestigial (a static blocklist could never be
     * retried away) but kept for API compatibility — each attempt is reported to {@code observer}, and on
     * exhaustion the last failure is rethrown verbatim (IOException / RuntimeException / Error preserved)
     * so the caller's fail-closed handling and logging see the real cause.
     */
    private static Streams acquireStreamsWithRetry(Socket socket, int maxAttempts, long backoffMillis,
            StreamAcquireObserver observer) throws IOException {
        long start = System.currentTimeMillis();
        Throwable last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // BARE output stream — NO BufferedOutputStream. Under the in-game script classloader B
                // java.io.BufferedOutputStream is on the static blocklist (its `new` opcode
                // resolves the class -> SecurityException), while a bare java.io.OutputStream is
                // allowlisted; this is exactly the discriminator vs. the proven ServerSnapshotClient.
                // BufferedInputStream IS allowlisted, so the read side keeps it.
                OutputStream out = socket.getOutputStream();
                InputStream in = new BufferedInputStream(socket.getInputStream());
                observer.onAttempt(attempt, null, System.currentTimeMillis() - start);
                return new Streams(in, out);
            } catch (Throwable t) {
                last = t;
                observer.onAttempt(attempt, t, System.currentTimeMillis() - start);
                if (attempt < maxAttempts && backoffMillis > 0L) {
                    try {
                        Thread.sleep(backoffMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        // Exhausted (or interrupted): fail-closed, preserving the real cause's type.
        if (last instanceof IOException) {
            throw (IOException) last;
        }
        if (last instanceof RuntimeException) {
            throw (RuntimeException) last;
        }
        if (last instanceof Error) {
            throw (Error) last;
        }
        throw new IOException("pilot session stream acquisition failed after " + maxAttempts + " attempts", last);
    }

    /** The buffered socket streams acquired by {@link #acquireStreamsWithRetry}. */
    private static final class Streams {
        final InputStream in;
        final OutputStream out;

        Streams(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }
    }

    /**
     * Read the next {@link PilotWorldViewFrame} the server streamed DOWN — the two ship poses the
     * window renders this tick. Returns {@code null} at a clean end of stream; the returned view may be
     * {@link PilotWorldViewFrame#terminal()} (the server signalled done — do not send an intent for it).
     */
    public PilotWorldViewFrame receiveWorldView() throws IOException {
        PilotWorldViewFrame view = PilotWorldViewWireCodec.readFrame(in);
        this.lastView = view;
        return view;
    }

    /**
     * Send this tick's faithful pilot intent — the player's LIVE input: {@code cursorFacingDeg} (where
     * the mouse points, an absolute world angle), {@code heldControls} (the {@code PilotManeuverIntent}
     * key bitset held this tick), and {@code fireHeld} (the trigger). The frame echoes the last received
     * view's {@code frameSeq} and the session's configured ship id + selected weapon group, then is
     * serialized once (those exact bytes folded into the running SHA-256) and written UP the wire.
     */
    public void sendIntent(float cursorFacingDeg, int heldControls, boolean fireHeld) throws IOException {
        if (lastView == null) {
            throw new IllegalStateException("receiveWorldView() must precede sendIntent()");
        }
        if (lastView.terminal()) {
            throw new IllegalStateException("no intent is sent for a terminal view");
        }
        // Puppet (PilotIntentCombatPlugin) lane: no separate A/D hull heading is tracked, so ownShipFacingDeg
        // carries the cursor aim (don't-care here — only the predict-replay branch consumes it; fix (B)).
        // Legacy 3-arg lane carries no aim world point (0,0 don't-care; the predict lane's
        // PredictReconcileClientSession carries the real getMouseTarget point).
        PilotIntentFrame intent = PilotIntentFrame.of(
                shipId, lastView.frameSeq(), cursorFacingDeg, heldControls, fireHeld, selectedWeaponGroup,
                cursorFacingDeg, 0f, 0f);
        // Serialize once into a fixed byte[], digest exactly those bytes, then write them — so
        // sentSha256() is a digest of precisely what crossed the wire (the server re-serializes the
        // decoded frames and compares, proving cross-process byte-identity end to end), mirroring
        // PilotIntentSocketClient. encodeFrame is allowlist-clean (no ByteArrayOutputStream/Data*Stream,
        // which the in-game script classloader B blocklists).
        byte[] bytes = PilotIntentWireCodec.encodeFrame(intent);
        if (digest != null) { // null = the in-game digest-free pilot; see connect(...,trackSentDigest)
            digest.update(bytes);
        }
        out.write(bytes);
        out.flush();
        framesSent++;
        lastAimDeg = cursorFacingDeg;
    }

    /** The most recent world view received (the render data for this tick), or {@code null} before the first read. */
    public PilotWorldViewFrame lastView() {
        return lastView;
    }

    public long shipId() {
        return shipId;
    }

    /** The weapon group this session's intents fire (the in-game plugin renders fire on this group). */
    public int selectedWeaponGroup() {
        return selectedWeaponGroup;
    }

    public int framesSent() {
        return framesSent;
    }

    public float lastAimDeg() {
        return lastAimDeg;
    }

    /**
     * Hex SHA-256 of exactly the intent bytes sent so far. Idempotent (clones the running digest).
     * Returns {@code ""} for a digest-free session (the in-game pilot), which never calls this.
     */
    public String sentSha256() {
        if (digest == null) {
            return "";
        }
        try {
            MessageDigest copy = (MessageDigest) digest.clone();
            return toHex(copy.digest());
        } catch (CloneNotSupportedException e) {
            // SHA-256 from the platform provider is cloneable; if not, fall back to a finalizing read.
            return toHex(digest.digest());
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignore) {
            // best effort
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
