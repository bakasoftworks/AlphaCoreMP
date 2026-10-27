package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

/**
 * Client-side decoder for the server's {@code HKCG} hulk-cut-geometry wire frame (
 * s-F(1)): the receiving half of the server's {@code HulkCutGeometryFrameCodec}
 * ({@code server.hulk_cut_geometry_record} v1). The server fans four records per authoritative
 * carve (pieceSlot × ringKind) on the reliable CTRL lane BEFORE the paired {@code HKFR}
 * PIECE_ADDED edge — the {@code HKFR} fan shape, NO admission catch-up (a newcomer's world
 * contains no corpses); the {@link MultiplexedServerStreamReader} routes it here by its leading
 * magic.
 *
 * <p>Like {@link ClientHulkFractureRecordReader}, the mod deliberately does not bundle the
 * server codec (whose static type-code initializers drag {@code NetworkMessageRegistry} into
 * the game JVM). Unlike the fixed-width SNMR/HKFR/HKPS family this frame is <b>bounded-max
 * LENGTH-PREFIXED</b> (the recorded deviation — ring size varies per hull): a fixed
 * 38-byte prefix — magic / wireTypeCode / schema / simTick(i64) / rootEntityId(i64) /
 * deathTick(i64) / cutSeq(u8) / pieceSlot(u8) / ringKind(u8) / pointCount(u8) — sizes the
 * variable tail of pointCount × (i16 x, i16 y) quantized 1/64 u, plus (exact records only)
 * cutCount(u8) + cutCount × u8 ring indices, plus the CRC32-over-body trailer. The tail bounds
 * are validated BEFORE the reads they size, so a hostile prefix can never demand more than
 * {@link #MAX_FRAME_BYTES}. The game-free cross-check ({@code CcClientSeatNameVerifier}'s HKCG
 * legs) streams real server {@code HulkCutGeometryFrameCodec} bytes through this reader and
 * asserts the decoded records match, keeping the mirrors in sync.
 *
 * <p>Fail-closed, mirroring the server decode exactly: a wire-type-code or schema mismatch, an
 * out-of-bounds ringKind/pointCount/cutCount, a frame-length inconsistency, a CRC32 mismatch,
 * or any field outside the {@link ClientHulkCutGeometryRecord} guards all throw
 * {@link IOException}.
 */
public final class ClientHulkCutGeometryRecordReader {

    /** Frame magic: ASCII "HKCG". Mirrors {@code HulkCutGeometryFrameCodec.CUT_GEOMETRY_MAGIC}. */
    public static final int MAGIC = 0x484B4347;

    /** Registry message id this frame carries. Mirrors {@code ServerHulkCutGeometryRecordMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "server.hulk_cut_geometry_record";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);

    /** Schema version this reader understands. Mirrors {@code ServerHulkCutGeometryRecordMessage.SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 1;

    /** magic(int) + typeCode(int) + schema(short). Mirrors {@code HulkCutGeometryFrameCodec.HEADER_BYTES}. */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** simTick(long) + rootEntityId(long) + deathTick(long) + cutSeq/pieceSlot/ringKind/pointCount
     * (u8 ×4). Mirrors {@code HulkCutGeometryFrameCodec.FIXED_PAYLOAD_BYTES}. */
    public static final int FIXED_PAYLOAD_BYTES = Long.BYTES * 3 + 4; // 28
    /** The fixed frame prefix that sizes the variable tail. Mirrors
     * {@code HulkCutGeometryFrameCodec.PREFIX_BYTES}. */
    public static final int PREFIX_BYTES = HEADER_BYTES + FIXED_PAYLOAD_BYTES; // 38
    /** Largest legal frame (exact record at both caps): 38 + 800 + 201 + 4. Mirrors
     * {@code HulkCutGeometryFrameCodec.MAX_FRAME_BYTES}. */
    public static final int MAX_FRAME_BYTES = PREFIX_BYTES
            + ClientHulkCutGeometryRecord.MAX_POINTS * 4 + 1
            + ClientHulkCutGeometryRecord.MAX_POINTS + Integer.BYTES; // 1043

    private ClientHulkCutGeometryRecordReader() {
    }

    /**
     * Decode one hulk-cut-geometry frame whose 4-byte {@link #MAGIC} has <em>already</em> been
     * read and validated by the caller — the {@link MultiplexedServerStreamReader} entry point.
     * The rest of the fixed prefix sizes the variable tail (bounds validated BEFORE the reads
     * they size); the CRC32 covers the frame INCLUDING the magic, so this reconstructs the full
     * frame buffer before validating.
     */
    public static ClientHulkCutGeometryRecord readAfterMagic(InputStream in) throws IOException {
        byte[] prefixRest = new byte[PREFIX_BYTES - 4];
        WireBytes.readFully(in, prefixRest); // mid-frame EOF throws — the magic is already consumed
        int ringKind = prefixRest[prefixRest.length - 2] & 0xFF;
        int pointCount = prefixRest[prefixRest.length - 1] & 0xFF;
        if (ringKind != ClientHulkCutGeometryRecord.RING_KIND_EXACT
                && ringKind != ClientHulkCutGeometryRecord.RING_KIND_VISUAL) {
            throw new IOException(
                    "hulk cut-geometry ringKind must be 0 (exact) or 1 (visual); got " + ringKind);
        }
        if (pointCount < ClientHulkCutGeometryRecord.MIN_POINTS
                || pointCount > ClientHulkCutGeometryRecord.MAX_POINTS) {
            throw new IOException("hulk cut-geometry pointCount must be "
                    + ClientHulkCutGeometryRecord.MIN_POINTS + ".."
                    + ClientHulkCutGeometryRecord.MAX_POINTS + "; got " + pointCount);
        }
        byte[] pointBytes = new byte[pointCount * 4];
        WireBytes.readFully(in, pointBytes);
        byte[] cutBlock = new byte[0];
        if (ringKind == ClientHulkCutGeometryRecord.RING_KIND_EXACT) {
            int cutCount = WireBytes.readUnsignedByte(in);
            if (cutCount < 2 || cutCount > pointCount) {
                throw new IOException("hulk cut-geometry cutCount must be 2..pointCount ("
                        + pointCount + "); got " + cutCount);
            }
            byte[] indexBytes = new byte[cutCount];
            WireBytes.readFully(in, indexBytes);
            cutBlock = new byte[1 + cutCount];
            cutBlock[0] = (byte) cutCount;
            System.arraycopy(indexBytes, 0, cutBlock, 1, cutCount);
        }
        byte[] crcBytes = new byte[Integer.BYTES];
        WireBytes.readFully(in, crcBytes);
        byte[] frame = new byte[PREFIX_BYTES + pointBytes.length + cutBlock.length + Integer.BYTES];
        int off = 0;
        WireBytes.putInt(frame, off, MAGIC);
        off += 4;
        System.arraycopy(prefixRest, 0, frame, off, prefixRest.length);
        off += prefixRest.length;
        System.arraycopy(pointBytes, 0, frame, off, pointBytes.length);
        off += pointBytes.length;
        System.arraycopy(cutBlock, 0, frame, off, cutBlock.length);
        off += cutBlock.length;
        System.arraycopy(crcBytes, 0, frame, off, crcBytes.length);
        return decodeFrame(frame);
    }

    /** Decode + validate one full {@code HKCG} frame — mirrors
     * {@code HulkCutGeometryFrameCodec.decode}. Rejection order: structural (length floor/cap,
     * magic, type code, schema, ringKind/pointCount bounds, exact frame-length consistency) →
     * CRC → field ranges (the record constructor, wrapped as {@link IOException}). */
    public static ClientHulkCutGeometryRecord decodeFrame(byte[] frame) throws IOException {
        if (frame == null || frame.length < PREFIX_BYTES + Integer.BYTES
                || frame.length > MAX_FRAME_BYTES) {
            throw new IOException("hulk cut-geometry frame must be "
                    + (PREFIX_BYTES + Integer.BYTES) + ".." + MAX_FRAME_BYTES + " bytes, got "
                    + (frame == null ? "null" : frame.length));
        }
        int off = 0;
        int magic = WireBytes.getInt(frame, off);
        off += 4;
        if (magic != MAGIC) {
            throw new IOException("hulk cut-geometry bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = WireBytes.getInt(frame, off);
        off += 4;
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("hulk cut-geometry wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = ((frame[off] & 0xff) << 8) | (frame[off + 1] & 0xff);
        off += 2;
        if (schema != SCHEMA_VERSION) {
            throw new IOException("hulk cut-geometry unsupported schema version: " + schema);
        }
        long simTick = WireBytes.getLong(frame, off);
        off += 8;
        long rootEntityId = WireBytes.getLong(frame, off);
        off += 8;
        long deathTick = WireBytes.getLong(frame, off);
        off += 8;
        int cutSeq = frame[off++] & 0xFF;
        int pieceSlot = frame[off++] & 0xFF;
        int ringKind = frame[off++] & 0xFF;
        int pointCount = frame[off++] & 0xFF;
        if (ringKind != ClientHulkCutGeometryRecord.RING_KIND_EXACT
                && ringKind != ClientHulkCutGeometryRecord.RING_KIND_VISUAL) {
            throw new IOException(
                    "hulk cut-geometry ringKind must be 0 (exact) or 1 (visual); got " + ringKind);
        }
        if (pointCount < ClientHulkCutGeometryRecord.MIN_POINTS
                || pointCount > ClientHulkCutGeometryRecord.MAX_POINTS) {
            throw new IOException("hulk cut-geometry pointCount must be "
                    + ClientHulkCutGeometryRecord.MIN_POINTS + ".."
                    + ClientHulkCutGeometryRecord.MAX_POINTS + "; got " + pointCount);
        }
        int pointsEnd = off + pointCount * 4;
        int cutCount = 0;
        int expectedBytes;
        if (ringKind == ClientHulkCutGeometryRecord.RING_KIND_EXACT) {
            if (frame.length < pointsEnd + 1 + Integer.BYTES) {
                throw new IOException("hulk cut-geometry frame truncated before cutCount");
            }
            cutCount = frame[pointsEnd] & 0xFF;
            expectedBytes = pointsEnd + 1 + cutCount + Integer.BYTES;
        } else {
            expectedBytes = pointsEnd + Integer.BYTES;
        }
        if (frame.length != expectedBytes) {
            throw new IOException("hulk cut-geometry frame length mismatch: expected "
                    + expectedBytes + " bytes, got " + frame.length);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, expectedBytes - Integer.BYTES);
        if (WireBytes.getInt(frame, expectedBytes - Integer.BYTES) != (int) crc.getValue()) {
            throw new IOException("hulk cut-geometry frame CRC mismatch");
        }
        short[] points = new short[pointCount * 2];
        for (int i = 0; i < points.length; i++) {
            points[i] = (short) (((frame[off] & 0xff) << 8) | (frame[off + 1] & 0xff));
            off += 2;
        }
        int[] cutIndices = null;
        if (ringKind == ClientHulkCutGeometryRecord.RING_KIND_EXACT) {
            off++; // cutCount, already parsed for the length check
            cutIndices = new int[cutCount];
            for (int i = 0; i < cutCount; i++) {
                cutIndices[i] = frame[off++] & 0xFF;
            }
        }
        try {
            return new ClientHulkCutGeometryRecord(simTick, rootEntityId, deathTick,
                    cutSeq, pieceSlot, ringKind, points, cutIndices);
        } catch (IllegalArgumentException badFields) {
            throw new IOException(
                    "hulk cut-geometry fields invalid: " + badFields.getMessage(), badFields);
        }
    }
}
