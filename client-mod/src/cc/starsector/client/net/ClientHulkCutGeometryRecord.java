package cc.starsector.client.net;

import java.util.Arrays;

/**
 * The decoded payload of one {@code HKCG} server-authoritative hulk-cut-geometry record
 * — the client-side analogue of the server's
 * {@code HulkCutGeometryFrameCodec.CutGeometryRecord} — one piece × one ring of one
 * authoritative carve. The server fans FOUR per cut ({@code pieceSlot} × {@code ringKind})
 * BEFORE the paired {@code HKFR} PIECE_ADDED edge on the same strict-FIFO ctrl lane, so the
 * client latch is always warm at edge arrival and the coordinator can feed the engine's own
 * public two-arg {@code splitShip(oo, oo)} with the server's measured result rings instead of
 * rolling its local tesselation RNG.
 *
 * <p>The latch key is (rootEntityId, deathTick, cutSeq) — the {@code HKFR}/{@code HKPS} family
 * identity plus the per-family PIECE_ADDED ordinal (EVERY emitted ADD increments it, captured
 * or not; the client derives the same ordinal by counting applied ADDs).
 * {@code points} is the OPEN ring in the parent's PRE-SPLIT local frame as flat x,y pairs of
 * i16 coords quantized 1/64 u (de-quantize = ÷64 at apply); {@code cutIndices} (exact records
 * only, {@code null} on visual) is the wrap-aware run of indices into THIS ring whose points
 * the two exact rings share — first/last are the cut endpoints, the middle the cut interiors
 * in chain order.
 *
 * <p>Like its {@code HKFR}/{@code HKPS} siblings {@link ClientHulkFractureRecord} /
 * {@link ClientHulkPoseRecord}, this is a small client-owned mirror — the mod deliberately
 * does not bundle the server's record (its codec statics drag {@code NetworkMessageRegistry}
 * into the game JVM). The game-free cross-check ({@code CcClientSeatNameVerifier}'s HKCG legs)
 * round-trips real server {@code HulkCutGeometryFrameCodec} bytes through
 * {@link ClientHulkCutGeometryRecordReader} and asserts this value matches, keeping the two
 * mirrors in sync.
 */
public final class ClientHulkCutGeometryRecord {

    /** The piece the engine carved IN PLACE (bag 1 — the ring the local parent keeps).
     * Mirrors {@code HulkCutGeometryFrameCodec.PIECE_SLOT_PARENT}. */
    public static final int PIECE_SLOT_PARENT = 0;
    /** The piece the engine cloned off (bag 2 — the new hull the carve returns).
     * Mirrors {@code HulkCutGeometryFrameCodec.PIECE_SLOT_CLONE}. */
    public static final int PIECE_SLOT_CLONE = 1;
    /** Exact bound ring (collision truth; carries the cut-run indices).
     * Mirrors {@code HulkCutGeometryFrameCodec.RING_KIND_EXACT}. */
    public static final int RING_KIND_EXACT = 0;
    /** Visual bound ring (presentation; no cut block).
     * Mirrors {@code HulkCutGeometryFrameCodec.RING_KIND_VISUAL}. */
    public static final int RING_KIND_VISUAL = 1;

    /** Open-ring point floor. Mirrors {@code HulkCutGeometryFrameCodec.MIN_POINTS}. */
    public static final int MIN_POINTS = 3;
    /** Open-ring point cap (hard fail-closed). Mirrors {@code HulkCutGeometryFrameCodec.MAX_POINTS}. */
    public static final int MAX_POINTS = 200;

    private final long simTick;
    private final long rootEntityId;
    private final long deathTick;
    private final int cutSeq;
    private final int pieceSlot;
    private final int ringKind;
    private final short[] points;
    private final int[] cutIndices;

    /**
     * @throws IllegalArgumentException on any field outside the server record's exact guards
     *     (negative simTick, rootEntityId &lt; 1, a deathTick outside {@code 0..simTick}, a
     *     cutSeq outside 0..255, an unknown pieceSlot/ringKind, an odd or out-of-cap points
     *     array, a missing/oversized/out-of-range cut run on an exact ring, or a non-empty cut
     *     run on a visual ring). The reader translates this to an {@link java.io.IOException}
     *     so a bad frame fails closed like every other wire decode.
     */
    public ClientHulkCutGeometryRecord(long simTick, long rootEntityId, long deathTick,
            int cutSeq, int pieceSlot, int ringKind, short[] points, int[] cutIndices) {
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
        if (points == null) {
            throw new IllegalArgumentException("points must not be null");
        }
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
            if (cutIndices == null) {
                throw new IllegalArgumentException(
                        "cutIndices must not be null (exact records carry the cut run)");
            }
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
                        "visual records carry no cut run; got " + cutIndices.length + " indices");
            }
            cutIndices = null; // canonical: visual == null (empty normalizes)
        }
        this.simTick = simTick;
        this.rootEntityId = rootEntityId;
        this.deathTick = deathTick;
        this.cutSeq = cutSeq;
        this.pieceSlot = pieceSlot;
        this.ringKind = ringKind;
        this.points = points.clone();
        this.cutIndices = cutIndices;
    }

    /** The server sim tick the cut's records were emitted on. */
    public long simTick() {
        return simTick;
    }

    /** The corpse family's wire id (the originally-alive ship's entity id). */
    public long rootEntityId() {
        return rootEntityId;
    }

    /** The family's wire identity: the server detector's firstSightTick (~the EDSP death tick;
     * the HKFR deathTick-keyed family law). Latch key with {@link #rootEntityId()} + {@link #cutSeq()}. */
    public long deathTick() {
        return deathTick;
    }

    /** The per-family PIECE_ADDED ordinal this cut pairs with (EVERY emitted ADD increments). */
    public int cutSeq() {
        return cutSeq;
    }

    /** Which carve output this ring belongs to: 0 = parent (carved in place), 1 = clone. */
    public int pieceSlot() {
        return pieceSlot;
    }

    /** Which bound this ring is: 0 = exact (collision truth), 1 = visual (presentation). */
    public int ringKind() {
        return ringKind;
    }

    /** The OPEN ring as flat x,y pairs, i16 coords × 1/64 u, parent PRE-SPLIT local frame
     * (defensive copy). */
    public short[] points() {
        return points.clone();
    }

    /** The wrap-aware shared cut run as indices into THIS ring (exact records; defensive copy),
     * or {@code null} on a visual record. First/last = the cut endpoints, middle = interiors in
     * chain order. */
    public int[] cutIndices() {
        return cutIndices == null ? null : cutIndices.clone();
    }

    /** Point count of the open ring ({@code points().length / 2}). */
    public int pointCount() {
        return points.length / 2;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientHulkCutGeometryRecord other)) {
            return false;
        }
        return simTick == other.simTick && rootEntityId == other.rootEntityId
                && deathTick == other.deathTick && cutSeq == other.cutSeq
                && pieceSlot == other.pieceSlot && ringKind == other.ringKind
                && Arrays.equals(points, other.points)
                && Arrays.equals(cutIndices, other.cutIndices);
    }

    @Override
    public int hashCode() {
        int h = Long.hashCode(simTick);
        h = 31 * h + Long.hashCode(rootEntityId);
        h = 31 * h + Long.hashCode(deathTick);
        h = 31 * h + cutSeq;
        h = 31 * h + pieceSlot;
        h = 31 * h + ringKind;
        h = 31 * h + Arrays.hashCode(points);
        return 31 * h + Arrays.hashCode(cutIndices);
    }

    @Override
    public String toString() {
        return "ClientHulkCutGeometryRecord{tick=" + simTick + ",rootId=" + rootEntityId
                + ",deathTick=" + deathTick + ",cutSeq=" + cutSeq + ",slot=" + pieceSlot
                + ",ring=" + ringKind + ",points=" + (points.length / 2)
                + ",cutRun=" + (cutIndices == null ? 0 : cutIndices.length) + "}";
    }
}
