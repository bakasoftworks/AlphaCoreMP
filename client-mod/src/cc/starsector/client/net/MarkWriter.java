package cc.starsector.client.net;

import java.util.zip.CRC32;

/**
 * Client-side encoder for the F9 owner-mark capture's {@code MARK} frame — the sending half of
 * the server's {@code MarkFrameCodec} ({@code client.debug.owner_mark} v1).
 *
 *
 * <p>One frame = one F9 press: the client's running mark count + its last snapshot sim tick
 * ({@code -1} = none yet). The server prints one {@code owner-marker seat=<n> #<k> clientTick=
 * serverTick=} row on the serve log — a public stranger's mark reaches us even when their
 * sidelog never does. NO text rides it. Rides the reliable CTRL up lane (RTO ×2 + SACK
 * fast-retransmit: a sent mark is delivered or the session dies).
 *
 * <p>This mirrors the server codec's exact layout so the mod speaks the registered message
 * without bundling {@code MarkFrameCodec} — whose static type-code initializer drags
 * {@code NetworkMessageRegistry} + the whole message closure into the game JVM (the
 * {@link AwsdNackWriter} / {@link SeatNameWriter} discipline). The wire type code is derived
 * the same way the server derives it — CRC32 of the message id via
 * {@link FireDischargeDeclarationWriter#wireTypeCode} — never a copied constant. The game-free
 * verifier ({@code CcClientAwsdDecodeVerifier} Part G) asserts these constants equal
 * {@code MarkFrameCodec}'s, byte-compares both encoders, and decodes writer bytes through the
 * server codec; any drift breaks it.
 *
 * <p>Allowlist-clean: fixed {@code byte[]} via {@link WireBytes} big-endian ops +
 * the allowlisted {@link CRC32} — no {@code Data*Stream}, so it serializes under the in-game
 * script classloader {@code B}.
 */
public final class MarkWriter {

    /** Frame magic: ASCII "MARK". Mirrors {@code MarkFrameCodec.MAGIC}. */
    public static final int MAGIC = 0x4D41524B;

    /** Registry message id this frame carries. Mirrors {@code ClientOwnerMarkMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "client.debug.owner_mark";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);

    /** Registered schema version. Mirrors {@code ClientOwnerMarkMessage.SCHEMA_VERSION}. */
    public static final short SCHEMA_VERSION = 1;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** count(u16) + clientTick(i64). */
    public static final int PAYLOAD_BYTES = Short.BYTES + Long.BYTES; // 10
    /** Fixed frame width: header + payload + CRC32 trailer. Mirrors {@code MarkFrameCodec.FRAME_BYTES}. */
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 24

    /** Sentinel clientTick: no snapshot seen yet. Mirrors {@code MarkFrameCodec.NO_SNAPSHOT_TICK}. */
    public static final long NO_SNAPSHOT_TICK = -1L;

    private MarkWriter() {
    }

    /**
     * Encode one mark as the fixed {@link #FRAME_BYTES}-byte {@code MARK} frame, byte-identical
     * to {@code MarkFrameCodec.encodeFrame}. Domain constraints mirror the server record's
     * fail-closed validation ({@code MarkFrameCodec.OwnerMark}: count u16 ≥1, tick ≥ -1) so the
     * client can never emit a frame the server's decode would kill the connection over. CRC32
     * covers exactly the header + payload including the magic, like SNNK/PDCL/SNAM.
     */
    public static byte[] encodeFrame(int count, long clientTick) {
        if (count < 1 || count > 0xFFFF) {
            throw new IllegalArgumentException(
                    "owner mark count outside u16 [1,65535]: " + count);
        }
        if (clientTick < NO_SNAPSHOT_TICK) {
            throw new IllegalArgumentException(
                    "owner mark clientTick below the -1 no-snapshot sentinel: " + clientTick);
        }
        byte[] b = new byte[FRAME_BYTES];
        int off = WireBytes.putInt(b, 0, MAGIC);
        off = WireBytes.putInt(b, off, MESSAGE_TYPE_CODE);
        off = WireBytes.putShort(b, off, SCHEMA_VERSION);
        off = WireBytes.putShort(b, off, (short) count);
        off = WireBytes.putLong(b, off, clientTick);
        // off now == HEADER_BYTES + PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(b, 0, off);
        WireBytes.putInt(b, off, (int) crc.getValue());
        return b;
    }
}
