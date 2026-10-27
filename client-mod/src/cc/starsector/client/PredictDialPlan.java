package cc.starsector.client;

import cc.starsector.server.runtime.Handshake13Flight;

/**
 * W13: the immutable app-load half of a dial (see {@link PredictDialLane#predictDialPlan}).
 *
 *
 */
final class PredictDialPlan {
    final String host;
    final int port;
    final long shipId;
    final int group;
    final int attempts;
    final String transport;
    final String clientId;
    final String seatToken;
    /** The credential field {@link #seatToken} came from (PASSWORD = the match
     * password lane, TOKEN = the seat-token lane) and the handshake version the dial and the probe
     * speak — the planner sets {@code Handshake13.VERSION} (= 14); no knob, env or
     * handoff field reaches it. */
    final Handshake13Flight.Lane lane;
    final int handshakeVersion;
    final String fingerprint;
    final String variantId;
    final String probePassword;
    final boolean observer;
    /** The plan's endpoint came from the in-process reel replay door
     * ({@link ClientLaunchKnobs.PredictEndpoint#reelDoor}); such a plan carries a blank
     * {@link #seatToken} and a null {@link #probePassword} ({@link #credentialsFor}).
     * */
    final boolean reelDoor;

    PredictDialPlan(String host, int port, long shipId, int group, int attempts,
            String transport, String clientId, String seatToken, Handshake13Flight.Lane lane,
            int handshakeVersion, String fingerprint, String variantId, String probePassword,
            boolean observer, boolean reelDoor) {
        this.host = host;
        this.port = port;
        this.shipId = shipId;
        this.group = group;
        this.attempts = attempts;
        this.transport = transport;
        this.clientId = clientId;
        this.seatToken = seatToken;
        this.lane = lane;
        this.handshakeVersion = handshakeVersion;
        this.fingerprint = fingerprint;
        this.variantId = variantId;
        this.probePassword = probePassword;
        this.observer = observer;
        this.reelDoor = reelDoor;
    }

    /** The two credential fields a plan may carry toward its endpoint ({@link #credentialsFor}). */
    static final class Credentials {
        final String seatToken;
        final String probePassword;

        Credentials(String seatToken, String probePassword) {
            this.seatToken = seatToken;
            this.probePassword = probePassword;
        }
    }

    /**
     * The credentials the plan may hand to {@code endpoint}: a reel-tagged endpoint (the in-process
     * loopback replay door, an OPEN door that authenticates nothing) gets a blank seat credential
     * and NO probe password, so the typed match password never reaches it; every other endpoint
     * gets both unchanged. Keys on the TAG only — a live server on {@code 127.0.0.1} keeps its
     * credential. Pure; the planner calls it where the plan is built, never at the password read
     * (the AUTO gate and the token arm there must see the typed value). A blank seat credential
     * is legal only on an observer dial ({@code PredictDatagramDial}); a reel endpoint is observer-only.
     *
     *
     */
    static Credentials credentialsFor(ClientLaunchKnobs.PredictEndpoint endpoint, String seatToken,
            String probePassword) {
        if (endpoint.reelDoor) {
            return new Credentials("", null);
        }
        return new Credentials(seatToken, probePassword);
    }

    /**
     * The click-time provenance check: TRUE = the re-resolved endpoint's tag differs from the
     * plan's, so the plan's credentials were minted for the OTHER kind of door (a live plan's typed
     * password toward the replay door, or a blanked reel plan toward a live server) — the caller
     * neither dials nor probes. A null re-resolve keeps the plan's endpoint and tag (FALSE). Pure.
     *
     *
     */
    static boolean provenanceMoved(boolean planReelDoor, ClientLaunchKnobs.PredictEndpoint reResolved) {
        return reResolved != null && reResolved.reelDoor != planReelDoor;
    }
}
