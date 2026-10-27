package cc.starsector.client.net;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Client-side encoder for the declared-discharge fire lane's {@code PDCL} frame — the
 * sending half of the server's {@code FireDischargeDeclarationCodec}
 * ({@code client.fire_discharge.declared} v1).
 *
 * <p>One frame declares one discharge the client's own predicting engine actually EMITTED
 * (detected by the projectile scan in {@code PredictReconcileCombatPlugin}): a per-session
 * monotonic {@code declSeq}, the client's last-known authoritative sim tick (a logging/matching
 * stamp only), and the aim facing AT the discharge instant. The frame rides the pilot UP lane
 * immediately AFTER the tick's {@code PINT} intent frame; the server's {@code UpLaneDemux} routes
 * it apart by magic and the declared fire lane serves exactly these discharges.
 *
 * <p>This mirrors the server codec's exact layout so the mod speaks the registered message
 * without bundling {@code FireDischargeDeclarationCodec} — whose static type-code initializer
 * drags {@code NetworkMessageRegistry} + the whole message closure into the game JVM (the same
 * discipline as {@link ManeuverIntentWriter} / {@link AwssSnapshotReader}). The wire type code is
 * <em>derived the same way the server derives it</em> — CRC32 of the message id (see
 * {@code ProtocolRegistryEntry.wireTypeCode}) — rather than a copied magic constant, so it tracks
 * the registry id. The game-free verifier asserts
 * these constants equal {@code FireDischargeDeclarationCodec}'s, byte-compares both encoders, and
 * round-trips frames through the real {@code PilotIntentSocketServer.UpLaneDemux}; any drift
 * breaks it.
 *
 * <p>Allowlist-clean: builds directly into a fixed {@code byte[]} via
 * {@link WireBytes} big-endian ops + the allowlisted {@link CRC32} — no {@code Data*Stream} /
 * {@code ByteArrayOutputStream}, so it serializes under the in-game script classloader {@code B}.
 */
public final class FireDischargeDeclarationWriter {

    /** Frame magic ("PDCL"). Mirrors {@code FireDischargeDeclarationCodec.MAGIC}. */
    public static final int MAGIC = 0x5044434C;

    /** Registry message id this frame carries. Mirrors {@code ClientFireDischargeDeclarationMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "client.fire_discharge.declared";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = wireTypeCode(MESSAGE_ID);

    /** Registered schema version. Mirrors {@code ClientFireDischargeDeclarationMessage.SCHEMA_VERSION}. */
    public static final short SCHEMA_VERSION = 5; // v4: + muzzleX/muzzleY/bearingDeg/speed (committed shot's ray; all-NaN = none); v5: SEMANTIC bump, same 66 B layout — bearingDeg/speed are the SHIP-frame launch ray (fired bearing + relative launch speed), never the travel-frame velocity

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /**
     * shipId(long) + declSeq(int) + clientTick(long) + aimFacingDeg(float) + aimWorldX/Y(float×2,
     * v2) + mountIndex(int, v3) + muzzleX/muzzleY/bearingDeg/speed(float×4,
     * v4 mount-ray carry; all-NaN = no carried ray).
     */
    public static final int PAYLOAD_BYTES =
            Long.BYTES + Integer.BYTES + Long.BYTES + Float.BYTES + Float.BYTES + Float.BYTES
            + Integer.BYTES + Float.BYTES + Float.BYTES + Float.BYTES + Float.BYTES; // 52
    /** Fixed frame width: header + payload + CRC32 trailer. Mirrors {@code FireDischargeDeclarationCodec.FRAME_BYTES}. */
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 66

    private FireDischargeDeclarationWriter() {
    }

    /**
     * Stable 32-bit wire identity derived from the registry message id, byte-identical to the
     * server's {@code ProtocolRegistryEntry.wireTypeCode(String)} (CRC32 of the UTF-8 id).
     */
    public static int wireTypeCode(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must be non-blank");
        }
        CRC32 crc = new CRC32();
        byte[] bytes = messageId.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    /**
     * Encode one declaration as the fixed {@link #FRAME_BYTES}-byte {@code PDCL} frame, byte-identical
     * to {@code FireDischargeDeclarationCodec.encodeFrame}. Field constraints mirror the server record's
     * fail-closed validation ({@code FireDischargeDeclaration}) so the client can never emit a frame the
     * server would kill the connection over.
     */
    public static byte[] encodeFrame(long shipId, int declSeq, long clientTick, float aimFacingDeg,
            float aimWorldX, float aimWorldY, int mountIndex,
            float muzzleX, float muzzleY, float bearingDeg, float speed) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        if (declSeq < 0) {
            throw new IllegalArgumentException("declSeq must be >= 0; got " + declSeq);
        }
        if (clientTick < 0L) {
            throw new IllegalArgumentException("clientTick must be >= 0; got " + clientTick);
        }
        if (!Float.isFinite(aimFacingDeg)) {
            throw new IllegalArgumentException("aimFacingDeg must be finite; got " + aimFacingDeg);
        }
        if (!Float.isFinite(aimWorldX)) {
            throw new IllegalArgumentException("aimWorldX must be finite; got " + aimWorldX);
        }
        if (!Float.isFinite(aimWorldY)) {
            throw new IllegalArgumentException("aimWorldY must be finite; got " + aimWorldY);
        }
        if (mountIndex < -1) {
            throw new IllegalArgumentException("mountIndex must be >= -1 (-1 = unknown); got " + mountIndex);
        }
        // v4 ray law — mirrors FireDischargeDeclaration's ctor law exactly (fail-closed server
        // decode: one law violation would kill the whole session's socket): each ray field
        // finite-or-NaN (±Inf reject); all four finite (requires mountIndex >= 0) or all four NaN.
        if (Float.isInfinite(muzzleX) || Float.isInfinite(muzzleY)
                || Float.isInfinite(bearingDeg) || Float.isInfinite(speed)) {
            throw new IllegalArgumentException("ray fields must be finite or NaN; got muzzle=("
                    + muzzleX + "," + muzzleY + ") bearingDeg=" + bearingDeg + " speed=" + speed);
        }
        boolean anyFinite = !Float.isNaN(muzzleX) || !Float.isNaN(muzzleY)
                || !Float.isNaN(bearingDeg) || !Float.isNaN(speed);
        boolean allFinite = !Float.isNaN(muzzleX) && !Float.isNaN(muzzleY)
                && !Float.isNaN(bearingDeg) && !Float.isNaN(speed);
        if (anyFinite && !allFinite) {
            throw new IllegalArgumentException("carried ray must be all-finite or all-NaN; got"
                    + " muzzle=(" + muzzleX + "," + muzzleY + ") bearingDeg=" + bearingDeg
                    + " speed=" + speed);
        }
        if (allFinite && mountIndex < 0) {
            throw new IllegalArgumentException(
                    "carried ray requires a real mountIndex (>= 0); got " + mountIndex);
        }
        byte[] b = new byte[FRAME_BYTES];
        int off = WireBytes.putInt(b, 0, MAGIC);
        off = WireBytes.putInt(b, off, MESSAGE_TYPE_CODE);
        off = WireBytes.putShort(b, off, SCHEMA_VERSION);
        off = WireBytes.putLong(b, off, shipId);
        off = WireBytes.putInt(b, off, declSeq);
        off = WireBytes.putLong(b, off, clientTick);
        off = WireBytes.putInt(b, off, Float.floatToIntBits(aimFacingDeg));
        off = WireBytes.putInt(b, off, Float.floatToIntBits(aimWorldX)); // v2 aim carry
        off = WireBytes.putInt(b, off, Float.floatToIntBits(aimWorldY)); // v2 aim carry
        off = WireBytes.putInt(b, off, mountIndex); // v3 mount identity
        off = WireBytes.putInt(b, off, Float.floatToIntBits(muzzleX)); // v4 ray carry
        off = WireBytes.putInt(b, off, Float.floatToIntBits(muzzleY)); // v4
        off = WireBytes.putInt(b, off, Float.floatToIntBits(bearingDeg)); // v4
        off = WireBytes.putInt(b, off, Float.floatToIntBits(speed)); // v4
        // off now == HEADER_BYTES + PAYLOAD_BYTES; CRC32 covers exactly that body, like PINT.
        CRC32 crc = new CRC32();
        crc.update(b, 0, off);
        WireBytes.putInt(b, off, (int) crc.getValue());
        return b;
    }
}
