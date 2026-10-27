package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

/**
 * Client-side decoder for the server's {@code HKFR} hulk-fracture edge wire frame (the
 * hulk-split sync): the receiving half of the server's {@code HulkFractureFrameCodec}
 * ({@code server.hulk_fracture_record} v3 — the REMOVE_DESTROYED kind; deathTick-keyed
 * families since v2). The server's
 * remnant-family detector fans one on every authoritative fracture edge (a corpse split into a
 * new piece, or a piece removed) on the reliable CTRL lane — the {@code SNMR} fan shape, NO
 * admission catch-up (a newcomer's world contains no corpses); the
 * {@link MultiplexedServerStreamReader} routes it here by its leading magic.
 *
 * <p>Like {@link ClientSeatNameRecordReader}, the mod deliberately does not bundle the server
 * codec (whose static type-code initializers drag {@code NetworkMessageRegistry} into the game
 * JVM). It reads exactly the fixed-width 41 bytes the server writes — magic / wireTypeCode /
 * schema / simTick(i64) / rootEntityId(i64) / deathTick(i64) / edgeKind(u8) / sizeRank(u8) /
 * familyPieceCount(u8) / CRC32-over-body — over plain {@link InputStream} primitives plus the
 * allowlisted {@link CRC32}. The game-free cross-check ({@code CcClientSeatNameVerifier}'s HKFR
 * legs) streams real server {@code HulkFractureFrameCodec} bytes through this reader and asserts
 * the decoded records match, keeping the mirrors in sync.
 *
 * <p>Fail-closed, mirroring the server decode exactly: a wire-type-code or schema mismatch, a
 * CRC32 mismatch, or any field outside the {@link ClientHulkFractureRecord} guards (rootEntityId
 * &lt; 1, an unknown edgeKind, a nonzero sizeRank on an ADD) all throw {@link IOException}.
 */
public final class ClientHulkFractureRecordReader {

    /** Frame magic: ASCII "HKFR". Mirrors {@code HulkFractureFrameCodec.FRACTURE_MAGIC}. */
    public static final int MAGIC = 0x484B4652;

    /** Registry message id this frame carries. Mirrors {@code ServerHulkFractureRecordMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "server.hulk_fracture_record";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);

    /** Schema version this reader understands. Mirrors {@code ServerHulkFractureRecordMessage.SCHEMA_VERSION}.
     * v4 = the ROOT_DETACHED kind (layout unchanged, like v2→v3). */
    public static final int SCHEMA_VERSION = 4;

    /** magic(int) + typeCode(int) + schema(short). Mirrors {@code HulkFractureFrameCodec.HEADER_BYTES}. */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** simTick(long) + rootEntityId(long) + deathTick(long) + edgeKind/sizeRank/familyPieceCount
     * (u8 ×3). Mirrors {@code HulkFractureFrameCodec.FRACTURE_PAYLOAD_BYTES}. */
    public static final int PAYLOAD_BYTES = Long.BYTES * 3 + 3; // 27
    /** Fixed frame width. Mirrors {@code HulkFractureFrameCodec.FRACTURE_FRAME_BYTES}. */
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 41

    private ClientHulkFractureRecordReader() {
    }

    /**
     * Decode one hulk-fracture frame whose 4-byte {@link #MAGIC} has <em>already</em> been read
     * and validated by the caller — the {@link MultiplexedServerStreamReader} entry point. The
     * CRC32 covers the frame INCLUDING the magic (the server codec checksums {@code [0, 37)} of
     * the full frame), so this reconstructs the full frame buffer before validating.
     */
    public static ClientHulkFractureRecord readAfterMagic(InputStream in) throws IOException {
        byte[] frame = new byte[FRAME_BYTES];
        WireBytes.putInt(frame, 0, MAGIC);
        byte[] rest = new byte[FRAME_BYTES - 4];
        WireBytes.readFully(in, rest); // mid-frame EOF throws — the magic is already consumed
        System.arraycopy(rest, 0, frame, 4, rest.length);
        return decodeFrame(frame);
    }

    /** Decode + validate one full {@code HKFR} frame — mirrors {@code HulkFractureFrameCodec.decode}. */
    public static ClientHulkFractureRecord decodeFrame(byte[] frame) throws IOException {
        if (frame == null || frame.length != FRAME_BYTES) {
            throw new IOException("hulk fracture frame must be " + FRAME_BYTES + " bytes, got "
                    + (frame == null ? "null" : frame.length));
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = WireBytes.getInt(frame, off);
        off += 4;
        if (magic != MAGIC) {
            throw new IOException("hulk fracture bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = WireBytes.getInt(frame, off);
        off += 4;
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("hulk fracture wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = ((frame[off] & 0xff) << 8) | (frame[off + 1] & 0xff);
        off += 2;
        if (schema != SCHEMA_VERSION) {
            throw new IOException("hulk fracture unsupported schema version: " + schema);
        }
        long simTick = WireBytes.getLong(frame, off);
        off += 8;
        long rootEntityId = WireBytes.getLong(frame, off);
        off += 8;
        long deathTick = WireBytes.getLong(frame, off);
        off += 8;
        int edgeKind = frame[off++] & 0xFF;
        int sizeRank = frame[off++] & 0xFF;
        int familyPieceCount = frame[off++] & 0xFF;
        int actualCrc = WireBytes.getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("hulk fracture frame CRC mismatch");
        }
        try {
            return new ClientHulkFractureRecord(
                    simTick, rootEntityId, deathTick, edgeKind, sizeRank, familyPieceCount);
        } catch (IllegalArgumentException badFields) {
            throw new IOException(
                    "hulk fracture fields invalid: " + badFields.getMessage(), badFields);
        }
    }
}
