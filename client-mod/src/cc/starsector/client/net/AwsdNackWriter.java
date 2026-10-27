package cc.starsector.client.net;

import java.util.zip.CRC32;

/**
 * Client-side encoder for the AWSD delta-reject NACK's {@code SNNK} frame — the
 * sending half of the server's {@code AwsdNackFrameCodec} ({@code client.world_snapshot.awsd_nack}
 * v1, the D7 NACK-absolute law).
 *
 * <p>One frame names one entity whose AWSD DELTA the {@link SnapshotChunkMerger} rejected
 * hold-last (missing anchor after a lost keyframe, or the D5 {@code applyDelta} drop signal);
 * the server answers with an out-of-turn KEYFRAME for that entity at its next compose — recovery
 * ≤ RTT + 1 tick instead of the ≤E-tick round-robin wait. The epoch is ADVISORY (diagnostics
 * only): all trigger sites carry the REJECTED delta's epoch; the server re-keys unconditionally.
 * The frame rides the reliable CTRL up lane (RTO ×2 + SACK fast-retransmit: a sent NACK is
 * delivered or the session dies, so ONE send per (id, epoch) suffices — no timer).
 *
 * <p>This mirrors the server codec's exact layout so the mod speaks the registered message
 * without bundling {@code AwsdNackFrameCodec} — whose static type-code initializer drags
 * {@code NetworkMessageRegistry} + the whole message closure into the game JVM (the
 * {@link FireDischargeDeclarationWriter} / {@link SeatNameWriter} discipline). The wire type
 * code is derived the same way the server derives it — CRC32 of the message id via
 * {@link FireDischargeDeclarationWriter#wireTypeCode} — never a copied constant. The game-free
 * verifier ({@code CcClientAwsdDecodeVerifier} Part F) asserts these constants equal
 * {@code AwsdNackFrameCodec}'s, byte-compares both encoders, and decodes writer bytes through
 * the server codec; any drift breaks it.
 *
 * <p>Allowlist-clean: fixed {@code byte[]} via {@link WireBytes} big-endian ops +
 * the allowlisted {@link CRC32} — no {@code Data*Stream}, so it serializes under the in-game
 * script classloader {@code B}.
 */
public final class AwsdNackWriter {

    /** Frame magic: ASCII "SNNK" (SNapshot NacK). Mirrors {@code AwsdNackFrameCodec.MAGIC}. */
    public static final int MAGIC = 0x534E4E4B;

    /** Registry message id this frame carries. Mirrors {@code ClientAwsdNackMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "client.world_snapshot.awsd_nack";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);

    /** Registered schema version. Mirrors {@code ClientAwsdNackMessage.SCHEMA_VERSION}. */
    public static final short SCHEMA_VERSION = 1;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** entityId(u16) + rejected-delta epoch(u8). */
    public static final int PAYLOAD_BYTES = Short.BYTES + Byte.BYTES; // 3
    /** Fixed frame width: header + payload + CRC32 trailer. Mirrors {@code AwsdNackFrameCodec.FRAME_BYTES}. */
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 17

    private AwsdNackWriter() {
    }

    /**
     * Encode one NACK as the fixed {@link #FRAME_BYTES}-byte {@code SNNK} frame, byte-identical
     * to {@code AwsdNackFrameCodec.encodeFrame}. Domain constraints mirror the server record's
     * fail-closed validation ({@code AwsdNackFrameCodec.AwsdNack}) so the client can never emit
     * a frame the server's decode would kill the connection over. CRC32 covers exactly the
     * header + payload including the magic, like PDCL/SNAM.
     */
    public static byte[] encodeFrame(long entityId, int epoch) {
        if (entityId < 0L || entityId > 0xFFFFL) {
            throw new IllegalArgumentException(
                    "awsd nack entityId outside u16 [0,65535]: " + entityId);
        }
        if (epoch < 0 || epoch > 0xFF) {
            throw new IllegalArgumentException(
                    "awsd nack epoch outside u8 [0,255]: " + epoch);
        }
        byte[] b = new byte[FRAME_BYTES];
        int off = WireBytes.putInt(b, 0, MAGIC);
        off = WireBytes.putInt(b, off, MESSAGE_TYPE_CODE);
        off = WireBytes.putShort(b, off, SCHEMA_VERSION);
        off = WireBytes.putShort(b, off, (short) entityId);
        off = WireBytes.putByte(b, off, epoch);
        // off now == HEADER_BYTES + PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(b, 0, off);
        WireBytes.putInt(b, off, (int) crc.getValue());
        return b;
    }
}
