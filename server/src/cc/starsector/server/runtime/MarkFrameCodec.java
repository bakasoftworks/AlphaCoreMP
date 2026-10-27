package cc.starsector.server.runtime;

import cc.starsector.server.protocol.ClientOwnerMarkMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.CRC32;

import static cc.starsector.server.runtime.WireByteOps.getInt;
import static cc.starsector.server.runtime.WireByteOps.getLong;
import static cc.starsector.server.runtime.WireByteOps.getUnsignedShort;
import static cc.starsector.server.runtime.WireByteOps.putInt;
import static cc.starsector.server.runtime.WireByteOps.putLong;
import static cc.starsector.server.runtime.WireByteOps.putShort;

/**
 * Fixed-width wire codec for the F9 owner-mark capture — the client-&gt;server
 * {@code MARK} frame ({@link ClientOwnerMarkMessage}).
 *
 * <p>Shape = {@link AwsdNackFrameCodec} verbatim (the PDCL/SNAM/SNNK fixed-width CTRL template:
 * magic + registered wire type code + schema, CRC32 over the WHOLE header+payload including the
 * magic, fail-closed read). The CTRL up lane demuxes by magic exactly like SNNK; the server read
 * peeks 4 magic bytes and routes {@code MARK} here via {@link #readFrameAfterMagic}.
 *
 * <p>Payload = mark count u16 (≥1 — a zeroth mark does not exist) + client sim tick i64
 * ({@code -1} = the client had no snapshot yet; anything below {@code -1} is refused) ⇒ a
 * {@value #FRAME_BYTES}-B frame. No text: nothing wire-derived ever reaches a path/exec/log
 * sink beyond two integers.
 *
 * <p>Like {@link AwsdNackFrameCodec}, this class is NOT folded into the client mod jar (its
 * static type-code initializer drags {@link NetworkMessageRegistry} into the game JVM) — the
 * in-game seam speaks these bytes through a hand-mirrored {@code MarkWriter} (seam-3),
 * byte-compared against this codec by the client verifier's Part G.
 */
public final class MarkFrameCodec {

    /** Magic = ASCII "MARK". */
    public static final int MAGIC = 0x4D41524B;

    /** Registered wire type code this frame carries. */
    public static final int MESSAGE_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .clientOwnerMarkWireEntry().wireTypeCode();

    /** Registered schema version of the owner-mark message. */
    public static final short SCHEMA_VERSION =
            (short) ClientOwnerMarkMessage.SCHEMA_VERSION;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** count(u16) + clientTick(i64). */
    public static final int PAYLOAD_BYTES = Short.BYTES + Long.BYTES; // 10
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 24

    /** Sentinel clientTick: the client had not yet seen a snapshot when it marked. */
    public static final long NO_SNAPSHOT_TICK = -1L;

    /** One decoded mark: the client's running count (u16, ≥1) + its last snapshot sim tick. */
    public record OwnerMark(int count, long clientTick) {
        public OwnerMark {
            if (count < 1 || count > 0xFFFF) {
                throw new IllegalArgumentException(
                        "owner mark count outside u16 [1,65535]: " + count);
            }
            if (clientTick < NO_SNAPSHOT_TICK) {
                throw new IllegalArgumentException(
                        "owner mark clientTick below the -1 no-snapshot sentinel: " + clientTick);
            }
        }
    }

    private MarkFrameCodec() {}

    /**
     * Encode one mark into a fresh {@link #FRAME_BYTES}-long array using only allowlist-clean
     * big-endian byte ops. CRC32 covers exactly the header + payload, like SNNK.
     * Throws {@link IllegalArgumentException} outside the count/tick domain.
     */
    public static byte[] encodeFrame(int count, long clientTick) {
        OwnerMark mark = new OwnerMark(count, clientTick); // domain validation
        byte[] buf = new byte[FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, MAGIC);
        off = putInt(buf, off, MESSAGE_TYPE_CODE);
        off = putShort(buf, off, SCHEMA_VERSION);
        off = putShort(buf, off, (short) mark.count());
        off = putLong(buf, off, mark.clientTick());
        // off now == HEADER_BYTES + PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /**
     * Read exactly one mark from {@code in}, blocking until {@link #FRAME_BYTES} arrive.
     * Returns {@code null} at a clean end of stream (zero bytes available). Throws on a
     * truncated frame, bad magic / type code / schema, a CRC mismatch, or an out-of-domain
     * payload.
     */
    public static OwnerMark readFrame(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != FRAME_BYTES) {
            throw new IOException("owner mark frame truncated: expected "
                    + FRAME_BYTES + " bytes, got " + frame.length);
        }
        int magic = getInt(frame, 0);
        if (magic != MAGIC) {
            throw new IOException(
                    "owner mark frame bad magic: 0x" + Integer.toHexString(magic));
        }
        return decodeAfterMagic(frame);
    }

    /**
     * The demux-read half: the caller (the CTRL up-lane magic peek) has already consumed the
     * 4 magic bytes; read the remaining {@link #FRAME_BYTES}{@code - 4} bytes and validate the
     * reconstructed full frame (the CRC covers the magic too). Fail-closed like
     * {@link #readFrame}; an EOF here is a truncation, never a clean end.
     */
    public static OwnerMark readFrameAfterMagic(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] rest = in.readNBytes(FRAME_BYTES - Integer.BYTES);
        if (rest.length != FRAME_BYTES - Integer.BYTES) {
            throw new IOException("owner mark frame truncated after magic: expected "
                    + (FRAME_BYTES - Integer.BYTES) + " bytes, got " + rest.length);
        }
        byte[] frame = new byte[FRAME_BYTES];
        putInt(frame, 0, MAGIC);
        System.arraycopy(rest, 0, frame, Integer.BYTES, rest.length);
        return decodeAfterMagic(frame);
    }

    /**
     * The DATAGRAM entry — one CTRL payload IS one whole
     * frame (magic included), so it must be exactly {@link #FRAME_BYTES}: a valid frame plus trailing
     * bytes is refused. The stream reader
     * {@link #readFrameAfterMagic} stays unchanged (the codec contract + the client verifier's Part G
     * read it; the datagram door no longer does) — a stream's NEXT frame may legitimately follow.
     */
    public static OwnerMark decodeDatagram(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length != FRAME_BYTES) {
            throw new IOException("owner mark datagram frame must be " + FRAME_BYTES
                    + " bytes, got " + payload.length);
        }
        int magic = getInt(payload, 0);
        if (magic != MAGIC) {
            throw new IOException("owner mark frame bad magic: 0x" + Integer.toHexString(magic));
        }
        return decodeAfterMagic(payload);
    }

    private static OwnerMark decodeAfterMagic(byte[] frame) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = Integer.BYTES; // magic already validated/reconstructed by the caller
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("owner mark wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != SCHEMA_VERSION) {
            throw new IOException("owner mark unsupported schema version: " + schema);
        }
        int count = getUnsignedShort(frame, off); off += 2;
        long clientTick = getLong(frame, off); off += 8;
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("owner mark frame CRC mismatch");
        }
        try {
            return new OwnerMark(count, clientTick); // count 0 / tick < -1 = fail-closed here
        } catch (IllegalArgumentException outOfDomain) {
            throw new IOException("owner mark payload out of domain: " + outOfDomain.getMessage());
        }
    }
}
