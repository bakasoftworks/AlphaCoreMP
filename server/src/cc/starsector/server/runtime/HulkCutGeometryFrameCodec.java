package cc.starsector.server.runtime;

import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ServerHulkCutGeometryRecordMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

import static cc.starsector.server.runtime.WireByteOps.getInt;
import static cc.starsector.server.runtime.WireByteOps.getLong;
import static cc.starsector.server.runtime.WireByteOps.getUnsignedShort;
import static cc.starsector.server.runtime.WireByteOps.putInt;
import static cc.starsector.server.runtime.WireByteOps.putLong;
import static cc.starsector.server.runtime.WireByteOps.putShort;

/**
 * Wire codec for the server-authoritative hulk-cut-geometry record — the
 * server→both {@code HKCG} record ({@link ServerHulkCutGeometryRecordMessage}) fanned per cut
 * BEFORE the paired {@code HKFR} PIECE_ADDED edge, carrying one piece's exact or visual ring
 * (reconstructed into the parent's PRE-SPLIT local frame) so the client feeds the engine's own
 * public two-arg {@code splitShip(oo, oo)} instead of its local RNG cut.
 *
 * <p><b>Deviation from the fixed-width SNMR/HKFR/HKPS family:</b> this frame is
 * bounded-max LENGTH-PREFIXED — ring size varies per hull (frigates ~10 points, subdivided
 * capitals ~160), and pad-to-max would waste ×4 on the common case. The fail-closed property is
 * kept by hard caps ({@code pointCount} 3..200, {@code cutCount} ≤ pointCount), full structural
 * length validation, and the same CRC32 trailer. Max frame = 10 + 28 + 800 + 201 + 4 = 1043
 * bytes &lt; the 1164 ctrl payload cap (no CTRL fragmentation exists; frag v2 is AWSS-only).
 * The client mod does NOT load this class (its static type-code initializer drags
 * {@link NetworkMessageRegistry} into the game JVM); it speaks the same bytes through a
 * hand-mirrored reader — the 15th schema-parity site at the S2 client half.
 *
 * <p>Frame layout: header 10 (magic u32, typeCode u32, schema u16) + fixed payload 28
 * ({@code simTick} i64, {@code rootEntityId} i64, {@code deathTick} i64, {@code cutSeq} u8,
 * {@code pieceSlot} u8, {@code ringKind} u8, {@code pointCount} u8) + {@code pointCount} ×
 * (i16 x, i16 y) quantized 1/64 u, OPEN ring + (exact records only: {@code cutCount} u8 +
 * {@code cutCount} × u8 ring indices, the wrap-aware shared cut run, endpoints first/last) +
 * CRC32 u32 over everything before it.
 */
public final class HulkCutGeometryFrameCodec {

    /** HKCG magic = ASCII "HKCG" (HulK Cut Geometry, down). */
    public static final int CUT_GEOMETRY_MAGIC = 0x484B4347;

    /** Registered wire type code this frame carries. */
    public static final int CUT_GEOMETRY_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .serverHulkCutGeometryRecordWireEntry().wireTypeCode();

    public static final short CUT_GEOMETRY_SCHEMA_VERSION =
            (short) ServerHulkCutGeometryRecordMessage.SCHEMA_VERSION;

    /** The piece the engine carved IN PLACE (bag 1 — the ring the local parent keeps). */
    public static final int PIECE_SLOT_PARENT = 0;
    /** The piece the engine cloned off (bag 2 — the new hull the carve returns). */
    public static final int PIECE_SLOT_CLONE = 1;
    /** Exact bound ring (collision truth; carries the cut-run indices). */
    public static final int RING_KIND_EXACT = 0;
    /** Visual bound ring (presentation; no cut block). */
    public static final int RING_KIND_VISUAL = 1;

    /** Open-ring point floor (a bound ring below a triangle is malformed). */
    public static final int MIN_POINTS = 3;
    /** Open-ring point cap (worst observed subdivided capital + cut ≈ 160; hard fail-closed). */
    public static final int MAX_POINTS = 200;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** simTick(long) + rootEntityId(long) + deathTick(long) + cutSeq/pieceSlot/ringKind/pointCount (u8 ×4). */
    public static final int FIXED_PAYLOAD_BYTES = Long.BYTES + Long.BYTES + Long.BYTES + 4; // 28
    /** The fixed frame prefix a reader needs before the variable tail is sized. */
    public static final int PREFIX_BYTES = HEADER_BYTES + FIXED_PAYLOAD_BYTES; // 38
    /** Largest legal frame (exact record at both caps): 38 + 800 + 201 + 4. */
    public static final int MAX_FRAME_BYTES =
            PREFIX_BYTES + MAX_POINTS * 4 + 1 + MAX_POINTS + Integer.BYTES; // 1043

    /**
     * One decoded {@code HKCG} record — one piece × one ring of one cut. The latch key is
     * (rootEntityId, deathTick, cutSeq): the HKFR/HKPS family identity plus the per-family
     * PIECE_ADDED ordinal (EVERY emitted ADD increments it, captured or not — the client
     * derives the same ordinal by counting applied ADDs). {@code points} is the OPEN
     * ring in the parent's pre-split local frame, i16 coords × 1/64 u; {@code cutIndices}
     * (exact records only, {@code null} on visual) is the wrap-aware run of indices into THIS
     * ring whose points the two exact rings share — first/last are the cut endpoints, the
     * middle the cut interiors in chain order. Arrays are defensively copied both ways;
     * compare decoded records field-wise (record equality on arrays is identity).
     */
    public record CutGeometryRecord(
            long simTick,
            long rootEntityId,
            long deathTick,
            int cutSeq,
            int pieceSlot,
            int ringKind,
            short[] points,
            int[] cutIndices) {
        public CutGeometryRecord {
            if (simTick < 0L) {
                throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
            }
            if (rootEntityId < 1L) {
                throw new IllegalArgumentException(
                        "rootEntityId must be >= 1 (a wire entity id); got " + rootEntityId);
            }
            if (deathTick < 0L) {
                throw new IllegalArgumentException("deathTick must be >= 0; got " + deathTick);
            }
            if (deathTick > simTick) {
                throw new IllegalArgumentException("deathTick must be <= simTick (a family's"
                        + " records never precede its death); got deathTick=" + deathTick
                        + " simTick=" + simTick);
            }
            if (cutSeq < 0 || cutSeq > 0xFF) {
                throw new IllegalArgumentException("cutSeq must be 0..255; got " + cutSeq);
            }
            if (pieceSlot != PIECE_SLOT_PARENT && pieceSlot != PIECE_SLOT_CLONE) {
                throw new IllegalArgumentException(
                        "pieceSlot must be 0 (parent) or 1 (clone); got " + pieceSlot);
            }
            if (ringKind != RING_KIND_EXACT && ringKind != RING_KIND_VISUAL) {
                throw new IllegalArgumentException(
                        "ringKind must be 0 (exact) or 1 (visual); got " + ringKind);
            }
            Objects.requireNonNull(points, "points");
            if ((points.length & 1) != 0) {
                throw new IllegalArgumentException(
                        "points must be flat x,y pairs (even length); got " + points.length);
            }
            int pointCount = points.length / 2;
            if (pointCount < MIN_POINTS || pointCount > MAX_POINTS) {
                throw new IllegalArgumentException("pointCount must be " + MIN_POINTS + ".."
                        + MAX_POINTS + "; got " + pointCount);
            }
            if (ringKind == RING_KIND_EXACT) {
                Objects.requireNonNull(cutIndices, "cutIndices (exact records carry the cut run)");
                if (cutIndices.length < 2 || cutIndices.length > pointCount) {
                    throw new IllegalArgumentException("cutCount must be 2..pointCount ("
                            + pointCount + "); got " + cutIndices.length);
                }
                for (int index : cutIndices) {
                    if (index < 0 || index >= pointCount) {
                        throw new IllegalArgumentException("cut index must be 0.."
                                + (pointCount - 1) + "; got " + index);
                    }
                }
                cutIndices = cutIndices.clone();
            } else {
                if (cutIndices != null && cutIndices.length != 0) {
                    throw new IllegalArgumentException(
                            "visual records carry no cut run; got " + cutIndices.length
                                    + " indices");
                }
                cutIndices = null; // canonical: visual == null (empty normalizes)
            }
            points = points.clone();
        }

        @Override
        public short[] points() {
            return points.clone();
        }

        @Override
        public int[] cutIndices() {
            return cutIndices == null ? null : cutIndices.clone();
        }
    }

    private HulkCutGeometryFrameCodec() {}

    /** The exact encoded size of {@code record} (length-prefixed frames vary per ring). */
    public static int frameBytes(CutGeometryRecord record) {
        Objects.requireNonNull(record, "record");
        int pointCount = record.points().length / 2;
        int cutBlock = record.ringKind() == RING_KIND_EXACT
                ? 1 + record.cutIndices().length : 0;
        return PREFIX_BYTES + pointCount * 4 + cutBlock + Integer.BYTES;
    }

    /** Encode one {@code HKCG} record as exactly {@link #frameBytes(CutGeometryRecord)} bytes. */
    public static byte[] encode(CutGeometryRecord record) {
        Objects.requireNonNull(record, "record");
        short[] points = record.points();
        int[] cutIndices = record.cutIndices();
        byte[] buf = new byte[frameBytes(record)];
        int off = 0;
        off = putInt(buf, off, CUT_GEOMETRY_MAGIC);
        off = putInt(buf, off, CUT_GEOMETRY_TYPE_CODE);
        off = putShort(buf, off, CUT_GEOMETRY_SCHEMA_VERSION);
        off = putLong(buf, off, record.simTick());
        off = putLong(buf, off, record.rootEntityId());
        off = putLong(buf, off, record.deathTick());
        buf[off++] = (byte) record.cutSeq();
        buf[off++] = (byte) record.pieceSlot();
        buf[off++] = (byte) record.ringKind();
        buf[off++] = (byte) (points.length / 2);
        for (short coord : points) {
            off = putShort(buf, off, coord);
        }
        if (record.ringKind() == RING_KIND_EXACT) {
            buf[off++] = (byte) cutIndices.length;
            for (int index : cutIndices) {
                buf[off++] = (byte) index;
            }
        }
        // off now == frame length - 4; CRC32 covers exactly the body before the trailer.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /** Serialize one {@code HKCG} to {@code out}. */
    public static void write(OutputStream out, CutGeometryRecord record) throws IOException {
        Objects.requireNonNull(out, "out");
        byte[] bytes = encode(record);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Read exactly one {@code HKCG} from {@code in} (game-free tests / round-trip verifier; the
     * live client reads it via a mirrored reader off its demux). The fixed 38-byte prefix sizes
     * the variable tail; the tail bounds are validated BEFORE the reads they size (fail-closed —
     * a hostile prefix can never demand more than {@link #MAX_FRAME_BYTES}). Returns
     * {@code null} at a clean end of stream; fail-closed otherwise.
     */
    public static CutGeometryRecord read(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] prefix = in.readNBytes(PREFIX_BYTES);
        if (prefix.length == 0) {
            return null; // clean end of stream
        }
        if (prefix.length != PREFIX_BYTES) {
            throw new IOException("hulk cut-geometry frame truncated: expected "
                    + PREFIX_BYTES + " prefix bytes, got " + prefix.length);
        }
        int ringKind = prefix[PREFIX_BYTES - 2] & 0xFF;
        int pointCount = prefix[PREFIX_BYTES - 1] & 0xFF;
        if (ringKind != RING_KIND_EXACT && ringKind != RING_KIND_VISUAL) {
            throw new IOException(
                    "hulk cut-geometry ringKind must be 0 (exact) or 1 (visual); got " + ringKind);
        }
        if (pointCount < MIN_POINTS || pointCount > MAX_POINTS) {
            throw new IOException("hulk cut-geometry pointCount must be " + MIN_POINTS + ".."
                    + MAX_POINTS + "; got " + pointCount);
        }
        byte[] pointBytes = in.readNBytes(pointCount * 4);
        if (pointBytes.length != pointCount * 4) {
            throw new IOException("hulk cut-geometry points truncated: expected "
                    + pointCount * 4 + " bytes, got " + pointBytes.length);
        }
        byte[] cutBlock = new byte[0];
        if (ringKind == RING_KIND_EXACT) {
            byte[] cutCountByte = in.readNBytes(1);
            if (cutCountByte.length != 1) {
                throw new IOException("hulk cut-geometry cutCount truncated");
            }
            int cutCount = cutCountByte[0] & 0xFF;
            if (cutCount < 2 || cutCount > pointCount) {
                throw new IOException("hulk cut-geometry cutCount must be 2..pointCount ("
                        + pointCount + "); got " + cutCount);
            }
            byte[] indexBytes = in.readNBytes(cutCount);
            if (indexBytes.length != cutCount) {
                throw new IOException("hulk cut-geometry cut indices truncated: expected "
                        + cutCount + " bytes, got " + indexBytes.length);
            }
            cutBlock = new byte[1 + cutCount];
            cutBlock[0] = cutCountByte[0];
            System.arraycopy(indexBytes, 0, cutBlock, 1, cutCount);
        }
        byte[] crcBytes = in.readNBytes(Integer.BYTES);
        if (crcBytes.length != Integer.BYTES) {
            throw new IOException("hulk cut-geometry CRC truncated: expected "
                    + Integer.BYTES + " bytes, got " + crcBytes.length);
        }
        byte[] frame = new byte[PREFIX_BYTES + pointBytes.length + cutBlock.length + Integer.BYTES];
        int off = 0;
        System.arraycopy(prefix, 0, frame, off, prefix.length); off += prefix.length;
        System.arraycopy(pointBytes, 0, frame, off, pointBytes.length); off += pointBytes.length;
        System.arraycopy(cutBlock, 0, frame, off, cutBlock.length); off += cutBlock.length;
        System.arraycopy(crcBytes, 0, frame, off, crcBytes.length);
        return decode(frame);
    }

    /**
     * Decode + validate one full {@code HKCG} frame (fail-closed; verifier + read path).
     * Rejection order: structural (length floor, magic, type code, schema, ringKind/pointCount
     * bounds, exact frame-length consistency) → CRC → field ranges (the record constructor,
     * wrapped as {@link IOException}).
     */
    public static CutGeometryRecord decode(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length < PREFIX_BYTES + Integer.BYTES || frame.length > MAX_FRAME_BYTES) {
            throw new IOException("hulk cut-geometry frame must be " + (PREFIX_BYTES + Integer.BYTES)
                    + ".." + MAX_FRAME_BYTES + " bytes, got " + frame.length);
        }
        int off = 0;
        int magic = getInt(frame, off); off += 4;
        if (magic != CUT_GEOMETRY_MAGIC) {
            throw new IOException("hulk cut-geometry bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != CUT_GEOMETRY_TYPE_CODE) {
            throw new IOException("hulk cut-geometry wire type code mismatch: expected "
                    + CUT_GEOMETRY_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != CUT_GEOMETRY_SCHEMA_VERSION) {
            throw new IOException("hulk cut-geometry unsupported schema version: " + schema);
        }
        long simTick = getLong(frame, off); off += 8;
        long rootEntityId = getLong(frame, off); off += 8;
        long deathTick = getLong(frame, off); off += 8;
        int cutSeq = frame[off++] & 0xFF;
        int pieceSlot = frame[off++] & 0xFF;
        int ringKind = frame[off++] & 0xFF;
        int pointCount = frame[off++] & 0xFF;
        if (ringKind != RING_KIND_EXACT && ringKind != RING_KIND_VISUAL) {
            throw new IOException(
                    "hulk cut-geometry ringKind must be 0 (exact) or 1 (visual); got " + ringKind);
        }
        if (pointCount < MIN_POINTS || pointCount > MAX_POINTS) {
            throw new IOException("hulk cut-geometry pointCount must be " + MIN_POINTS + ".."
                    + MAX_POINTS + "; got " + pointCount);
        }
        int pointsEnd = off + pointCount * 4;
        int cutCount = 0;
        int expectedBytes;
        if (ringKind == RING_KIND_EXACT) {
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
        if (getInt(frame, expectedBytes - Integer.BYTES) != (int) crc.getValue()) {
            throw new IOException("hulk cut-geometry frame CRC mismatch");
        }
        short[] points = new short[pointCount * 2];
        for (int i = 0; i < points.length; i++) {
            points[i] = (short) getUnsignedShort(frame, off); // sign-extend the i16 coord
            off += 2;
        }
        int[] cutIndices = null;
        if (ringKind == RING_KIND_EXACT) {
            off++; // cutCount, already parsed for the length check
            cutIndices = new int[cutCount];
            for (int i = 0; i < cutCount; i++) {
                cutIndices[i] = frame[off++] & 0xFF;
            }
        }
        try {
            return new CutGeometryRecord(simTick, rootEntityId, deathTick,
                    cutSeq, pieceSlot, ringKind, points, cutIndices);
        } catch (IllegalArgumentException badFields) {
            throw new IOException(
                    "hulk cut-geometry fields invalid: " + badFields.getMessage(), badFields);
        }
    }
}
