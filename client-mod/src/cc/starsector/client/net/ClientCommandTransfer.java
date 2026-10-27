package cc.starsector.client.net;

/**
 * The decoded payload of one {@code XFER} command-transfer control frame (W3 L3;
 * packet {@code w3-command-transfer-packet.md}): the server narrating one edge of a
 * seat's {@code /transfer} — BEGIN (a command-shuttle flight minted toward {@code toWireId}),
 * DENY (the row died with NO flight), COMMIT (the dock-gated re-key executed — MMNT stale-map
 * semantics: {@code fromWireId} = the source hull's FRESH above-band id, {@code toWireId} =
 * the target's RETIRING old id; the seat's fixed wire id re-points), ABORT (a flight existed
 * and died; the pod turns home). {@code podWireId}: REAL on
 * BEGIN and the abort classes — the pod rides the wire as a roster row named
 * {@link #POD_WIRE_VARIANT_ID}; COMMIT keeps {@code 0} (the pod despawns with it), and the
 * TIMEOUT abort also carries {@code 0} (the server's accepted phase-filter edge).
 * The record admits any ≥0 value — the schema never changed. The apply/own-view leg is
 * {@code CommandTransferLane}.
 *
 * <p><b>ONE-ENTITY from-law (server {@code CommandTransferServeLane}):</b>
 * the seat wire id S IS the pod for the whole flight, so {@code podWireId == seatWireId} on
 * BEGIN/ABORT and {@code 0} on COMMIT; the vacated source rides a FRESH id F. Per kind:
 * BEGIN {@code from = F} (fresh flight), {@code from = S} = RESUME from a stranded pod body
 * or a RETURNING pod re-targeted in place with no live source;
 * COMMIT {@code from = F} (target landing) or {@code from = S} (pod-body flight —
 * "nothing else survives"); DOCK-BACK = a COMMIT with {@code to == F};
 * ABORT {@code from = F} = RETURNING (a dock-back COMMIT follows), {@code from = S} = STRANDED /
 * NONE / TIMEOUT (nothing follows) — the client's ONLY returning-vs-stranded discriminator
 * ({@code fanAbortEnd}). The codec rejects {@code from < 1}, so {@code from} is
 * never 0 on any fanned frame.
 *
 * <p>Like {@link ClientModuleMount}, a small client-owned mirror of the server protocol class
 * ({@code cc.starsector.server.protocol.CommandTransferControlMessage}); the invariants match
 * the server codec's {@code TransferControl} compact constructor so a malformed frame fails
 * closed identically ({@link ClientCommandTransferReader} translates to
 * {@link java.io.IOException}).
 */
public record ClientCommandTransfer(long seatWireId, long fromWireId, long toWireId,
        long podWireId, int kind, int reason) {

    /** W3 L4 S1: the pod's CLIENT-resolvable wire variant — the id the
     * server's RAPP row names for the command-shuttle pod (the kit ships the variant file).
     * Deliberate constant PAIR with {@code cc.starsector.server.runtime
     * .CommandTransferServeLane.POD_WIRE_VARIANT_ID} (javadoc cross-ref, not a parity-regex
     * site). The replica spawn branch keys on it and splits by row kind (W3-P):
     * a SEAT row re-pointed to this variant = the fielded respawn pod, collidable
     * SHIP like the server's body; an appended podWireId row = the /transfer flight replica,
     * CollisionClass.NONE both sides. Both take the native animated-launch pop-in. */
    public static final String POD_WIRE_VARIANT_ID = "shuttlepod_cc_pod";

    /** Mirrors {@code CommandTransferFrameCodec.KIND_BEGIN}. */
    public static final int KIND_BEGIN = 1;
    /** Mirrors {@code CommandTransferFrameCodec.KIND_DENY}. */
    public static final int KIND_DENY = 2;
    /** Mirrors {@code CommandTransferFrameCodec.KIND_COMMIT}. */
    public static final int KIND_COMMIT = 3;
    /** Mirrors {@code CommandTransferFrameCodec.KIND_ABORT}. */
    public static final int KIND_ABORT = 4;

    /** Mirrors the server codec's {@code REASON_*} vocabulary (values 1..14 + 255; 10..12 =
     * the W3 L4 S3 dispatch-DENY pre-pod refusal band; 13 = the W3-FIX
     * generic fresh-begin refusal fanned on ALL pre-pod denials; 14 = the
     * return-home soft abort — log-only, abortOwn needs no branch). */
    public static final int REASON_NONE = 0;
    public static final int REASON_UNAVAILABLE = 1;
    public static final int REASON_TIMEOUT = 2;
    public static final int REASON_WINNER_INELIGIBLE = 3;
    public static final int REASON_TARGET_INELIGIBLE = 4;
    public static final int REASON_TARGET_DESTROYED = 5;
    public static final int REASON_COMMIT_RACE = 6;
    public static final int REASON_ENGINE_DENY = 7;
    public static final int REASON_RACE_LOST = 8;
    public static final int REASON_PILOT_LEFT = 9;
    public static final int REASON_TARGET_CLAIMED = 10;
    public static final int REASON_RATE_LIMITED = 11;
    public static final int REASON_HULL_COOLDOWN = 12;
    public static final int REASON_FRESH_DENIED = 13;
    public static final int REASON_RETURN_HOME = 14;
    public static final int REASON_OTHER = 255;

    public ClientCommandTransfer {
        if (kind < KIND_BEGIN || kind > KIND_ABORT) {
            throw new IllegalArgumentException(
                    "kind must be 1 (begin), 2 (deny), 3 (commit) or 4 (abort), got " + kind);
        }
        if (kind == KIND_DENY || kind == KIND_ABORT) {
            if (reason < REASON_UNAVAILABLE
                    || (reason > REASON_RETURN_HOME && reason != REASON_OTHER)) {
                throw new IllegalArgumentException(
                        "deny/abort reason must be 1..14 or 255, got " + reason);
            }
        } else if (reason != REASON_NONE) {
            throw new IllegalArgumentException(
                    "begin/commit carry no reason (must be 0), got " + reason);
        }
        if (seatWireId < 1L) {
            throw new IllegalArgumentException("seatWireId must be positive: " + seatWireId);
        }
        if (fromWireId < 1L) {
            throw new IllegalArgumentException("fromWireId must be positive: " + fromWireId);
        }
        if (toWireId < 0L) {
            throw new IllegalArgumentException("toWireId must be non-negative: " + toWireId);
        }
        if ((kind == KIND_BEGIN || kind == KIND_COMMIT) && toWireId == 0L) {
            throw new IllegalArgumentException(
                    "begin/commit require a positive toWireId (got 0)");
        }
        if (podWireId < 0L) {
            throw new IllegalArgumentException("podWireId must be non-negative: " + podWireId);
        }
    }

    public boolean isBegin() {
        return kind == KIND_BEGIN;
    }

    public boolean isCommit() {
        return kind == KIND_COMMIT;
    }
}
