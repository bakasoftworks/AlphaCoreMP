package cc.starsector.server.runtime;

import cc.starsector.server.protocol.ClientFireDischargeDeclarationMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

import static cc.starsector.server.runtime.WireByteOps.getFloat;
import static cc.starsector.server.runtime.WireByteOps.getInt;
import static cc.starsector.server.runtime.WireByteOps.getLong;
import static cc.starsector.server.runtime.WireByteOps.getUnsignedShort;
import static cc.starsector.server.runtime.WireByteOps.putFloat;
import static cc.starsector.server.runtime.WireByteOps.putInt;
import static cc.starsector.server.runtime.WireByteOps.putLong;
import static cc.starsector.server.runtime.WireByteOps.putShort;

/**
 * Fixed-width wire codec for {@link FireDischargeDeclaration} — the declared-discharge
 * fire lane's client-&gt;server {@code PDCL} frame
 * ({@link ClientFireDischargeDeclarationMessage}).
 *
 * <p>Header style copies {@link GameplayEdgeEventCodec} (magic + registered wire type code +
 * schema version, so the bytes are the registered message, not an ad-hoc one); framing discipline
 * copies {@link PilotIntentWireCodec} (fixed width + CRC32 over everything before the trailer,
 * fail-closed read) because this frame rides the SAME in-game up-lane the {@code PINT} intent
 * frames do. The up direction is demultiplexed by magic exactly like the DOWN direction's
 * GPEE-vs-AWSS split: the server read peeks 4 magic bytes, {@code PINT} = intent,
 * {@code PDCL} = this frame via {@link #readFrameAfterMagic}.
 *
 * <p><b>Allowlist-clean transport.</b> Both directions here build directly into a
 * fixed {@code byte[}{@link #FRAME_BYTES}{@code ]} using only the shared {@link WireByteOps}
 * big-endian byte ops and the allowlisted {@link CRC32}. Unlike
 * {@link PilotIntentWireCodec} this class is NOT folded into the client mod jar — its static
 * type-code initializer drags {@link NetworkMessageRegistry} into the game JVM — so the in-game
 * seam speaks these bytes through the hand-mirrored {@code FireDischargeDeclarationWriter},
 * byte-compared against this codec.
 */
public final class FireDischargeDeclarationCodec {

    /** Magic = ASCII "PDCL" (Pilot DeCLaration). */
    public static final int MAGIC = 0x5044434C;

    /** Registered wire type code this frame carries. */
    public static final int MESSAGE_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .clientFireDischargeDeclarationWireEntry().wireTypeCode();

    /** Registered schema version of the declared-discharge message. */
    public static final short SCHEMA_VERSION =
            (short) ClientFireDischargeDeclarationMessage.SCHEMA_VERSION;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /**
     * shipId(long) + declSeq(int) + clientTick(long) + aimFacingDeg(float) + aimWorldX/Y(float×2,
     * v2) + mountIndex(int, v3) + muzzleX/muzzleY/bearingDeg/speed(float×4,
     * v4 mount-ray carry; all-NaN = no carried ray).
     */
    public static final int PAYLOAD_BYTES =
            Long.BYTES + Integer.BYTES + Long.BYTES + Float.BYTES + Float.BYTES + Float.BYTES
            + Integer.BYTES + Float.BYTES + Float.BYTES + Float.BYTES + Float.BYTES; // 52
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 66

    private FireDischargeDeclarationCodec() {}

    /** Serialize {@code decl} to {@code out} as exactly {@link #FRAME_BYTES} bytes (header + payload + CRC32). */
    public static void writeFrame(OutputStream out, FireDischargeDeclaration decl) throws IOException {
        Objects.requireNonNull(out, "out");
        byte[] bytes = encodeFrame(decl);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Encode {@code decl} into a fresh {@link #FRAME_BYTES}-long array using only allowlist-clean
     * big-endian byte ops, so the in-game client seam can serialize under the script
     * classloader {@code B}. CRC32 covers exactly the header + payload, like {@code PINT}.
     */
    public static byte[] encodeFrame(FireDischargeDeclaration decl) {
        Objects.requireNonNull(decl, "decl");
        byte[] buf = new byte[FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, MAGIC);
        off = putInt(buf, off, MESSAGE_TYPE_CODE);
        off = putShort(buf, off, SCHEMA_VERSION);
        off = putLong(buf, off, decl.shipId());
        off = putInt(buf, off, decl.declSeq());
        off = putLong(buf, off, decl.clientTick());
        off = putFloat(buf, off, decl.aimFacingDeg());
        off = putFloat(buf, off, decl.aimWorldX()); // v2 (aim carry)
        off = putFloat(buf, off, decl.aimWorldY()); // v2 (aim carry)
        off = putInt(buf, off, decl.mountIndex()); // v3 (mount identity)
        off = putFloat(buf, off, decl.muzzleX()); // v4 (mount-ray carry)
        off = putFloat(buf, off, decl.muzzleY()); // v4
        off = putFloat(buf, off, decl.bearingDeg()); // v4
        off = putFloat(buf, off, decl.speed()); // v4
        // off now == HEADER_BYTES + PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /**
     * Read exactly one {@link FireDischargeDeclaration} from {@code in}, blocking until
     * {@link #FRAME_BYTES} arrive. Returns {@code null} at a clean end of stream (zero bytes
     * available). Throws on a truncated frame, bad magic / type code / schema, or a CRC mismatch.
     */
    public static FireDischargeDeclaration readFrame(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != FRAME_BYTES) {
            throw new IOException("fire discharge declaration frame truncated: expected "
                    + FRAME_BYTES + " bytes, got " + frame.length);
        }
        int magic = getInt(frame, 0);
        if (magic != MAGIC) {
            throw new IOException(
                    "fire discharge declaration frame bad magic: 0x" + Integer.toHexString(magic));
        }
        return decodeAfterMagic(frame);
    }

    /**
     * The demux-read half: the caller (the up-lane magic peek routing {@code PINT} vs {@code PDCL})
     * has already consumed the 4 magic bytes; read the remaining {@link #FRAME_BYTES}{@code - 4}
     * bytes and validate the reconstructed full frame (the CRC covers the magic too). Fail-closed
     * like {@link #readFrame}; an EOF here is a truncation, never a clean end.
     */
    public static FireDischargeDeclaration readFrameAfterMagic(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] rest = in.readNBytes(FRAME_BYTES - Integer.BYTES);
        if (rest.length != FRAME_BYTES - Integer.BYTES) {
            throw new IOException("fire discharge declaration frame truncated after magic: expected "
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
     * bytes is refused (the "trailing-byte
     * strict" law the PDCL security row states, now enforced where the datagram lands). The TCP
     * demux ({@code UpLaneDemux}) keeps {@link #readFrameAfterMagic}: there the NEXT frame
     * legitimately follows on the same stream, so a remaining-bytes check would be wrong.
     */
    public static FireDischargeDeclaration decodeDatagram(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length != FRAME_BYTES) {
            throw new IOException("fire discharge declaration datagram frame must be " + FRAME_BYTES
                    + " bytes, got " + payload.length);
        }
        int magic = getInt(payload, 0);
        if (magic != MAGIC) {
            throw new IOException(
                    "fire discharge declaration frame bad magic: 0x" + Integer.toHexString(magic));
        }
        return decodeAfterMagic(payload);
    }

    private static FireDischargeDeclaration decodeAfterMagic(byte[] frame) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = Integer.BYTES; // magic already validated/reconstructed by the caller
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("fire discharge declaration wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != SCHEMA_VERSION) {
            throw new IOException(
                    "fire discharge declaration unsupported schema version: " + schema);
        }
        long shipId = getLong(frame, off); off += 8;
        int declSeq = getInt(frame, off); off += 4;
        long clientTick = getLong(frame, off); off += 8;
        float aimFacingDeg = getFloat(frame, off); off += 4;
        float aimWorldX = getFloat(frame, off); off += 4; // v2 (aim carry)
        float aimWorldY = getFloat(frame, off); off += 4; // v2 (aim carry)
        int mountIndex = getInt(frame, off); off += 4; // v3 (mount identity)
        float muzzleX = getFloat(frame, off); off += 4; // v4 (mount-ray carry)
        float muzzleY = getFloat(frame, off); off += 4; // v4
        float bearingDeg = getFloat(frame, off); off += 4; // v4
        float speed = getFloat(frame, off); off += 4; // v4
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("fire discharge declaration frame CRC mismatch");
        }
        try {
            return new FireDischargeDeclaration(
                    shipId, declSeq, clientTick, aimFacingDeg, aimWorldX, aimWorldY, mountIndex,
                    muzzleX, muzzleY, bearingDeg, speed);
        } catch (IllegalArgumentException badFields) {
            throw new IOException(
                    "fire discharge declaration fields invalid: " + badFields.getMessage(), badFields);
        }
    }

}
