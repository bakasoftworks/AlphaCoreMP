package cc.starsector.server.runtime;

/**
 * The minimal per-tick world view the server streams DOWN to a remote pilot client so the client can
 * compute its own faithful aim (the live two-client 1v1, slice 1: a
 * separate remote client process).
 *
 * <p>An earlier stage proved the faithful pilot lane crossing a real off-loopback socket as a
 * single-JVM round-trip: the server itself built each {@link PilotIntentFrame} (including the aim it
 * computed from the engine) and read it straight back. The product splits the write end into a real
 * remote process — but the pilot's aim is the player's cursor tracking the enemy they SEE, i.e. it is
 * computed from the replicated world, not handed down ready-made. A remote process has no engine, so
 * to keep the aim genuinely the client's (and faithful), the server sends this tiny view of the two
 * ship poses each tick and the client computes the bearing itself via {@link #bearingToTargetDeg()}.
 * The client then streams a full {@link PilotIntentFrame} back up the SAME unchanged
 * {@code PilotIntentWireCodec}. This is the product's real loop: snapshot down, intent up.
 *
 * <p>{@link #bearingToTargetDeg()} is the single shared bearing computation used by BOTH ends (and it
 * mirrors the scout's {@code facingToTarget} exactly: float deltas, {@link Math#atan2}, degrees,
 * normalized to [0,360)). Because both the server's expectation and the client's intent flow through
 * this one method over the identical pose floats that crossed the wire, the aim is bit-identical
 * across the two processes — the cross-process faithfulness check is byte-exact, not merely close.
 *
 * <p><b>Authoritative facing + fire (schema v2).</b> The
 * view also carries each ship's server-authoritative {@code getFacing()} ({@link #srcFacingDeg} /
 * {@link #tgtFacingDeg}) and whether that ship's weapon is firing this tick ({@link #srcFiring} /
 * {@link #tgtFiring}). These are pure engine reads (no fake values). They exist so the two-window
 * view renders the REAL feel: the own ship at the engine's turn-rate-limited heading (not an instant
 * snap to the cursor) and the foe at its true heading (not the best-effort "bearing back at the player"
 * the position-only view forced), and so each window can present the Hellbore actually discharging.
 * The bearing-from-positions aim ({@link #bearingToTargetDeg()}) is unchanged and still drives the
 * cross-process aim byte-identity check — the facing/fire fields are render data only, never the aim.
 *
 * <p><b>Authoritative hull (schema v3).</b> The
 * view also carries each ship's server-authoritative hull fraction ({@link #srcHullFraction} /
 * {@link #tgtHullFraction}, the engine's own {@code getHullLevel()} in {@code [0,1]}). A live run
 * exposed the blocker this closes: the live duel landed NO in-window damage because the
 * server runs the REAL collision/damage (proven: {@code gate_server_intent_fire_summary
 * target_killed=true}) but only ship POSE (position + facing) was replicated DOWN — the foe rendered as
 * a non-collidable pose-ghost ({@link PilotWorldViewFrame#targetAlive()} was hardcoded {@code true},
 * with no hull field), so the player "couldn't damage it" and it never died on screen. Streaming the
 * authoritative hull DOWN lets each window MIRROR the server's real damage via the engine's own
 * {@code ShipAPI.setHitpoints} (pure snapshot replication — NOT a client-authoritative
 * re-sim, and NOT local collision, which would make the client the damage authority — a non-goal). The
 * hull fractions are render data only, never the aim.
 */
public record PilotWorldViewFrame(
        int frameSeq,
        long sourceShipId,
        float srcX,
        float srcY,
        float tgtX,
        float tgtY,
        float srcFacingDeg,
        float tgtFacingDeg,
        float srcHullFraction,
        float tgtHullFraction,
        boolean targetAlive,
        boolean srcFiring,
        boolean tgtFiring,
        boolean terminal) {

    public PilotWorldViewFrame {
        if (frameSeq < 0) {
            throw new IllegalArgumentException("frameSeq must be >= 0; got " + frameSeq);
        }
        if (sourceShipId < 0L) {
            throw new IllegalArgumentException("sourceShipId must be >= 0; got " + sourceShipId);
        }
        if (!Float.isFinite(srcX) || !Float.isFinite(srcY) || !Float.isFinite(tgtX) || !Float.isFinite(tgtY)) {
            throw new IllegalArgumentException("pose coordinates must be finite");
        }
        if (!Float.isFinite(srcFacingDeg) || !Float.isFinite(tgtFacingDeg)) {
            throw new IllegalArgumentException("facing degrees must be finite");
        }
        if (!Float.isFinite(srcHullFraction) || !Float.isFinite(tgtHullFraction)) {
            throw new IllegalArgumentException("hull fractions must be finite");
        }
    }

    /**
     * The faithful aim the remote pilot computes from this view: the bearing (degrees, [0,360)) from
     * the source ship to the target. Mirrors the scout's {@code facingToTarget} exactly so the server's
     * own bearing and the client's reconstructed aim are bit-identical over the same pose floats.
     */
    public float bearingToTargetDeg() {
        float dy = tgtY - srcY;
        float dx = tgtX - srcX;
        return (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 360.0d) % 360.0d);
    }
}
