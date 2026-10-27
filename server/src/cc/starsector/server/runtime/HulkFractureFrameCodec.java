package cc.starsector.server.runtime;

import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ServerHulkFractureRecordMessage;
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
 * Fixed-width wire codec for the server-authoritative hulk-fracture edge — the
 * server→both {@code HKFR} record ({@link ServerHulkFractureRecordMessage}) fanned when the
 * authoritative engine splits a corpse (or removes a piece), so clients mirror server-decided
 * fracture with engine-own verbs instead of rolling their own method-local RNGs (a dead end).
 *
 * <p>The SNMR twin ({@link SeatNameFrameCodec}): magic + registered wire type code + schema
 * header, fixed width, CRC32 over everything before the trailer, fail-closed reads. The client
 * mod does NOT load this class (its static type-code initializer drags
 * {@link NetworkMessageRegistry} into the game JVM); it speaks the same bytes through a
 * hand-mirrored reader, byte-compared by the game-free verifier at the s3 client half.
 *
 * <p>Frame layout (41 bytes, schema v3): header 10 (magic u32, typeCode u32,
 * schema u16) + payload 27 ({@code simTick} i64, {@code rootEntityId} i64, {@code deathTick}
 * i64, {@code edgeKind} u8, {@code sizeRank} u8, {@code familyPieceCount} u8) + CRC32 u32 over
 * {@code [0, 37)}. v2 replaced the v1 {@code familySeq} u8 death ordinal: a client that missed
 * deaths (late join) can never reconstruct an ordinal, but both sides always hold the death
 * TICK. v3 widened {@code edgeKind} to 1..3 — layout unchanged, the
 * bump only kills pre-v3 readers at the door instead of letting them fail-closed on kind 3.
 * v4 adds kind 4 {@code ROOT_DETACHED} — layout unchanged again: the
 * server observed the family root module-detached ({@code getParentStation()} present,
 * {@code getStationSlot()} null), and the client mirrors with {@code setStationSlot(null)}
 * so its engine's per-frame re-glue releases the wreck.
 */
public final class HulkFractureFrameCodec {

    /** HKFR magic = ASCII "HKFR" (HulK FRacture edge, down). */
    public static final int FRACTURE_MAGIC = 0x484B4652;

    /** Registered wire type code this frame carries. */
    public static final int FRACTURE_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .serverHulkFractureRecordWireEntry().wireTypeCode();

    public static final short FRACTURE_SCHEMA_VERSION =
            (short) ServerHulkFractureRecordMessage.SCHEMA_VERSION;

    /** A piece joined the corpse family (client: cut its current largest local piece). */
    public static final int EDGE_KIND_PIECE_ADDED = 1;
    /** A piece left the world (client: remove the local piece at {@code sizeRank} + explosion FX). */
    public static final int EDGE_KIND_PIECE_REMOVED = 2;
    /** A piece left the world because the engine VAPORIZED it (the
     * overkill branch — the retained ref's {@code wasDestroyed} read
     * {@code true} at the vanish observation; fail-soft {@code false} = kind 2, today's quiet
     * path). Client: replay the vanilla vaporize presentation (viewport-gated explosion at the
     * engine's own size law + {@code explosion_ship} SFX) instead of the kind-2 mini-fireball. */
    public static final int EDGE_KIND_PIECE_REMOVED_DESTROYED = 3;
    /** The family ROOT was observed module-detached (schema v4): a station
     * module wreck whose {@code getParentStation()} is present but {@code getStationSlot()}
     * reads null — the authoritative engine's detach roll or a
     * late overkill detach released it. Emitted ONCE per family, at the first sighting that
     * reads detached. Client: {@code setStationSlot(null)} on the bound wreck (unconditional
     * slot write) so the local re-glue releases and HKPS coast drives
     * the drift. NOT an add or a removal — piece membership is untouched. */
    public static final int EDGE_KIND_ROOT_DETACHED = 4;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** simTick(long) + rootEntityId(long) + deathTick(long) + edgeKind/sizeRank/familyPieceCount (u8 ×3). */
    public static final int FRACTURE_PAYLOAD_BYTES = Long.BYTES + Long.BYTES + Long.BYTES + 3; // 27
    public static final int FRACTURE_FRAME_BYTES =
            HEADER_BYTES + FRACTURE_PAYLOAD_BYTES + Integer.BYTES; // 41

    /**
     * One decoded {@code HKFR} fracture edge. {@code rootEntityId} is the corpse family's wire
     * id (pieces inherit the parent hull's engine id, pinned at family creation);
     * {@code deathTick} identifies the family — the detector's first-sight tick of the corpse
     * (within ~1-2 ticks of its EDSP death tick; the client binds families by nearest death
     * tick — one wire id can front two coexisting corpse families across a T4 remount);
     * {@code sizeRank} ranks by bbox area, largest = 0, and is 0 on adds by contract;
     * {@code familyPieceCount} is the post-edge family size (the client's apply assert).
     */
    public record FractureEdge(
            long simTick,
            long rootEntityId,
            long deathTick,
            int edgeKind,
            int sizeRank,
            int familyPieceCount) {
        public FractureEdge {
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
                        + " edges never precede its death); got deathTick=" + deathTick
                        + " simTick=" + simTick);
            }
            if (edgeKind != EDGE_KIND_PIECE_ADDED && edgeKind != EDGE_KIND_PIECE_REMOVED
                    && edgeKind != EDGE_KIND_PIECE_REMOVED_DESTROYED
                    && edgeKind != EDGE_KIND_ROOT_DETACHED) {
                throw new IllegalArgumentException("edgeKind must be 1 (ADD), 2 (REMOVE),"
                        + " 3 (REMOVE_DESTROYED) or 4 (ROOT_DETACHED); got " + edgeKind);
            }
            if (sizeRank < 0 || sizeRank > 0xFF) {
                throw new IllegalArgumentException("sizeRank must be 0..255; got " + sizeRank);
            }
            if ((edgeKind == EDGE_KIND_PIECE_ADDED || edgeKind == EDGE_KIND_ROOT_DETACHED)
                    && sizeRank != 0) {
                throw new IllegalArgumentException("sizeRank must be 0 on PIECE_ADDED and"
                        + " ROOT_DETACHED edges; got " + sizeRank);
            }
            if (familyPieceCount < 0 || familyPieceCount > 0xFF) {
                throw new IllegalArgumentException(
                        "familyPieceCount must be 0..255; got " + familyPieceCount);
            }
        }
    }

    private HulkFractureFrameCodec() {}

    /** Encode one {@code HKFR} edge as exactly {@link #FRACTURE_FRAME_BYTES} bytes. */
    public static byte[] encode(FractureEdge edge) {
        Objects.requireNonNull(edge, "edge");
        byte[] buf = new byte[FRACTURE_FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, FRACTURE_MAGIC);
        off = putInt(buf, off, FRACTURE_TYPE_CODE);
        off = putShort(buf, off, FRACTURE_SCHEMA_VERSION);
        off = putLong(buf, off, edge.simTick());
        off = putLong(buf, off, edge.rootEntityId());
        off = putLong(buf, off, edge.deathTick());
        buf[off++] = (byte) edge.edgeKind();
        buf[off++] = (byte) edge.sizeRank();
        buf[off++] = (byte) edge.familyPieceCount();
        // off now == HEADER_BYTES + FRACTURE_PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /** Serialize one {@code HKFR} to {@code out} as exactly {@link #FRACTURE_FRAME_BYTES} bytes. */
    public static void write(OutputStream out, FractureEdge edge) throws IOException {
        Objects.requireNonNull(out, "out");
        byte[] bytes = encode(edge);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Read exactly one {@code HKFR} from {@code in} (game-free tests / round-trip verifier; the
     * live client reads it via a mirrored reader off its demux). Returns {@code null} at a clean
     * end of stream; fail-closed otherwise.
     */
    public static FractureEdge read(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(FRACTURE_FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != FRACTURE_FRAME_BYTES) {
            throw new IOException("hulk fracture frame truncated: expected "
                    + FRACTURE_FRAME_BYTES + " bytes, got " + frame.length);
        }
        return decode(frame);
    }

    /** Decode + validate one full {@code HKFR} frame (fail-closed; verifier + read path). */
    public static FractureEdge decode(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length != FRACTURE_FRAME_BYTES) {
            throw new IOException("hulk fracture frame must be " + FRACTURE_FRAME_BYTES
                    + " bytes, got " + frame.length);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + FRACTURE_PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = getInt(frame, off); off += 4;
        if (magic != FRACTURE_MAGIC) {
            throw new IOException("hulk fracture bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != FRACTURE_TYPE_CODE) {
            throw new IOException("hulk fracture wire type code mismatch: expected "
                    + FRACTURE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != FRACTURE_SCHEMA_VERSION) {
            throw new IOException("hulk fracture unsupported schema version: " + schema);
        }
        long simTick = getLong(frame, off); off += 8;
        long rootEntityId = getLong(frame, off); off += 8;
        long deathTick = getLong(frame, off); off += 8;
        int edgeKind = frame[off++] & 0xFF;
        int sizeRank = frame[off++] & 0xFF;
        int familyPieceCount = frame[off++] & 0xFF;
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("hulk fracture frame CRC mismatch");
        }
        try {
            return new FractureEdge(
                    simTick, rootEntityId, deathTick, edgeKind, sizeRank, familyPieceCount);
        } catch (IllegalArgumentException badFields) {
            throw new IOException(
                    "hulk fracture fields invalid: " + badFields.getMessage(), badFields);
        }
    }
}
