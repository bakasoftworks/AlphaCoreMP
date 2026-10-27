package cc.starsector.server.runtime;

import cc.starsector.server.protocol.ClientAwsdNackMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

import static cc.starsector.server.runtime.WireByteOps.getInt;
import static cc.starsector.server.runtime.WireByteOps.getUnsignedShort;
import static cc.starsector.server.runtime.WireByteOps.putInt;
import static cc.starsector.server.runtime.WireByteOps.putShort;

/**
 * Fixed-width wire codec for the AWSD delta-reject NACK — the
 * client-&gt;server {@code SNNK} frame ({@link ClientAwsdNackMessage}).
 *
 * <p>Shape = {@link FireDischargeDeclarationCodec} verbatim (the PDCL/SNAM fixed-width CTRL
 * template: magic + registered wire type code + schema, CRC32 over the WHOLE header+payload
 * including the magic, fail-closed read) — deliberately NOT the
 * {@link ReservedControlFrameCodec} envelope family, which CRCs body-only. The CTRL up lane
 * demuxes by magic exactly like PDCL/SNAM/MRER; the server read peeks 4 magic bytes and
 * routes {@code SNNK} here via {@link #readFrameAfterMagic}.
 *
 * <p>Payload = entityId u16 + rejected-delta epoch u8 ⇒ a {@value #FRAME_BYTES}-B frame. The
 * epoch is ADVISORY (diagnostics only): the server re-keys unconditionally and never compares
 * it — in the missing-anchor trigger there is no anchor epoch to name, so all trigger sites
 * carry the REJECTED delta's epoch uniformly.
 *
 * <p>Like {@link FireDischargeDeclarationCodec}, this class is NOT folded into the client mod
 * jar (its static type-code initializer drags {@link NetworkMessageRegistry} into the game
 * JVM) — the in-game seam speaks these bytes through a hand-mirrored {@code AwsdNackWriter}
 * (seam-3), byte-compared against this codec by the client verifier's Part F.
 */
public final class AwsdNackFrameCodec {

    /** Magic = ASCII "SNNK" (SNapshot NacK). */
    public static final int MAGIC = 0x534E4E4B;

    /** Registered wire type code this frame carries. */
    public static final int MESSAGE_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .clientAwsdNackWireEntry().wireTypeCode();

    /** Registered schema version of the AWSD NACK message. */
    public static final short SCHEMA_VERSION =
            (short) ClientAwsdNackMessage.SCHEMA_VERSION;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** entityId(u16) + epoch(u8). */
    public static final int PAYLOAD_BYTES = Short.BYTES + Byte.BYTES; // 3
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 17

    /** One decoded NACK: the AWSD entityId (u16 domain) + the rejected delta's epoch (u8). */
    public record AwsdNack(int entityId, int epoch) {
        public AwsdNack {
            if (entityId < 0 || entityId > 0xFFFF) {
                throw new IllegalArgumentException(
                        "awsd nack entityId outside u16 [0,65535]: " + entityId);
            }
            if (epoch < 0 || epoch > 0xFF) {
                throw new IllegalArgumentException(
                        "awsd nack epoch outside u8 [0,255]: " + epoch);
            }
        }
    }

    private AwsdNackFrameCodec() {}

    /** Serialize one NACK to {@code out} as exactly {@link #FRAME_BYTES} bytes. */
    public static void writeFrame(OutputStream out, int entityId, int epoch) throws IOException {
        Objects.requireNonNull(out, "out");
        byte[] bytes = encodeFrame(entityId, epoch);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Encode one NACK into a fresh {@link #FRAME_BYTES}-long array using only allowlist-clean
     * big-endian byte ops. CRC32 covers exactly the header + payload, like PDCL.
     * Throws {@link IllegalArgumentException} outside the u16/u8 payload domain.
     */
    public static byte[] encodeFrame(int entityId, int epoch) {
        AwsdNack nack = new AwsdNack(entityId, epoch); // domain validation
        byte[] buf = new byte[FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, MAGIC);
        off = putInt(buf, off, MESSAGE_TYPE_CODE);
        off = putShort(buf, off, SCHEMA_VERSION);
        off = putShort(buf, off, (short) nack.entityId());
        buf[off] = (byte) nack.epoch();
        off += 1;
        // off now == HEADER_BYTES + PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /**
     * Read exactly one NACK from {@code in}, blocking until {@link #FRAME_BYTES} arrive.
     * Returns {@code null} at a clean end of stream (zero bytes available). Throws on a
     * truncated frame, bad magic / type code / schema, or a CRC mismatch.
     */
    public static AwsdNack readFrame(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != FRAME_BYTES) {
            throw new IOException("awsd nack frame truncated: expected "
                    + FRAME_BYTES + " bytes, got " + frame.length);
        }
        int magic = getInt(frame, 0);
        if (magic != MAGIC) {
            throw new IOException(
                    "awsd nack frame bad magic: 0x" + Integer.toHexString(magic));
        }
        return decodeAfterMagic(frame);
    }

    /**
     * The demux-read half: the caller (the CTRL up-lane magic peek) has already consumed the
     * 4 magic bytes; read the remaining {@link #FRAME_BYTES}{@code - 4} bytes and validate the
     * reconstructed full frame (the CRC covers the magic too). Fail-closed like
     * {@link #readFrame}; an EOF here is a truncation, never a clean end.
     */
    public static AwsdNack readFrameAfterMagic(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] rest = in.readNBytes(FRAME_BYTES - Integer.BYTES);
        if (rest.length != FRAME_BYTES - Integer.BYTES) {
            throw new IOException("awsd nack frame truncated after magic: expected "
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
     * {@link #readFrameAfterMagic} stays unchanged (the codec contract + the client verifier's Part F
     * read it; the datagram door no longer does) — a stream's NEXT frame may legitimately follow.
     */
    public static AwsdNack decodeDatagram(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length != FRAME_BYTES) {
            throw new IOException("awsd nack datagram frame must be " + FRAME_BYTES
                    + " bytes, got " + payload.length);
        }
        int magic = getInt(payload, 0);
        if (magic != MAGIC) {
            throw new IOException("awsd nack frame bad magic: 0x" + Integer.toHexString(magic));
        }
        return decodeAfterMagic(payload);
    }

    private static AwsdNack decodeAfterMagic(byte[] frame) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = Integer.BYTES; // magic already validated/reconstructed by the caller
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("awsd nack wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != SCHEMA_VERSION) {
            throw new IOException("awsd nack unsupported schema version: " + schema);
        }
        int entityId = getUnsignedShort(frame, off); off += 2;
        int epoch = frame[off] & 0xFF; off += 1;
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("awsd nack frame CRC mismatch");
        }
        return new AwsdNack(entityId, epoch); // u16/u8 by construction — never throws here
    }

}
