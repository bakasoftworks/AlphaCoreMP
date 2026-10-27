package cc.starsector.client.net;

import java.util.zip.CRC32;

/**
 * Client-side encoder for the {@code PSTE} posture echo frame
 * — the sending half of the server's
 * {@code PostureFrameCodec} ({@code client.posture_echo} v1): sent exactly ONCE at attach on the
 * CTRL lane (the {@link SeatNameWriter} announce precedent), echoing the client's RESOLVED
 * posture as computed by the bundled {@code ClientPostureRegistry} — row/unknown counts plus
 * BOTH 32-byte digests (full + behavior; the server's verdicts key on the BEHAVIOR hash,
 * instruments legitimately vary per-client). The server compares against its expected baseline
 * from the SAME fingerprint-locked registry class: a mismatch is machine proof this client was
 * locally re-postured (or a real bug) — the loud {@code posture-mismatch} row, detection-tier v1.
 * The receiving half of the posture pair is {@link ClientPostureBlockReader}.
 *
 * <p>Mirrors {@code cc.starsector.server.runtime.PostureFrameCodec.encodeEcho} byte-for-byte
 * (the mod does not bundle the codec — its static type-code initializers drag
 * {@code NetworkMessageRegistry} into the game JVM; the {@link FireDischargeDeclarationWriter}
 * discipline). The wire type code is derived, never copied — CRC32 of the message id via
 * {@link FireDischargeDeclarationWriter#wireTypeCode}. The game-free verifier
 * byte-compares both encoders; any drift
 * breaks it.
 *
 * <p>Allowlist-clean: fixed {@code byte[]} via {@link WireBytes} + the allowlisted
 * {@link CRC32} — no {@code Data*Stream}, so it serializes under the in-game script
 * classloader {@code B}.
 */
public final class PostureEchoWriter {

    /** Frame magic: ASCII "PSTE". Mirrors {@code PostureFrameCodec.ECHO_MAGIC}. */
    public static final int MAGIC = 0x50535445;

    /** Registry message id this frame carries. Mirrors {@code ClientPostureEchoMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "client.posture_echo";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);

    /** Registered schema version. Mirrors {@code ClientPostureEchoMessage.SCHEMA_VERSION}. */
    public static final short SCHEMA_VERSION = 1;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** Raw digest width (SHA-256), both hashes. Mirrors {@code PostureFrameCodec.HASH_BYTES}. */
    public static final int HASH_BYTES = 32;
    /** rowCount(u16) + unknownCount(u16) + full hash + behavior hash. */
    public static final int PAYLOAD_BYTES = Short.BYTES + Short.BYTES + 2 * HASH_BYTES; // 68
    /** Fixed frame width. Mirrors {@code PostureFrameCodec.ECHO_FRAME_BYTES}. */
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 82

    /** Echo count sanity bounds. Mirror {@code PostureFrameCodec.ROW_COUNT_MIN}/{@code COUNT_MAX}. */
    public static final int ROW_COUNT_MIN = 1;
    public static final int COUNT_MAX = 4096;

    private PostureEchoWriter() {
    }

    /**
     * Encode one posture echo as the fixed {@link #FRAME_BYTES}-byte {@code PSTE} frame,
     * byte-identical to {@code PostureFrameCodec.encodeEcho}. Validates so the client can never
     * emit a frame the server's fail-closed decode would kill the session over (the post-admit
     * CTRL law: corrupt CTRL = failSession LOUD).
     */
    public static byte[] encodeFrame(int rowCount, int unknownCount, byte[] fullHash,
            byte[] behaviorHash) {
        if (rowCount < ROW_COUNT_MIN || rowCount > COUNT_MAX) {
            throw new IllegalArgumentException("rowCount must be " + ROW_COUNT_MIN + ".."
                    + COUNT_MAX + "; got " + rowCount);
        }
        if (unknownCount < 0 || unknownCount > COUNT_MAX) {
            throw new IllegalArgumentException(
                    "unknownCount must be 0.." + COUNT_MAX + "; got " + unknownCount);
        }
        requireHash(fullHash, "fullHash");
        requireHash(behaviorHash, "behaviorHash");
        byte[] b = new byte[FRAME_BYTES];
        int off = WireBytes.putInt(b, 0, MAGIC);
        off = WireBytes.putInt(b, off, MESSAGE_TYPE_CODE);
        off = WireBytes.putShort(b, off, SCHEMA_VERSION);
        off = WireBytes.putShort(b, off, (short) rowCount);
        off = WireBytes.putShort(b, off, (short) unknownCount);
        System.arraycopy(fullHash, 0, b, off, HASH_BYTES);
        off += HASH_BYTES;
        System.arraycopy(behaviorHash, 0, b, off, HASH_BYTES);
        off += HASH_BYTES;
        // off now == HEADER_BYTES + PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(b, 0, off);
        WireBytes.putInt(b, off, (int) crc.getValue());
        return b;
    }

    private static void requireHash(byte[] hash, String what) {
        if (hash == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        if (hash.length != HASH_BYTES) {
            throw new IllegalArgumentException(
                    what + " must be " + HASH_BYTES + " bytes; got " + hash.length);
        }
    }
}
