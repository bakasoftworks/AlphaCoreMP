package cc.starsector.client.net;

import java.util.Objects;

/**
 * The decoded payload of one {@code MMNT} module-mount handover control frame (module
 * piloting): the server
 * repointing a seat's fixed wire id between its hull and a claimed station module.
 *
 * <p>Kind 1 = MOUNT — the seat's fixed wire id now drives the module body;
 * {@code moduleOldWireId} is the module's retiring above-band id, or {@code 0}, the
 * admission-replay sentinel (the server does not retain the retired id, so the catch-up replay
 * cannot carry it). The client resolves the module body by {@code slotToken} via the station
 * cluster's slot census — the old id is stale-map cleanup ONLY, never resolution. Kind 2 =
 * DISMOUNT — the module returns to server AI under {@code moduleOldWireId}, its FRESH
 * above-band id (required &gt; 0); the fresh id is out-of-band for the adopt lane's band gate,
 * so the client re-binds the cluster row directly (the explicit re-adopt seam).
 *
 * <p>Wire order law: a live MMNT is fanned BEFORE the paired {@code RAPP} row, and the
 * admission replay lands BEFORE the RAPP catch-up, so the roster lane's mounted-seat guard is
 * warm when the seat's roster row arrives.
 *
 * <p>Like {@link ClientRosterAppend}, a small client-owned mirror of the server protocol class
 * ({@code cc.starsector.server.protocol.ModuleMountControlMessage}); the invariants match the
 * server codec's {@code MountHandover} compact constructor so a malformed frame fails closed
 * identically ({@link ClientModuleMountReader} translates to {@link java.io.IOException}).
 */
public record ClientModuleMount(long seatWireId, long moduleOldWireId, int kind,
        String slotToken) {

    /** Mirrors {@code ModuleMountFrameCodec.KIND_MOUNT}. */
    public static final int KIND_MOUNT = 1;
    /** Mirrors {@code ModuleMountFrameCodec.KIND_DISMOUNT}. */
    public static final int KIND_DISMOUNT = 2;
    /** Mirrors {@code ModuleMountFrameCodec.MAX_TOKEN_CHARS}. */
    public static final int MAX_TOKEN_CHARS = 24;

    public ClientModuleMount {
        Objects.requireNonNull(slotToken, "slotToken");
        if (kind != KIND_MOUNT && kind != KIND_DISMOUNT) {
            throw new IllegalArgumentException("kind must be 1 (mount) or 2 (dismount), got "
                    + kind);
        }
        if (seatWireId < 1L) {
            throw new IllegalArgumentException("seatWireId must be positive: " + seatWireId);
        }
        if (moduleOldWireId < 0L) {
            throw new IllegalArgumentException(
                    "moduleOldWireId must be non-negative: " + moduleOldWireId);
        }
        if (kind == KIND_DISMOUNT && moduleOldWireId == 0L) {
            throw new IllegalArgumentException(
                    "dismount requires the module's fresh above-band id (got 0)");
        }
        if (slotToken.isBlank() || slotToken.length() > MAX_TOKEN_CHARS) {
            throw new IllegalArgumentException("slotToken must be non-blank and at most "
                    + MAX_TOKEN_CHARS + " chars");
        }
        for (int i = 0; i < slotToken.length(); i++) {
            char c = slotToken.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new IllegalArgumentException(
                        "slotToken must be single-line printable ASCII");
            }
        }
    }

    public boolean isMount() {
        return kind == KIND_MOUNT;
    }

    public boolean isDismount() {
        return kind == KIND_DISMOUNT;
    }
}
