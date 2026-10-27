package cc.starsector.server.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

import static cc.starsector.server.runtime.WireByteOps.getFloat;
import static cc.starsector.server.runtime.WireByteOps.getInt;
import static cc.starsector.server.runtime.WireByteOps.getLong;
import static cc.starsector.server.runtime.WireByteOps.getUnsignedShort;
import static cc.starsector.server.runtime.WireByteOps.putByte;
import static cc.starsector.server.runtime.WireByteOps.putFloat;
import static cc.starsector.server.runtime.WireByteOps.putInt;
import static cc.starsector.server.runtime.WireByteOps.putLong;
import static cc.starsector.server.runtime.WireByteOps.putShort;

/**
 * Fixed-width wire codec for {@link PilotWorldViewFrame} — the DOWN-channel of the live two-client
 * 1v1: the server streams this minimal per-tick world view to the
 * remote pilot client so the client can compute its own faithful aim. Mirrors the framing discipline
 * of {@link PilotIntentWireCodec} / {@link OfflineCombatStepWireCodec} (magic + schema version +
 * fixed-width payload + CRC32) so a frame survives a real TCP stream. Game-free (java.base only), so
 * the same separate remote client JVM that has no engine can decode it.
 *
 * <p><b>Allowlist-clean transport.</b> Like {@link PilotIntentWireCodec}, this codec is
 * folded into the client mod jar and read in-game under the script classloader {@code B}, whose static
 * blocklist denies {@code Data*Stream}/{@code ByteArrayOutputStream}. The pilot client
 * decodes the DOWN frame with {@link #readFrame} in-game, so (de)serialization uses only the shared
 * {@link WireByteOps} big-endian byte ops (folded into the mod jar beside this
 * codec) over a fixed {@code byte[}{@link #FRAME_BYTES}{@code ]} plus the allowlisted {@code CRC32}.
 * Wire bytes are unchanged from the prior {@code DataOutputStream} encoding.
 */
public final class PilotWorldViewWireCodec {

    /** Magic = ASCII "PWVW" (Pilot World View Wire). */
    public static final int MAGIC = 0x50575657;
    /**
     * Schema v3 widens v2 with the two ships' authoritative hull fractions
     * ({@code getHullLevel()} in {@code [0,1]}) so each window can MIRROR the server's real damage/death
     * (the in-window DAMAGE blocker). v2 widened v1 with the authoritative
     * facings + fire bits; v1 carried positions + alive/terminal only. There is no older reader/writer
     * left (the DOWN channel has a single producer + consumer pair), so the version simply moves forward
     * — an older frame against this reader fails the version check.
     */
    public static final short SCHEMA_VERSION = 3;
    public static final int HEADER_BYTES = Integer.BYTES + Short.BYTES + Short.BYTES; // 8
    public static final int PAYLOAD_BYTES =
            Integer.BYTES + Long.BYTES + (8 * Float.BYTES) + Byte.BYTES; // 45 (was 37: +2 hull floats)
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 57

    private static final int FLAG_TARGET_ALIVE = 0x1;
    private static final int FLAG_TERMINAL = 0x2;
    private static final int FLAG_SRC_FIRING = 0x4;
    private static final int FLAG_TGT_FIRING = 0x8;

    private PilotWorldViewWireCodec() {}

    /** Serialize {@code frame} to {@code out} as exactly {@link #FRAME_BYTES} bytes (header + payload + CRC32). */
    public static void writeFrame(OutputStream out, PilotWorldViewFrame frame) throws IOException {
        Objects.requireNonNull(out, "out");
        byte[] bytes = encodeFrame(frame);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Encode {@code frame} into a fresh {@link #FRAME_BYTES}-long array (header + payload + CRC32),
     * using only allowlist-clean big-endian byte ops (no {@code Data*Stream}/{@code ByteArrayOutputStream})
     * so it is byte-identical to the prior {@code DataOutputStream} encoding.
     */
    public static byte[] encodeFrame(PilotWorldViewFrame frame) {
        Objects.requireNonNull(frame, "frame");
        byte[] buf = new byte[FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, MAGIC);
        off = putShort(buf, off, SCHEMA_VERSION);
        off = putShort(buf, off, HEADER_BYTES);
        off = putInt(buf, off, frame.frameSeq());
        off = putLong(buf, off, frame.sourceShipId());
        off = putFloat(buf, off, frame.srcX());
        off = putFloat(buf, off, frame.srcY());
        off = putFloat(buf, off, frame.tgtX());
        off = putFloat(buf, off, frame.tgtY());
        off = putFloat(buf, off, frame.srcFacingDeg());
        off = putFloat(buf, off, frame.tgtFacingDeg());
        off = putFloat(buf, off, frame.srcHullFraction());
        off = putFloat(buf, off, frame.tgtHullFraction());
        int flags = (frame.targetAlive() ? FLAG_TARGET_ALIVE : 0) | (frame.terminal() ? FLAG_TERMINAL : 0)
                | (frame.srcFiring() ? FLAG_SRC_FIRING : 0) | (frame.tgtFiring() ? FLAG_TGT_FIRING : 0);
        off = putByte(buf, off, flags);
        // off now == HEADER_BYTES + PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /**
     * Read exactly one {@link PilotWorldViewFrame} from {@code in}, blocking until {@link #FRAME_BYTES}
     * arrive. Returns {@code null} at a clean end of stream (zero bytes available). Throws on a
     * truncated frame, bad magic / version / header size, or a CRC mismatch.
     */
    public static PilotWorldViewFrame readFrame(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != FRAME_BYTES) {
            throw new IOException("pilot world view wire frame truncated: expected " + FRAME_BYTES
                    + " bytes, got " + frame.length);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = getInt(frame, off); off += 4;
        if (magic != MAGIC) {
            throw new IOException("pilot world view wire frame bad magic: 0x" + Integer.toHexString(magic));
        }
        int version = getUnsignedShort(frame, off); off += 2;
        if (version != SCHEMA_VERSION) {
            throw new IOException("pilot world view wire frame unsupported schema version: " + version);
        }
        int headerSize = getUnsignedShort(frame, off); off += 2;
        if (headerSize != HEADER_BYTES) {
            throw new IOException("pilot world view wire frame bad header size: " + headerSize);
        }
        int frameSeq = getInt(frame, off); off += 4;
        long sourceShipId = getLong(frame, off); off += 8;
        float srcX = getFloat(frame, off); off += 4;
        float srcY = getFloat(frame, off); off += 4;
        float tgtX = getFloat(frame, off); off += 4;
        float tgtY = getFloat(frame, off); off += 4;
        float srcFacingDeg = getFloat(frame, off); off += 4;
        float tgtFacingDeg = getFloat(frame, off); off += 4;
        float srcHullFraction = getFloat(frame, off); off += 4;
        float tgtHullFraction = getFloat(frame, off); off += 4;
        int flags = frame[off] & 0xFF; off += 1;
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("pilot world view wire frame CRC mismatch");
        }
        return new PilotWorldViewFrame(
                frameSeq, sourceShipId, srcX, srcY, tgtX, tgtY, srcFacingDeg, tgtFacingDeg,
                srcHullFraction, tgtHullFraction,
                (flags & FLAG_TARGET_ALIVE) != 0,
                (flags & FLAG_SRC_FIRING) != 0, (flags & FLAG_TGT_FIRING) != 0,
                (flags & FLAG_TERMINAL) != 0);
    }

}
