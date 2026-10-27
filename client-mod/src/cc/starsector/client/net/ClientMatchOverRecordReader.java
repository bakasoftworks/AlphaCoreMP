package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

/**
 * Client-side decoder for the server's {@code MOVR} serve-end match verdict wire frame:
 * the receiving half of the server's {@code MatchOverFrameCodec}
 * ({@code server.match_over_record} v1). The server fans at most one per serve on the reliable
 * CTRL lane, before the goodbye BYE; the {@link MultiplexedServerStreamReader} routes it here
 * by its leading magic.
 *
 * <p>Like {@link ClientSeatNameRecordReader} and {@link ClientHulkFractureRecordReader}, the
 * mod deliberately does not bundle the server codec (whose static type-code initializer drags
 * {@code NetworkMessageRegistry} into the game JVM). It reads exactly the fixed-width 24 bytes
 * the server writes — magic / wireTypeCode / schema / simTick(i64) / outcome(u8) /
 * winnerSide(u8, 0xFF = no victor) / CRC32-over-body — over plain {@link InputStream}
 * primitives plus the allowlisted {@link CRC32}. The game-free cross-check streams real server
 * {@code MatchOverFrameCodec} bytes through this reader and asserts the decoded records match,
 * keeping the mirrors in sync.
 *
 * <p>Fail-closed, mirroring the server decode exactly: a wire-type-code or schema mismatch, a
 * CRC32 mismatch, or any field outside the {@link ClientMatchOverRecord} guards (an outcome
 * outside 1..6, a winnerSide other than -1/0/1) all throw {@link IOException}.
 */
public final class ClientMatchOverRecordReader {

    /** Frame magic: ASCII "MOVR". Mirrors {@code MatchOverFrameCodec.MATCH_OVER_MAGIC}. */
    public static final int MAGIC = 0x4D4F5652;

    /** Registry message id this frame carries. Mirrors {@code ServerMatchOverRecordMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "server.match_over_record";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);

    /** Schema version this reader understands. Mirrors {@code ServerMatchOverRecordMessage.SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 1;

    /** magic(int) + typeCode(int) + schema(short). Mirrors {@code MatchOverFrameCodec.HEADER_BYTES}. */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** simTick(long) + outcome/winnerSide (u8 ×2). Mirrors
     * {@code MatchOverFrameCodec.MATCH_OVER_PAYLOAD_BYTES}. */
    public static final int PAYLOAD_BYTES = Long.BYTES + 2; // 10
    /** Fixed frame width. Mirrors {@code MatchOverFrameCodec.MATCH_OVER_FRAME_BYTES}. */
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 24

    /** No-victor wire byte for winnerSide -1 (u8 lane; 0/1 ride verbatim). Mirrors the server
     * codec's private {@code WINNER_NONE_WIRE}. */
    private static final int WINNER_NONE_WIRE = 0xFF;

    private ClientMatchOverRecordReader() {
    }

    /**
     * Decode one match-over frame whose 4-byte {@link #MAGIC} has <em>already</em> been read
     * and validated by the caller — the {@link MultiplexedServerStreamReader} entry point. The
     * CRC32 covers the frame INCLUDING the magic (the server codec checksums {@code [0, 20)} of
     * the full frame), so this reconstructs the full frame buffer before validating.
     */
    public static ClientMatchOverRecord readAfterMagic(InputStream in) throws IOException {
        byte[] frame = new byte[FRAME_BYTES];
        WireBytes.putInt(frame, 0, MAGIC);
        byte[] rest = new byte[FRAME_BYTES - 4];
        WireBytes.readFully(in, rest); // mid-frame EOF throws — the magic is already consumed
        System.arraycopy(rest, 0, frame, 4, rest.length);
        return decodeFrame(frame);
    }

    /** Decode + validate one full {@code MOVR} frame — mirrors {@code MatchOverFrameCodec.decode}. */
    public static ClientMatchOverRecord decodeFrame(byte[] frame) throws IOException {
        if (frame == null || frame.length != FRAME_BYTES) {
            throw new IOException("match-over frame must be " + FRAME_BYTES + " bytes, got "
                    + (frame == null ? "null" : frame.length));
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = WireBytes.getInt(frame, off);
        off += 4;
        if (magic != MAGIC) {
            throw new IOException("match-over bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = WireBytes.getInt(frame, off);
        off += 4;
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("match-over wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = ((frame[off] & 0xff) << 8) | (frame[off + 1] & 0xff);
        off += 2;
        if (schema != SCHEMA_VERSION) {
            throw new IOException("match-over unsupported schema version: " + schema);
        }
        long simTick = WireBytes.getLong(frame, off);
        off += 8;
        int outcome = frame[off++] & 0xFF;
        int rawWinner = frame[off++] & 0xFF;
        int winnerSide = rawWinner == WINNER_NONE_WIRE ? -1 : rawWinner;
        int actualCrc = WireBytes.getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("match-over frame CRC mismatch");
        }
        try {
            return new ClientMatchOverRecord(simTick, outcome, winnerSide);
        } catch (IllegalArgumentException badFields) {
            throw new IOException(
                    "match-over fields invalid: " + badFields.getMessage(), badFields);
        }
    }
}
